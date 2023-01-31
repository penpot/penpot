/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
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
      const randomBytes = crypto["randomBytes"];

      return (buf) => {
        const bytes = randomBytes(buf.length);
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

  function getBigUint64(view, byteOffset, le) {
    const a = view.getUint32(byteOffset, le);
    const b = view.getUint32(byteOffset + 4, le);
    const leMask = Number(!!le);
    const beMask = Number(!le);
    return ((BigInt(a * beMask + b * leMask) << 32n) |
            (BigInt(a * leMask + b * beMask)));
  }

  function setBigUint64(view, byteOffset, value, le) {
    const hi = Number(value >> 32n);
    const lo = Number(value & 0xffffffffn);
    if (le) {
      view.setUint32(byteOffset + 4, hi, le);
      view.setUint32(byteOffset, lo, le);
    }
    else {
      view.setUint32(byteOffset, hi, le);
      view.setUint32(byteOffset + 4, lo, le);
    }
  }

  self.v8 = (function () {
    const buff  = new ArrayBuffer(16);
    const int8 = new Uint8Array(buff);
    const view  = new DataView(buff);

    const tmpBuff = new ArrayBuffer(8);
    const tmpView = new DataView(tmpBuff);
    const tmpInt8 = new Uint8Array(tmpBuff);

    const timeRef = 1640995200000; // ms since 2022-01-01T00:00:00
    const maxCs   = 0x0000_0000_0000_3fffn; // 14 bits space

    let countCs = 0n;
    let lastRd  = 0n;
    let lastCs  = 0n;
    let lastTs  = 0n;
    let baseMsb = 0x0000_0000_0000_8000n;
    let baseLsb = 0x8000_0000_0000_0000n;

    const currentTimestamp = () => {
      return BigInt.asUintN(64, "" + (Date.now() - timeRef));
    };

    const nextLong = () => {
      fill(tmpInt8);
      return getBigUint64(tmpView, 0, false);
    };

    lastRd = nextLong() & 0xffff_ffff_ffff_f0ffn;
    lastCs = nextLong() & maxCs;

    const create = function create(ts, lastRd, lastCs) {
      const msb = (baseMsb
                   | (lastRd & 0xffff_ffff_ffff_0fffn));

      const lsb = (baseLsb
                   | ((ts << 14n) & 0x3fff_ffff_ffff_c000n)
                   | lastCs);

      setBigUint64(view, 0, msb, false);
      setBigUint64(view, 8, lsb, false);

      return core.uuid(toHexString(int8));
    };

    const factory = function v8() {
      while (true) {
        let ts = currentTimestamp();

        // Protect from clock regression
        if ((ts - lastTs) < 0) {
          lastRd = (lastRd
                    & 0x0000_0000_0000_0f00n
                    | (nextLong() & 0xffff_ffff_ffff_f0ffn));
          countCs = 0n;
          continue;
        }

        if (lastTs === ts) {
          if (countCs < maxCs) {
            lastCs = (lastCs + 1n) & maxCs;
            countCs++;
          } else {
            continue;
          }
        } else {
          lastTs = ts;
          lastCs = nextLong() & maxCs;
          countCs = 0;
        }

        return create(ts, lastRd, lastCs);
      }
    };

    const setTag = (tag) => {
      tag = BigInt.asUintN(64, "" + tag);
      if (tag > 0x0000_0000_0000_000fn) {
        throw new Error("illegal arguments: tag value should fit in 4bits");
      }

      lastRd = (lastRd
                & 0xffff_ffff_ffff_f0ffn
                | ((tag << 8) & 0x0000_0000_0000_0f00n));
    };

    factory.create = create;
    factory.setTag = setTag;
    return factory;
  })();


  self.custom = function formatAsUUID(mostSigBits, leastSigBits) {
    const most = mostSigBits.toString("16").padStart(16, "0");
    const least = leastSigBits.toString("16").padStart(16, "0");
    return `${most.substring(0, 8)}-${most.substring(8, 12)}-${most.substring(12)}-${least.substring(0, 4)}-${least.substring(4)}`;
  }
});
