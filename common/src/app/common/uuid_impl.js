/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */
"use strict";

goog.require("app.common.encoding_impl");
goog.provide("app.common.uuid_impl");

goog.scope(function() {
  const global = goog.global;
  const encoding  = app.common.encoding_impl;
  const self = app.common.uuid_impl;

  const timeRef = 1640995200000; // ms since 2022-01-01T00:00:00

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

  function toHexString(buf) {
    const hexMap = encoding.hexMap;
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
  };

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

  function currentTimestamp(timeRef) {
    return BigInt.asUintN(64, "" + (Date.now() - timeRef));
  }

  const tmpBuff = new ArrayBuffer(8);
  const tmpView = new DataView(tmpBuff);
  const tmpInt8 = new Uint8Array(tmpBuff);

  function nextLong() {
    fill(tmpInt8);
    return getBigUint64(tmpView, 0, false);
  }

  self.shortID = (function () {
    const buff  = new ArrayBuffer(8);
    const int8 = new Uint8Array(buff);
    const view  = new DataView(buff);

    const base = 0x0000_0000_0000_0000n;

    return function shortID(ts) {
      const tss = currentTimestamp(timeRef);
      const msb = (base
                   | (nextLong() & 0xffff_ffff_0000_0000n)
                   | (tss & 0x0000_0000_ffff_ffffn));
      setBigUint64(view, 0, msb, false);
      return encoding.toBase62(int8);
    };
  })();

  self.v4 = (function () {
    const arr = new Uint8Array(16);

    return function v4() {
      fill(arr);
      arr[6] = (arr[6] & 0x0f) | 0x40;
      arr[8] = (arr[8] & 0x3f) | 0x80;
      return encoding.bufferToHex(arr, true);
    };
  })();

  self.v8 = (function () {
    const buff = new ArrayBuffer(16);
    const int8 = new Uint8Array(buff);
    const view = new DataView(buff);

    const maxCs = 0x0000_0000_0000_3fffn; // 14 bits space

    let countCs = 0n;
    let lastRd  = 0n;
    let lastCs  = 0n;
    let lastTs  = 0n;
    let baseMsb = 0x0000_0000_0000_8000n;
    let baseLsb = 0x8000_0000_0000_0000n;

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

      return encoding.bufferToHex(int8, true);
    };

    const factory = function v8() {
      while (true) {
        let ts = currentTimestamp(timeRef);

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

    const fromArray = (u8data) => {
      int8.set(u8data);
      return encoding.bufferToHex(int8, true);
    }

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
    factory.fromArray = fromArray;
    return factory;
  })();

  self.shortV8 = function(uuid) {
    const buff = encoding.hexToBuffer(uuid);
    const short =  new Uint8Array(buff, 4);
    return encoding.bufferToBase62(short);
  };

  self.custom = function formatAsUUID(mostSigBits, leastSigBits) {
    const most = mostSigBits.toString("16").padStart(16, "0");
    const least = leastSigBits.toString("16").padStart(16, "0");
    return `${most.substring(0, 8)}-${most.substring(8, 12)}-${most.substring(12)}-${least.substring(0, 4)}-${least.substring(4)}`;
  };

  self.fromBytes = function(data) {
    if (data instanceof Uint8Array) {
      return self.v8.fromArray(data);
    } else if (data instanceof Int8Array) {
      data = Uint8Array.from(data);
      return self.v8.fromArray(data);
    } else {
      let buffer = data?.buffer;
      if (buffer instanceof ArrayBuffer) {
        data = new Uint8Array(buffer);
        return self.v8.fromArray(data);
      } else {
        throw new Error("invalid array type received");
      }
    }
  };

  // Code based from uuidjs/parse.ts
  self.getBytes = function parse(uuid) {
    const buffer = new ArrayBuffer(16);
    const view = new Int8Array(buffer);
    let rest;

    // Parse ########-....-....-....-............
    view[0] = (rest = parseInt(uuid.slice(0, 8), 16)) >>> 24;
    view[1] = (rest >>> 16) & 0xff;
    view[2] = (rest >>> 8) & 0xff;
    view[3] = rest & 0xff;

      // Parse ........-####-....-....-............
    view[4] = (rest = parseInt(uuid.slice(9, 13), 16)) >>> 8;
    view[5] = rest & 0xff;

    // Parse ........-....-####-....-............
    view[6] = (rest = parseInt(uuid.slice(14, 18), 16)) >>> 8;
    view[7] = rest & 0xff;

    // Parse ........-....-....-####-............
    view[8] = (rest = parseInt(uuid.slice(19, 23), 16)) >>> 8;
    view[9] = rest & 0xff,

    // Parse ........-....-....-....-############
    // (Use "/" to avoid 32-bit truncation when bit-shifting high-order bytes)
    view[10] = ((rest = parseInt(uuid.slice(24, 36), 16)) / 0x10000000000) & 0xff;
    view[11] = (rest / 0x100000000) & 0xff;
    view[12] = (rest >>> 24) & 0xff;
    view[13] = (rest >>> 16) & 0xff;
    view[14] = (rest >>> 8) & 0xff;
    view[15] = rest & 0xff;

    return view;
  }

  self.getUnsignedInt32Array = function (uuid) {
    const bytes = self.getBytes(uuid);
    return new Uint32Array(bytes.buffer);
  }
});
