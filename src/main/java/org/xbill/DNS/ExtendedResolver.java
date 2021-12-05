// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package org.xbill.DNS;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * An implementation of {@link Resolver} that can send queries to multiple servers, sending the
 * queries multiple times if necessary.
 *
 * @see Resolver
 * @author Brian Wellington
 */
@Slf4j
public class ExtendedResolver implements Resolver {
  private static class Resolution {
    private final Message query;
    private final int[] attempts;
    private final int retriesPerResolver;
    private final long endTime;
    private List<ResolverEntry> resolvers;
    private int currentResolver;

    Resolution(ExtendedResolver eres, Message query) {
      resolvers = new ArrayList<>(eres.resolvers);
      endTime = System.nanoTime() + eres.timeout.toNanos();
      if (eres.loadBalance) {
        int start = eres.lbStart.updateAndGet(i -> (i + 1) % resolvers.size());
        if (start > 0) {
          List<ResolverEntry> shuffle = new ArrayList<>(resolvers.size());
          for (int i = 0; i < resolvers.size(); i++) {
            int pos = (i + start) % resolvers.size();
            shuffle.add(resolvers.get(pos));
          }

          resolvers = shuffle;
        }
      } else {
        resolvers =
            resolvers.stream()
                .sorted(Comparator.comparingInt(re -> re.failures.get()))
                .collect(Collectors.toList());
      }

      attempts = new int[resolvers.size()];
      retriesPerResolver = eres.retries;
      this.query = query;
    }

    /* Asynchronously sends a message. */
    private CompletionStage<Message> send(Executor executorService) {
      ResolverEntry r = resolvers.get(currentResolver);
      log.debug(
          "Sending {}/{}, id={} to resolver {} ({}), attempt {} of {}",
          query.getQuestion().getName(),
          Type.string(query.getQuestion().getType()),
          query.getHeader().getID(),
          currentResolver,
          r.resolver,
          attempts[currentResolver] + 1,
          retriesPerResolver);
      attempts[currentResolver]++;
      return r.resolver.sendAsync(query, executorService);
    }

    /* Start an asynchronous resolution */
    private CompletionStage<Message> startAsync(Executor executorService) {
      return send(executorService)
          .handle((result, ex) -> handle(result, ex, executorService))
          .thenCompose(Function.identity());
    }

    private CompletionStage<Message> handle(
        Message result, Throwable ex, Executor executorService) {
      AtomicInteger failureCounter = resolvers.get(currentResolver).failures;
      if (ex != null) {
        log.debug(
            "Failed to resolve {}/{}, id={} with resolver {} ({}) on attempt {} of {}, reason={}",
            query.getQuestion().getName(),
            Type.string(query.getQuestion().getType()),
            query.getHeader().getID(),
            currentResolver,
            resolvers.get(currentResolver).resolver,
            attempts[currentResolver],
            retriesPerResolver,
            ex.getMessage());

        failureCounter.incrementAndGet();

        if (endTime - System.nanoTime() < 0) {
          CompletableFuture<Message> f = new CompletableFuture<>();
          f.completeExceptionally(
              new IOException(
                  "Timed out while trying to resolve "
                      + query.getQuestion().getName()
                      + "/"
                      + Type.string(query.getQuestion().type)
                      + ", id="
                      + query.getHeader().getID()));
          return f;
        } else {
          // go to next resolver, until retries on all resolvers are exhausted
          currentResolver = (currentResolver + 1) % resolvers.size();
          if (attempts[currentResolver] < retriesPerResolver) {
            return send(executorService)
                .handle((r, t) -> handle(r, t, executorService))
                .thenCompose(Function.identity());
          }

          CompletableFuture<Message> f = new CompletableFuture<>();
          f.completeExceptionally(ex);
          return f;
        }
      } else {
        failureCounter.updateAndGet(i -> i > 0 ? (int) Math.log(i) : 0);
        return CompletableFuture.completedFuture(result);
      }
    }
  }

  @RequiredArgsConstructor
  private static class ResolverEntry {
    private final Resolver resolver;
    private final AtomicInteger failures;

    ResolverEntry(Resolver r) {
      this(r, new AtomicInteger(0));
    }

    @Override
    public String toString() {
      return resolver.toString();
    }
  }

  /**
   * Default timeout until resolving is aborted.
   *
   * @since 3.2
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Default timeout until resolving with one of the used resolvers fails.
   *
   * @since 3.2
   */
  public static final Duration DEFAULT_RESOLVER_TIMEOUT = Duration.ofSeconds(5);

  private final List<ResolverEntry> resolvers = new CopyOnWriteArrayList<>();
  private final AtomicInteger lbStart = new AtomicInteger();
  private boolean loadBalance;
  private int retries = 3;
  private Duration timeout = DEFAULT_TIMEOUT;

  /**
   * Creates a new Extended Resolver. The default {@link ResolverConfig} is used to determine the
   * servers for which {@link SimpleResolver}s are initialized. The timeout for each server is
   * initialized with {@link #DEFAULT_RESOLVER_TIMEOUT}.
   */
  public ExtendedResolver() {
    List<InetSocketAddress> servers = ResolverConfig.getCurrentConfig().servers();
    resolvers.addAll(
        servers.stream()
            .map(
                server -> {
                  Resolver r = new SimpleResolver(server);
                  r.setTimeout(DEFAULT_RESOLVER_TIMEOUT);
                  return new ResolverEntry(r);
                })
            .collect(Collectors.toList()));
  }

  /**
   * Creates a new instance with {@link SimpleResolver}s. The timeout for each server is initialized
   * with {@link #DEFAULT_RESOLVER_TIMEOUT}.
   *
   * @param servers An array of server names or IP addresses for which {@link SimpleResolver}s are
   *     initialized.
   * @exception UnknownHostException A server name could not be resolved
   */
  public ExtendedResolver(String[] servers) throws UnknownHostException {
    for (String server : servers) {
      Resolver r = new SimpleResolver(server);
      r.setTimeout(DEFAULT_RESOLVER_TIMEOUT);
      resolvers.add(new ResolverEntry(r));
    }
  }

  /**
   * Creates a new Extended Resolver
   *
   * @param resolvers An array of pre-initialized {@link Resolver}s.
   */
  public ExtendedResolver(Resolver[] resolvers) {
    this(Arrays.asList(resolvers));
  }

  /**
   * Creates a new {@link ExtendedResolver}. No timeout value is applied to the individual
   * resolvers, make sure their timeout is smaller than the timeout of this {@link
   * ExtendedResolver}.
   *
   * @param resolvers An iterable of pre-initialized {@link Resolver}s.
   */
  public ExtendedResolver(Iterable<Resolver> resolvers) {
    for (Resolver r : resolvers) {
      this.resolvers.add(new ResolverEntry(r));
    }
  }

  @Override
  public void setPort(int port) {
    for (ResolverEntry re : resolvers) {
      re.resolver.setPort(port);
    }
  }

  @Override
  public void setTCP(boolean flag) {
    for (ResolverEntry re : resolvers) {
      re.resolver.setTCP(flag);
    }
  }

  @Override
  public void setIgnoreTruncation(boolean flag) {
    for (ResolverEntry re : resolvers) {
      re.resolver.setIgnoreTruncation(flag);
    }
  }

  @Override
  public void setEDNS(int version, int payloadSize, int flags, List<EDNSOption> options) {
    for (ResolverEntry re : resolvers) {
      re.resolver.setEDNS(version, payloadSize, flags, options);
    }
  }

  @Override
  public void setTSIGKey(TSIG key) {
    for (ResolverEntry re : resolvers) {
      re.resolver.setTSIGKey(key);
    }
  }

  @Override
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * Sets the timeout for the {@link ExtendedResolver}.
   *
   * <p>Note that this <i>only</i> sets the timeout for the {@link ExtendedResolver}, not the
   * individual {@link Resolver}s. If the timeout expires, the {@link ExtendedResolver} simply stops
   * retrying, it does not abort running queries. The timeout value must be larger than that for the
   * individual resolver to have any effect.
   *
   * @see #ExtendedResolver()
   * @see #ExtendedResolver(String[])
   * @param timeout The amount of time to wait before sending further queries.
   */
  @Override
  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  /**
   * Sends a message to multiple servers, and queries are sent multiple times until either a
   * successful response is received, or it is clear that there is no successful response.
   *
   * @param query The query to send.
   * @return A future that completes when the query is finished.
   */
  @Override
  public CompletionStage<Message> sendAsync(Message query) {
    return sendAsync(query, ForkJoinPool.commonPool());
  }

  /**
   * Sends a message to multiple servers, and queries are sent multiple times until either a
   * successful response is received, or it is clear that there is no successful response.
   *
   * @param query The query to send.
   * @param executor The service to use for async operations.
   * @return A future that completes when the query is finished.
   */
  @Override
  public CompletionStage<Message> sendAsync(Message query, Executor executor) {
    Resolution res = new Resolution(this, query);
    return res.startAsync(executor);
  }

  /** Returns the nth resolver used by this ExtendedResolver */
  public Resolver getResolver(int n) {
    if (n < resolvers.size()) {
      return resolvers.get(n).resolver;
    }
    return null;
  }

  /** Returns all resolvers used by this ExtendedResolver */
  public Resolver[] getResolvers() {
    return resolvers.stream().map(re -> re.resolver).toArray(Resolver[]::new);
  }

  /** Adds a new resolver to be used by this ExtendedResolver */
  public void addResolver(Resolver r) {
    resolvers.add(new ResolverEntry(r));
  }

  /** Deletes a resolver used by this ExtendedResolver */
  public void deleteResolver(Resolver r) {
    resolvers.removeIf(re -> re.resolver == r);
  }

  /**
   * Gets whether the servers receive queries load balanced.
   *
   * @since 3.2
   */
  public boolean getLoadBalance() {
    return loadBalance;
  }

  /**
   * Sets whether the servers should be load balanced.
   *
   * @param flag If true, servers will be tried in round-robin order. If false, servers will always
   *     be queried in the same order.
   */
  public void setLoadBalance(boolean flag) {
    loadBalance = flag;
  }

  /**
   * Gets the number of retries sent to each server per query.
   *
   * @since 3.2
   */
  public int getRetries() {
    return retries;
  }

  /** Sets the number of retries sent to each server per query */
  public void setRetries(int retries) {
    this.retries = retries;
  }

  @Override
  public String toString() {
    return "ExtendedResolver of " + resolvers;
  }
}
