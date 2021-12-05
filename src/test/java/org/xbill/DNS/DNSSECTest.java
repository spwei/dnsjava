// SPDX-License-Identifier: BSD-3-Clause
package org.xbill.DNS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DNSSEC.DNSSECException;

class DNSSECTest {
  private final TXTRecord txt = new TXTRecord(Name.root, DClass.IN, 3600, "test");

  @Test
  void testECDSALeadingZeroUndersize() throws IOException, DNSSECException {
    DNSKEYRecord dnskey =
        (DNSKEYRecord)
            Record.fromString(
                Name.root,
                Type.DNSKEY,
                DClass.IN,
                3600,
                "256 3 13 HgcQzDrxDm641ASGyEF0MXrjDji4XDnWzjrY9VoIn5GfAvHpuqI2W8yihplAz6C/56Zxq1XbAHjLZATfhZFmaA==",
                Name.root);
    RRSIGRecord rrsig =
        (RRSIGRecord)
            Record.fromString(
                Name.root,
                Type.RRSIG,
                DClass.IN,
                3600,
                "TXT 13 0 3600 19700101000003 19700101000000 46271 . dRwMEthIeGiucMcEcDmwixM8/LZcZ+W6lMM0KDSY5rwAGrm1j7tS/VU6xs+rpD5dSRmBYosinkWD6Jk3zRmyBQ==",
                Name.root);

    RRset rrset = new RRset();
    rrset.addRR(txt);
    rrset.addRR(rrsig);
    DNSSEC.verify(rrset, rrsig, dnskey, Instant.ofEpochMilli(60));
  }

  @Test
  void testECDSALeadingZeroOversize() throws IOException, DNSSECException {
    DNSKEYRecord dnskey =
        (DNSKEYRecord)
            Record.fromString(
                Name.root,
                Type.DNSKEY,
                DClass.IN,
                3600,
                "256 3 13 OYt2tO1n75q/Wb6CglqPVrU22f02clZehWamgXc9ZGPhVMAerzPR9/bhf1XxtC3xAR9riVuGh9CEPVvmiNqukQ==",
                Name.root);
    RRSIGRecord rrsig =
        (RRSIGRecord)
            Record.fromString(
                Name.root,
                Type.RRSIG,
                DClass.IN,
                3600,
                "TXT 13 0 3600 19700101000003 19700101000000 25719 . m6sD/b0ZbfBXsQruhq5dYTnHGaA+PRTL5Y1W36rMdnGBb7eOJRRzDS5Wk5hZlrS4RUKQ/tKMCn7lsl9fn4U2lw==",
                Name.root);

    RRset rrset = new RRset();
    rrset.addRR(txt);
    rrset.addRR(rrsig);
    DNSSEC.verify(rrset, rrsig, dnskey, Instant.ofEpochMilli(60));
  }

  @Test
  void testDSALeadingZeroUndersize() throws DNSSECException, IOException {
    DNSKEYRecord dnskey =
        (DNSKEYRecord)
            Record.fromString(
                Name.root,
                Type.DNSKEY,
                DClass.IN,
                3600,
                "256 3 3 AJYu3cw2nLqOuyYO5rahJtk0bjjF/KaCzo4Syrom78z3EQ5SbbB4sF7ey80etKII864WF64B81uRpH5t9jQTxeEu0ImbzRMqzVDZkVG9xD7nN1kuF2eEcbJ6nPRO6RpJxRR9samq8kTwWkNNZIaTHS0UJxueNQMLcf1z2heQabMuKTVjDhwgYjVNDaIKbEFuUL55TKRAt3Xr7t5zCMLaujMvqNHOzCFEusXN5mXjJqAj8J0l4B4tbL7M4iIFZeXJDXGCEcsBbNrVAfFnlOO06B6dkB8L",
                Name.root);
    RRSIGRecord rrsig =
        (RRSIGRecord)
            Record.fromString(
                Name.root,
                Type.RRSIG,
                DClass.IN,
                3600L,
                "TXT 3 0 3600 19700101000003 19700101000000 36714 . AAAycZeIdBGB7vjlFzd5+ZgV8IxGRLpLierdV1KO4SGIy707hKUXJRc=",
                Name.root);

    RRset set = new RRset();
    set.addRR(txt);
    set.addRR(rrsig);
    DNSSEC.verify(set, rrsig, dnskey, Instant.ofEpochMilli(60));
  }

  @Test
  void testDSALeadingZeroOversize() throws DNSSECException, IOException {
    DNSKEYRecord dnskey =
        (DNSKEYRecord)
            Record.fromString(
                Name.root,
                Type.DNSKEY,
                DClass.IN,
                3600,
                "256 3 3 AJYu3cw2nLqOuyYO5rahJtk0bjjF/KaCzo4Syrom78z3EQ5SbbB4sF7ey80etKII864WF64B81uRpH5t9jQTxeEu0ImbzRMqzVDZkVG9xD7nN1kuF2eEcbJ6nPRO6RpJxRR9samq8kTwWkNNZIaTHS0UJxueNQMLcf1z2heQabMuKTVjDhwgYjVNDaIKbEFuUL55TKQflphJYUXcb2M3wKNGoXP7NufzhfVaDtiS44waWjC8IN98Ab+SPPfM4+xgTsgzWt8KvzL8hhqSW+4+5zjiQ6UG",
                Name.root);
    RRSIGRecord rrsig =
        (RRSIGRecord)
            Record.fromString(
                Name.root,
                Type.RRSIG,
                DClass.IN,
                3600L,
                "TXT 3 0 3600 19700101000003 19700101000000 57407 . AIh8Bp0EFNszs3cB0gNatjWy8tBrgUAUe1gTHkVsm1pva1GYWOW/FbA=",
                Name.root);

    RRset set = new RRset();
    set.addRR(txt);
    set.addRR(rrsig);
    DNSSEC.verify(set, rrsig, dnskey, Instant.ofEpochMilli(60));
  }

  @Test
  void testDigestRrsetOrdering() {
    Name name = Name.fromConstantString("a.");
    Record a1 = new CNAMERecord(name, DClass.IN, 60, Name.fromConstantString("a.b.c."));
    Record a2 = new CNAMERecord(name, DClass.IN, 60, Name.fromConstantString("aa.bb.cc."));

    RRSIGRecord s1 =
        new RRSIGRecord(
            name,
            DClass.IN,
            60,
            Type.CNAME,
            0xF,
            0x60,
            Instant.now(),
            Instant.now(),
            0xA,
            name,
            new byte[] {0xa, 0x0});
    RRSIGRecord s2 =
        new RRSIGRecord(
            name,
            DClass.IN,
            60,
            Type.CNAME,
            0xF,
            0x60,
            Instant.now(),
            Instant.now(),
            0xB,
            name,
            new byte[] {0x0, 0xa});
    RRset rrset = new RRset();
    rrset.addRR(a2);
    rrset.addRR(a1);
    rrset.addRR(s1);
    rrset.addRR(s2);
    assertArrayEquals(DNSSEC.digestRRset(s1, rrset), DNSSEC.digestRRset(s1, rrset));
  }
}
