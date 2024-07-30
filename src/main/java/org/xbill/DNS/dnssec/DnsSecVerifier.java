// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2005 VeriSign. All rights reserved.
// Copyright (c) 2007-2024 NLnet Labs
// Copyright (c) 2013-2024 Ingo Bauersachs
package org.xbill.DNS.dnssec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.DNSSEC.DNSSECException;
import org.xbill.DNS.DNSSEC.InvalidDnskeyException;
import org.xbill.DNS.DNSSEC.KeyMismatchException;
import org.xbill.DNS.DNSSEC.SignatureExpiredException;
import org.xbill.DNS.DNSSEC.SignatureNotYetValidException;
import org.xbill.DNS.ExtendedErrorCodeOption;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

/**
 * A class for performing basic DNSSEC verification. The DNSJAVA package contains a similar class.
 * This is a reimplementation that allows us to have finer control over the validation process.
 *
 * @since 3.5
 */
@Slf4j
final class DnsSecVerifier {
  public static final String MAX_VALIDATE_RRSIGS_PROPERTY = "dnsjava.dnssec.max_validate_rrsigs";
  private final ValUtils valUtils;
  private int maxValidateRRsigs;

  public DnsSecVerifier(ValUtils valUtils) {
    this.valUtils = valUtils;
  }

  /**
   * Initialize the module. The recognized configuration values are:
   *
   * <ul>
   *   <li>{@value #MAX_VALIDATE_RRSIGS_PROPERTY}
   * </ul>
   *
   * @param config The configuration data for this module.
   */
  public void init(Properties config) {
    maxValidateRRsigs = Integer.parseInt(config.getProperty(MAX_VALIDATE_RRSIGS_PROPERTY, "8"));
  }

  /**
   * Find the matching DNSKEY(s) to an RRSIG within a DNSKEY rrset. Normally this will only return
   * one DNSKEY. It can return more than one, since KeyID/Footprints are not guaranteed to be
   * unique.
   *
   * @param dnskeyRrset The DNSKEY rrset to search.
   * @param signature The RRSIG to match against.
   * @return A List that contains one or more DNSKEYRecord objects; empty if a matching DNSKEY could
   *     not be found.
   */
  private List<DNSKEYRecord> findKey(RRset dnskeyRrset, RRSIGRecord signature) {
    if (!signature.getSigner().equals(dnskeyRrset.getName())) {
      log.trace(
          "Could not find appropriate key because incorrect keyset was supplied. Wanted: {}, got: {}",
          signature.getSigner(),
          dnskeyRrset.getName());
      return Collections.emptyList();
    }

    int keyid = signature.getFootprint();
    int alg = signature.getAlgorithm();
    List<DNSKEYRecord> res = new ArrayList<>(dnskeyRrset.size());
    for (Record r : dnskeyRrset.rrs(false)) {
      DNSKEYRecord dnskey = (DNSKEYRecord) r;
      if (dnskey.getAlgorithm() == alg && dnskey.getFootprint() == keyid) {
        res.add(dnskey);
      }
    }

    return res;
  }

  /**
   * Verify an RRset against a particular signature.
   *
   * @param rrset The RRset to verify.
   * @param sigrec The signature record that signs the RRset.
   * @param keyRrset The keys used to create the signature record.
   * @param date The date against which to verify the signature.
   * @return {@link SecurityStatus#SECURE} if the signature verified, {@link SecurityStatus#BOGUS}
   *     if it did not verify (for any reason), and {@link SecurityStatus#UNCHECKED} if verification
   *     could not be completed (usually because the public key was not available).
   */
  private JustifiedSecStatus verifySignature(
      SRRset rrset, RRSIGRecord sigrec, KeyEntry keyRrset, Instant date) {
    if (!rrset.getName().subdomain(sigrec.getSigner())) {
      log.debug("Signer name {} is off-tree for {}", sigrec.getSigner(), rrset.getName());
      return new JustifiedSecStatus(
          SecurityStatus.BOGUS,
          ExtendedErrorCodeOption.DNSSEC_BOGUS,
          R.get("dnskey.key_offtree", sigrec.getSigner(), rrset.getName()));
    }

    List<DNSKEYRecord> keys = this.findKey(keyRrset, sigrec);

    for (DNSKEYRecord dnskey : keys) {
      try {
        DNSSEC.verify(rrset, sigrec, dnskey, date);
        ValUtils.setCanonicalNsecOwner(rrset, sigrec);
        return new JustifiedSecStatus(SecurityStatus.SECURE, -1, null);
      } catch (KeyMismatchException kme) {
        return new JustifiedSecStatus(
            SecurityStatus.BOGUS, ExtendedErrorCodeOption.DNSSEC_BOGUS, R.get("dnskey.no_match"));
      } catch (SignatureExpiredException e) {
        return new JustifiedSecStatus(
            SecurityStatus.BOGUS,
            ExtendedErrorCodeOption.SIGNATURE_EXPIRED,
            R.get("dnskey.expired"));
      } catch (SignatureNotYetValidException e) {
        return new JustifiedSecStatus(
            SecurityStatus.BOGUS,
            ExtendedErrorCodeOption.SIGNATURE_NOT_YET_VALID,
            R.get("dnskey.not_yet_valid"));
      } catch (InvalidDnskeyException e) {
        return new JustifiedSecStatus(
            SecurityStatus.BOGUS, e.getEdeCode(), R.get("dnskey.invalid"));
      } catch (DNSSECException e) {
        log.error(
            "Failed to validate RRset <{}/{}/{}>",
            rrset.getName(),
            DClass.string(rrset.getDClass()),
            Type.string(rrset.getType()),
            e);
        return new JustifiedSecStatus(
            SecurityStatus.BOGUS, ExtendedErrorCodeOption.DNSSEC_BOGUS, R.get("dnskey.invalid"));
      }
    }

    log.trace("Could not find appropriate key for {}", sigrec);
    return new JustifiedSecStatus(
        SecurityStatus.UNCHECKED,
        ExtendedErrorCodeOption.DNSKEY_MISSING,
        R.get("dnskey.no_key", sigrec.getSigner()));
  }

  /**
   * Verifies an RRset. This routine does not modify the RRset. The RRset is presumed to be
   * verifiable, and the correct DNSKEY rrset is presumed to have been found.
   *
   * @param rrset The RRset to verify.
   * @param keyRrset The keys to verify the signatures in the RRset to check.
   * @param date The date against which to verify the rrset.
   * @return {@link SecurityStatus#SECURE} if the {@link RRset} verified positively, {@link
   *     SecurityStatus#BOGUS} otherwise.
   */
  public JustifiedSecStatus verify(SRRset rrset, KeyEntry keyRrset, Instant date) {
    List<RRSIGRecord> sigs = rrset.sigs();
    if (sigs.isEmpty()) {
      log.info(
          "RRset <{}/{}/{}> failed to verify due to a lack of signatures",
          rrset.getName(),
          DClass.string(rrset.getDClass()),
          Type.string(rrset.getType()));
      return new JustifiedSecStatus(
          SecurityStatus.BOGUS,
          ExtendedErrorCodeOption.RRSIGS_MISSING,
          R.get("validate.bogus.missingsig_named", rrset.getName(), Type.string(rrset.getType())));
    }

    AlgorithmRequirements needs = null;
    if (keyRrset.getAlgo() != null) {
      needs = new AlgorithmRequirements(valUtils);
      needs.initList(keyRrset.getAlgo());
      if (needs.getNum() == 0) {
        log.debug("{} has no known algorithms", rrset.getName());
        return new JustifiedSecStatus(
            SecurityStatus.INSECURE,
            ExtendedErrorCodeOption.UNSUPPORTED_DNSKEY_ALGORITHM,
            R.get("validate.insecure.noalg", rrset.getName()));
      }
    }

    JustifiedSecStatus res = null;
    int numVerified = 0;
    for (RRSIGRecord sigrec : sigs) {
      res = this.verifySignature(rrset, sigrec, keyRrset, date);
      if (res.status == SecurityStatus.SECURE) {
        if (needs == null || needs.setSecure(sigrec.getAlgorithm())) {
          return res;
        }
      } else if (needs != null && res.status == SecurityStatus.BOGUS) {
        needs.setBogus(sigrec.getAlgorithm());
      }

      numVerified++;
      if (numVerified > maxValidateRRsigs) {
        log.warn(
            "RRset <{}/{}/{}> failed to verify: too many signatures",
            rrset.getName(),
            DClass.string(rrset.getDClass()),
            Type.string(rrset.getType()));
        return new JustifiedSecStatus(
            SecurityStatus.BOGUS,
            ExtendedErrorCodeOption.DNSSEC_BOGUS,
            R.get("validate.bogus.rrsigtoomany", rrset.getName(), Type.string(rrset.getType())));
      }
    }

    log.warn(
        "RRset <{}/{}/{}> failed to verify: all signatures are BOGUS",
        rrset.getName(),
        DClass.string(rrset.getDClass()),
        Type.string(rrset.getType()));
    return res;
  }

  /**
   * Verify an RRset against a single DNSKEY. Use this when you must be certain that an RRset signed
   * and verifies with a particular DNSKEY (as opposed to a particular DNSKEY rrset).
   *
   * @param rrset The rrset to verify.
   * @param dnskey The DNSKEY to verify with.
   * @param date The date against which to verify the rrset.
   * @return {@link SecurityStatus#SECURE} if the {@link RRset} verified, {@link
   *     SecurityStatus#BOGUS} otherwise.
   */
  public JustifiedSecStatus verify(RRset rrset, DNSKEYRecord dnskey, Instant date) {
    List<RRSIGRecord> sigs = rrset.sigs();
    if (sigs.isEmpty()) {
      log.warn(
          "RRset <{}/{}/{}> failed to verify due to lack of signatures",
          rrset.getName(),
          DClass.string(rrset.getDClass()),
          Type.string(rrset.getType()));
      return new JustifiedSecStatus(
          SecurityStatus.BOGUS,
          ExtendedErrorCodeOption.RRSIGS_MISSING,
          R.get("validate.bogus.missingsig_named", rrset.getName(), Type.string(rrset.getType())));
    }

    DNSSECException lastException = null;
    int numVerified = 0;
    for (RRSIGRecord sigrec : sigs) {
      // Skip RRSIGs that do not match our given key's footprint.
      if (sigrec.getFootprint() != dnskey.getFootprint()) {
        continue;
      }

      numVerified++;
      try {
        DNSSEC.verify(rrset, sigrec, dnskey, date);
        return new JustifiedSecStatus(SecurityStatus.SECURE, -1, null);
      } catch (DNSSECException e) {
        log.warn(
            "Failed to validate RRset <{}/{}/{}> with signature {}",
            rrset.getName(),
            DClass.string(rrset.getDClass()),
            Type.string(rrset.getType()),
            sigrec.getFootprint(),
            e);
        lastException = e;
      }

      if (numVerified > maxValidateRRsigs) {
        log.warn(
            "RRset <{}/{}/{}> failed to verify: too many signatures",
            rrset.getName(),
            DClass.string(rrset.getDClass()),
            Type.string(rrset.getType()));
        return new JustifiedSecStatus(
            SecurityStatus.BOGUS,
            ExtendedErrorCodeOption.DNSSEC_BOGUS,
            R.get("validate.bogus.rrsigtoomany", rrset.getName(), Type.string(rrset.getType())));
      }
    }

    log.warn(
        "RRset <{}/{}/{}> failed to verify: all signatures were BOGUS",
        rrset.getName(),
        DClass.string(rrset.getDClass()),
        Type.string(rrset.getType()));
    int edeReason = ExtendedErrorCodeOption.DNSSEC_BOGUS;
    String reason = "dnskey.invalid";
    if (numVerified == 0) {
      edeReason = ExtendedErrorCodeOption.DNSKEY_MISSING;
      reason = "dnskey.no_ds_match";
    } else if (lastException instanceof SignatureExpiredException) {
      edeReason = ExtendedErrorCodeOption.SIGNATURE_EXPIRED;
      reason = "dnskey.expired";
    } else if (lastException instanceof SignatureNotYetValidException) {
      edeReason = ExtendedErrorCodeOption.SIGNATURE_NOT_YET_VALID;
      reason = "dnskey.not_yet_valid";
    }

    return new JustifiedSecStatus(SecurityStatus.BOGUS, edeReason, R.get(reason));
  }
}
