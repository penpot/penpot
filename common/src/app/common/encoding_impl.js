/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */
"use strict";

goog.require("cljs.core");
goog.provide("app.common.encoding_impl");

goog.scope(function() {
  const core = cljs.core;
  const global = goog.global;
  const self = app.common.encoding_impl;

  const hexMap = [];
  for (let i = 0; i < 256; i++) {
    hexMap[i] = (i + 0x100).toString(16).substr(1);
  }

  function hexToBuffer(input) {
    if (typeof input !== "string") {
      throw new TypeError("Expected input to be a string");
    }

    // Accept UUID hex format
    input = input.replace(/-/g, "");

    if ((input.length % 2) !== 0) {
      throw new RangeError("Expected string to be an even number of characters")
    }

    const view = new Uint8Array(input.length / 2);

    for (let i = 0; i < input.length; i += 2) {
      view[i / 2] = parseInt(input.substring(i, i + 2), 16);
    }

    return view.buffer;
  }

  function bufferToHex(source, isUuid) {
    if (source instanceof Uint8Array) {
    } else if (ArrayBuffer.isView(source)) {
      source = new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
    } else if (Array.isArray(source)) {
      source = Uint8Array.from(source);
    }

    if (source.length != 16) {
      throw new RangeError("only 16 bytes array is allowed");
    }

    const spacer = isUuid ? "-" : "";

    let i = 0;
    return  (hexMap[source[i++]] +
             hexMap[source[i++]] +
             hexMap[source[i++]] +
             hexMap[source[i++]] + spacer +
             hexMap[source[i++]] +
             hexMap[source[i++]] + spacer +
             hexMap[source[i++]] +
             hexMap[source[i++]] + spacer +
             hexMap[source[i++]] +
             hexMap[source[i++]] + spacer +
             hexMap[source[i++]] +
             hexMap[source[i++]] +
             hexMap[source[i++]] +
             hexMap[source[i++]] +
             hexMap[source[i++]] +
             hexMap[source[i++]]);
  }

  self.hexToBuffer = hexToBuffer;
  self.bufferToHex = bufferToHex;

  // base-x encoding / decoding
  // Copyright (c) 2018 base-x contributors
  // Copyright (c) 2014-2018 The Bitcoin Core developers (base58.cpp)
  // Distributed under the MIT software license, see the accompanying
  // file LICENSE or http://www.opensource.org/licenses/mit-license.php.

  // WARNING: This module is NOT RFC3548 compliant, it cannot be used
  // for base16 (hex), base32, or base64 encoding in a standards
  // compliant manner.

  function getBaseCodec (ALPHABET) {
    if (ALPHABET.length >= 255) { throw new TypeError("Alphabet too long"); }
    let BASE_MAP = new Uint8Array(256);
    for (let j = 0; j < BASE_MAP.length; j++) {
      BASE_MAP[j] = 255;
    }
    for (let i = 0; i < ALPHABET.length; i++) {
      let x = ALPHABET.charAt(i);
      let xc = x.charCodeAt(0);
      if (BASE_MAP[xc] !== 255) { throw new TypeError(x + " is ambiguous"); }
      BASE_MAP[xc] = i;
    }
    let BASE = ALPHABET.length;
    let LEADER = ALPHABET.charAt(0);
    let FACTOR = Math.log(BASE) / Math.log(256); // log(BASE) / log(256), rounded up
    let iFACTOR = Math.log(256) / Math.log(BASE); // log(256) / log(BASE), rounded up
    function encode (source) {
      if (source instanceof Uint8Array) {
      } else if (ArrayBuffer.isView(source)) {
        source = new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
      } else if (Array.isArray(source)) {
        source = Uint8Array.from(source);
      }
      if (!(source instanceof Uint8Array)) { throw new TypeError("Expected Uint8Array"); }
      if (source.length === 0) { return ""; }
      // Skip & count leading zeroes.
      let zeroes = 0;
      let length = 0;
      let pbegin = 0;
      let pend = source.length;
      while (pbegin !== pend && source[pbegin] === 0) {
        pbegin++;
        zeroes++;
      }
      // Allocate enough space in big-endian base58 representation.
      let size = ((pend - pbegin) * iFACTOR + 1) >>> 0;
      let b58 = new Uint8Array(size);
      // Process the bytes.
      while (pbegin !== pend) {
        let carry = source[pbegin];
        // Apply "b58 = b58 * 256 + ch".
        let i = 0;
        for (let it1 = size - 1; (carry !== 0 || i < length) && (it1 !== -1); it1--, i++) {
          carry += (256 * b58[it1]) >>> 0;
          b58[it1] = (carry % BASE) >>> 0;
          carry = (carry / BASE) >>> 0;
        }
        if (carry !== 0) { throw new Error("Non-zero carry"); }
        length = i;
        pbegin++;
      }
      // Skip leading zeroes in base58 result.
      let it2 = size - length;
      while (it2 !== size && b58[it2] === 0) {
        it2++;
      }
      // Translate the result into a string.
      let str = LEADER.repeat(zeroes);
      for (; it2 < size; ++it2) { str += ALPHABET.charAt(b58[it2]); }
      return str;
    }

    function decodeUnsafe (source) {
      if (typeof source !== "string") { throw new TypeError("Expected String"); }
      if (source.length === 0) { return new Uint8Array(); }
      let psz = 0;
      // Skip and count leading '1's.
      let zeroes = 0;
      let length = 0;
      while (source[psz] === LEADER) {
        zeroes++;
        psz++;
      }
      // Allocate enough space in big-endian base256 representation.
      let size = (((source.length - psz) * FACTOR) + 1) >>> 0; // log(58) / log(256), rounded up.
      let b256 = new Uint8Array(size);
      // Process the characters.
      while (source[psz]) {
        // Decode character
        let carry = BASE_MAP[source.charCodeAt(psz)];
        // Invalid character
        if (carry === 255) { return; }
        let i = 0;
        for (let it3 = size - 1; (carry !== 0 || i < length) && (it3 !== -1); it3--, i++) {
          carry += (BASE * b256[it3]) >>> 0;
          b256[it3] = (carry % 256) >>> 0;
          carry = (carry / 256) >>> 0;
        }
        if (carry !== 0) { throw new Error("Non-zero carry"); }
        length = i;
        psz++;
      }
      // Skip leading zeroes in b256.
      let it4 = size - length;
      while (it4 !== size && b256[it4] === 0) {
        it4++;
      }
      let vch = new Uint8Array(zeroes + (size - it4));
      let j = zeroes;
      while (it4 !== size) {
        vch[j++] = b256[it4++];
      }
      return vch;
    }

    function decode (string) {
      let buffer = decodeUnsafe(string);
      if (buffer) { return buffer; }
      throw new Error("Non-base" + BASE + " character");
    }

    return {
      encode: encode,
      decodeUnsafe: decodeUnsafe,
      decode: decode
    };
  }
  // MORE bases here: https://github.com/cryptocoinjs/base-x/tree/master
  const BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  self.bufferToBase62 = getBaseCodec(BASE62).encode;

});
