/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */
"use strict";

goog.require("cljs.core");
goog.provide("app.common.uuid_impl");

goog.scope(function() {
  const core = cljs.core;
  const global = goog.global;
  const self = app.common.uuid_impl;

  const fill = (() => {
    if (typeof global.crypto !== "undefined" &&
        typeof global.crypto.getRandomValues !== "undefined") {
      return (buf) => {
        global.crypto.getRandomValues(buf);
        return buf;
      };
    } else if (typeof require === "function") {
      const crypto = require("crypto");
      return (buf) => {
        const bytes = crypto.randomBytes(buf.length);
        buf.set(bytes)
        return buf;
      };
    } else {
      // FALLBACK
      console.warn("No SRNG available, switching back to Math.random.");

      return (buf) => {
        for (let i = 0, r; i < buf.length; i++) {
          if ((i & 0x03) === 0) { r = Math.random() * 0x100000000; }
          buf[i] = r >>> ((i & 0x03) << 3) & 0xff;
        }
        return buf;
      };
    }
  })();

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

  self.v4 = (function () {
    const buff8 = new Uint8Array(16);

    return function v4() {
      fill(buff8);
      buff8[6] = (buff8[6] & 0x0f) | 0x40;
      buff8[8] = (buff8[8] & 0x3f) | 0x80;
      return core.uuid(toHexString(buff8));
    };
  })();

  self.v8 = (function () {
    const buff  = new ArrayBuffer(16);
    const buff8 = new Uint8Array(buff);
    const view  = new DataView(buff);

    const timeRef = 1640991600 * 1000; // ms since 2022-01-01T00:00:00
    const maxClockSeq = 16384n; // 14 bits space

    let clockSeq = 0n;
    let lastTs = 0n;
    let baseMsb;
    let baseLsb;

    function initializeSeed() {
      fill(buff8);
      baseMsb = 0x0000_0000_0000_8000n; // Version 8;
      baseLsb = view.getBigUint64(8, false) & 0x0fff_ffff_ffff_ffffn | 0x8000_0000_0000_0000n; // Variant 2;
    }

    function currentTimestamp() {
      return BigInt.asUintN(64, "" + (Date.now() - timeRef));
    }

    initializeSeed();

    const create = function create(ts, clockSeq) {
      let msb = (baseMsb
                 | ((ts << 16n) & 0xffff_ffff_ffff_0000n)
                 | ((clockSeq >> 2n) & 0x0000_0000_0000_0fffn));
      let lsb = baseLsb | ((clockSeq << 60n) & 0x3000_0000_0000_0000n);
      view.setBigUint64(0, msb, false);
      view.setBigUint64(8, lsb, false);
      return core.uuid(toHexString(buff8));
    }

    const factory = function v8() {
      while (true) {
        let ts = currentTimestamp();

        // Protect from clock regression
        if ((ts-lastTs) < 0) {
          initializeSeed();
          clockSeq = 0;
          continue;
        }

        if (lastTs === ts) {
          if (clockSeq < maxClockSeq) {
            clockSeq++;
          } else {
            continue;
          }
        } else {
          clockSeq = 0n;
          lastTs = ts;
        }

        return create(ts, clockSeq);
      }
    };

    factory.create = create
    factory.initialize = initializeSeed;
    return factory;
  })();

  self.custom = function formatAsUUID(mostSigBits, leastSigBits) {
    const most = mostSigBits.toString("16").padStart(16, "0");
    const least = leastSigBits.toString("16").padStart(16, "0");
    return `${most.substring(0, 8)}-${most.substring(8, 12)}-${most.substring(12)}-${least.substring(0, 4)}-${least.substring(4)}`;
  }
});
