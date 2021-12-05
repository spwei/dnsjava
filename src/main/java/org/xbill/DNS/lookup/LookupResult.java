// SPDX-License-Identifier: BSD-3-Clause
package org.xbill.DNS.lookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

/** LookupResult instances holds the result of a successful lookup operation. */
@Data
public final class LookupResult {
  /** An unmodifiable list of records that this instance wraps, may not be null but can be empty */
  private final List<Record> records;

  /**
   * In the case of CNAME or DNAME indirection, this property contains the original name as well as
   * any intermediate redirect targets except the last one. For example, if X is a CNAME pointing to
   * Y which is a CNAME pointing to Z which has an A record, aliases will hold X and Y after
   * successful lookup.
   */
  private final List<Name> aliases;

  /**
   * Construct an instance with the provided records and, in the case of a CNAME or DNAME
   * indirection a List of aliases.
   *
   * @param records a list of records to return.
   * @param aliases a list of aliases discovered during lookup, or null if there was no indirection.
   */
  public LookupResult(List<Record> records, List<Name> aliases) {
    this.records = Collections.unmodifiableList(new ArrayList<>(records));
    this.aliases =
        aliases == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(aliases));
  }
}
