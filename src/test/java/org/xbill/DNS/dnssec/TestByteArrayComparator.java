// SPDX-License-Identifier: BSD-3-Clause
package org.xbill.DNS.dnssec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestByteArrayComparator {
  private final byte[] b1 = new byte[] {0};
  private final byte[] b2 = new byte[] {0};
  private final byte[] b3 = new byte[] {1};
  private final byte[] b4 = new byte[] {1, 0};

  @Test
  void testEquals() {
    assertEquals(0, ByteArrayComparator.compare(b1, b2));
  }

  @Test
  void testLessThan() {
    assertEquals(-1, ByteArrayComparator.compare(b2, b3));
    assertEquals(-1, ByteArrayComparator.compare(b1, b4));
  }

  @Test
  void testGreaterThan() {
    assertEquals(1, ByteArrayComparator.compare(b3, b2));
    assertEquals(1, ByteArrayComparator.compare(b4, b1));
  }
}
