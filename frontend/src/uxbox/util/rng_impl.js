/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
*/

"use strict";

goog.provide("uxbox.util.rng_impl");
goog.require("cljs.core");

goog.scope(function() {
  const self = uxbox.util.rng_impl;
  const global = goog.global;

  // Check if nodejs rng is available (high quality);
  if (cljs.core._STAR_target_STAR_ === "nodejs") {
    const crypto = require("crypto");

    self.fill = function(buf) {
      crypto.randomFillSync(buf);
      return buf;
    };

    self.getBytes = function(n) {
      return crypto.randomBytes(n);
    };
  }

  // Check if whatwg rng is available (high quality);
  else if (global.crypto !== undefined &&
           global.crypto.getRandomValues !== undefined) {

    self.fill = function(buf) {
      global.crypto.getRandomValues(buf);
      return buf;
    };

    self.getBytes = function(n) {
      const buf = new Uint8Array(16);
      global.crypto.getRandomValues(buf);
      return buf;
    };
  }

  // Switch Back to the Math.random (low quality);
  else {
    console.warn("No high quality RNG available, switching back to Math.random.");

    self.fill = function(buf) {
      for (let i = 0, r; i < buf.length; i++) {
        if ((i & 0x03) === 0) { r = Math.random() * 0x100000000; }
        buf[i] = r >>> ((i & 0x03) << 3) & 0xff;
      }

      return buf;
    };

    self.getBytes = function(n) {
      const buf = new Array(n);
      for (let i = 0, r; i < n; i++) {
        if ((i & 0x03) === 0) { r = Math.random() * 0x100000000; }
        buf[i] = r >>> ((i & 0x03) << 3) & 0xff;
      }
      return buf;
    };
  }
});
