/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
*/

"use strict";

goog.provide("uuid.rng");

goog.scope(function() {
  uuid.rng.getBytes = null;

  // Check if nodejs rng is available (high quality);
  if (goog.global.require !== undefined) {
    const crypto = goog.global.require("crypto");

    uuid.rng.getBytes = function(n) {
      return crypto.randomBytes(n);
    };
  }
  // Check if whatwg rng is available (high quality);
  else if (goog.global.crypto.getRandomValues !== undefined) {
    uuid.rng.getBytes = function(n) {
      const buf = new Uint8Array(16);
      goog.global.crypto.getRandomValues(buf);
      return buf;
    };
  }
  // Switch Back to the Math.random (low quality);
  else {
    console.warn("No high quality RNG available, switching back to Math.random.");
    uuid.rng.getBytes = function(n) {
      const buf = new Array(16);
      for (let i = 0, r; i < 16; i++) {
        if ((i & 0x03) === 0) { r = Math.random() * 0x100000000; }
        buf[i] = r >>> ((i & 0x03) << 3) & 0xff;
      }
      return buf;
    };
  }
});
