/*
  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at http://mozilla.org/MPL/2.0/.

  Copyright (c) UXBOX Labs SL

  This file contains a UUIDv8 with conformance with
  https://datatracker.ietf.org/doc/html/draft-peabody-dispatch-new-uuid-format

  It has the following characteristics:
   - time ordered
   - 48bits timestamp
   - custom epoch: milliseconds since 2022-01-01T00:00:00
   - 14bits monotonic clockseq (allows generate 16k uuids/ms)
   - mostly static random 60 bits (initialized at class load or clock regression)

   This is results in a constantly increasing, sortable, very fast uuid impl.
*/

package app.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public class UUIDv8 {
  public static final long timeRef = 1640991600L * 1000L; // ms since 2022-01-01T00:00:00
  public static final long clockSeqMax = 16384L; // 14 bits space
  public static final Clock clock = Clock.systemUTC();

  public static long baseMsb;
  public static long baseLsb;
  public static long clockSeq = 0L;
  public static long lastTs = 0L;

  public static SecureRandom srandom = new java.security.SecureRandom();

  public static synchronized void initializeSeed() {
    baseMsb = 0x0000_0000_0000_8000L; // Version 8
    baseLsb = srandom.nextLong() & 0x0fff_ffff_ffff_ffffL | 0x8000_0000_0000_0000L; // Variant 2
  }

  static {
    initializeSeed();
  }

  public static synchronized UUID create(final long ts, final long clockSeq) {
    long msb = (baseMsb
             | ((ts << 16) & 0xffff_ffff_ffff_0000L)
             | ((clockSeq >>> 2) & 0x0000_0000_0000_0fffL));
    long lsb = baseLsb | ((clockSeq << 60) & 0x3000_0000_0000_0000L);
    return new UUID(msb, lsb);
  }

  public static synchronized UUID create() {
    while (true) {
      long ts = clock.millis() - timeRef;

      // Protect from clock regression
      if ((ts - lastTs) < 0) {
        initializeSeed();
        clockSeq = 0;
        continue;
      }

      if (lastTs == ts) {
        if (clockSeq < clockSeqMax) {
          clockSeq++;
        } else {
          continue;
        }
      } else {
        lastTs = ts;
        clockSeq = 0;
      }

      return create(ts, clockSeq);
    }
  }
}
