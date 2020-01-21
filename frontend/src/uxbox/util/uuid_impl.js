/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 *
 * Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>
 */
/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2010-2016 Robert Kieffer and other contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
"use strict";

goog.provide("uxbox.util.uuid_impl");
goog.require("uxbox.util.rng_impl");

goog.scope(function() {
  const self = uxbox.util.uuid_impl;
  const rng = uxbox.util.rng_impl;

  const hexMap = [];
  for (let i = 0; i < 256; i++) {
    hexMap[i] = (i + 0x100).toString(16).substr(1);
  }

  function toHexString(buf) {
    let i = 0;
    return  (hexMap[buf[i++]] +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] + '-' +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] + '-' +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] + '-' +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] + '-' +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] +
             hexMap[buf[i++]] +
             hexMap[buf[i++]]);
  }

  self.v4 = function() {
    const buf = rng.getBytes(16);
    buf[6] = (buf[6] & 0x0f) | 0x40;
    buf[8] = (buf[8] & 0x3f) | 0x80;
    return toHexString(buf);
  };

  let initialized = false;
  let node;
  let clockseq;
  let lastms = 0;
  let lastns = 0;

  self.v1 = function() {
    let cs = clockseq;

    if (!initialized) {
      const seed = rng.getBytes(8);

      // Per 4.5, create and 48-bit node id, (47 random bits + multicast bit = 1)
      node = [
        seed[0] | 0x01,
        seed[1],
        seed[2],
        seed[3],
        seed[4],
        seed[5]
      ];

      // Per 4.2.2, randomize (14 bit) clockseq
      cs = clockseq = (seed[6] << 8 | seed[7]) & 0x3fff;
      initialized = true;
    }

    let ms = Date.now();
    let ns = lastns + 1;
    let dt = (ms - lastms) + (ns - lastns) / 10000;

    // Per 4.2.1.2, Bump clockseq on clock regression
    if (dt < 0) {
      cs = cs + 1 & 0x3fff;
    }

    // Reset nsecs if clock regresses (new clockseq) or we've moved onto a new
    // time interval
    if (dt < 0 || ms > lastms) {
      ns = 0;
    }

    // Per 4.2.1.2 Throw error if too many uuids are requested
    if (ns >= 10000) {
      throw new Error("uuid v1 can't create more than 10M uuids/s")
    }

    lastms = ms;
    lastns = ns;
    clockseq = cs;

    // Per 4.1.4 - Convert from unix epoch to Gregorian epoch
    ms += 12219292800000;

    let i = 0;
    let buf = new Uint8Array(16);

    // `time_low`
    var tl = ((ms & 0xfffffff) * 10000 + ns) % 0x100000000;
    buf[i++] = tl >>> 24 & 0xff;
    buf[i++] = tl >>> 16 & 0xff;
    buf[i++] = tl >>> 8 & 0xff;
    buf[i++] = tl & 0xff;

    // `time_mid`
    var tmh = (ms / 0x100000000 * 10000) & 0xfffffff;
    buf[i++] = tmh >>> 8 & 0xff;
    buf[i++] = tmh & 0xff;

    // `time_high_and_version`
    buf[i++] = tmh >>> 24 & 0xf | 0x10; // include version
    buf[i++] = tmh >>> 16 & 0xff;

    // `clock_seq_hi_and_reserved` (Per 4.2.2 - include variant)
    buf[i++] = cs >>> 8 | 0x80;

    // `clock_seq_low`
    buf[i++] = cs & 0xff;

    // `node`
    for (var n = 0; n < 6; ++n) {
      buf[i + n] = node[n];
    }

    return toHexString(buf);
  }
});
