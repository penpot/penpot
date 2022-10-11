/*
  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at http://mozilla.org/MPL/2.0/.

  Copyright (c) KALEIDOS INC

  This file contains a UUIDv8 with conformance with
  https://datatracker.ietf.org/doc/html/draft-peabody-dispatch-new-uuid-format

  It has the following characteristics:
   - time ordered
   - 48bits timestamp (milliseconds precision, with custom epoch: 2022-01-01T00:00:00)
   - 14bits random clockseq (monotonically increasing on timestamp conflict)
   - spin locks (blocks) if more than 16384 ids/ms is generated in a single host
   - 56bits of randomnes generated statically on load (resets on clock regression)
   - 4 bits of user defined tag (defaults to 1 on jvm and 0 on js)

   This results in a constantly increasing, sortable, very fast
   and easy to visually read uuid implementation.
*/

package app.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Instant;

public class UUIDv8 {
  private static final long timeRef = 1640995200000L; // ms since 2022-01-01T00:00:00
  private static final Clock clock = Clock.systemUTC();
  private static final Lock lock = new ReentrantLock();
  private static final long baseMsb = 0x0000_0000_0000_8000L; // Version 8
  private static final long baseLsb = 0x8000_0000_0000_0000L; // Variant 2
  private static final long maxCs = 0x0000_0000_0000_3fffL;

  private static final SecureRandom srandom = new java.security.SecureRandom();

  private static long countCs = 0L;
  private static long lastCs = 0L;
  private static long lastTs = 0L;
  private static long lastRd = 0L;

  static {
    lastRd = (srandom.nextLong() & 0xffff_ffff_ffff_f1ffL);
    lastCs = (srandom.nextLong() & maxCs);
  }

  public static UUID create(final long ts, final long lastRd, final long lastCs) {
    long msb = (baseMsb
                | (lastRd & 0xffff_ffff_ffff_0fffL));

    long lsb = (baseLsb
                | ((ts << 14) & 0x3fff_ffff_ffff_c000L)
                | lastCs);

    return new UUID(msb, lsb);
  }

  public static void setTag(final long tag) {
    lock.lock();
    try {
      if (tag > 0x0000_0000_0000_000fL) {
        throw new IllegalArgumentException("tag value should fit in 4bits");
      }

      lastRd = (lastRd
                & 0xffff_ffff_ffff_f0ffL
                | ((tag << 8) & 0x0000_0000_0000_0f00L));

    } finally {
      lock.unlock();
    }
  }

  public static Instant getTimestamp(final UUID uuid) {
    final long lsb = uuid.getLeastSignificantBits();
    return Instant.EPOCH.plusMillis(timeRef).plusMillis((lsb >>> 14) & 0x0000_ffff_ffff_ffffL);
  }

  public static UUID create() {
    lock.lock();
    try {
      while (true) {
        long ts = (clock.millis() - timeRef); // in millis

        // If clock regression happens, regenerate lastRd
        if ((ts - lastTs) < 0) {
          // Clear and replace the 56 bits of randomness (60bits - 4 bits tag)
          lastRd = (lastRd
                    & 0x0000_0000_0000_0f00L
                    | (srandom.nextLong() & 0xffff_ffff_ffff_f0ffL));

          countCs = 0;
          continue;
        }

        // If last timestamp is the same as the current one we proceed
        // to increment the counters.
        if (lastTs == ts) {
          if (countCs < maxCs) {
            lastCs = (lastCs + 1L) & maxCs;
            countCs++;
          } else {
            continue;
          }
        } else {
          lastTs = ts;
          lastCs = srandom.nextLong() & maxCs;
          countCs = 0;
        }

        return create(ts, lastRd, lastCs);
      }
    } finally {
      lock.unlock();
    }
  }
}
