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
goog.require("goog.object");

goog.scope(function() {
  const self = uxbox.util.rng_impl;
  const platform = cljs.core._STAR_target_STAR_;

  if (platform === "nodejs") {
    const crypto = require("crypto");

    self.getBytes = function(n) {
      return crypto.randomBytes(n);
    };

  } else {
    const gobj = goog.object;
    const global = goog.global;
    const crypto = gobj.get(global, "crypto");

    if (crypto === undefined) {
      console.warn("No high quality RNG available, switching back to Math.random.", platform);

      self.getBytes = function(n) {
        const buf = new Uint8Array(n);

        for (let i = 0, r; i < n; i++) {
          if ((i & 0x03) === 0) { r = Math.random() * 0x100000000; }
          buf[i] = r >>> ((i & 0x03) << 3) & 0xff;
        }

        return buf;
      };
    } else {
      self.getBytes = function(n) {
        const buf = new Uint8Array(n);
        crypto.getRandomValues(buf);
        return buf;
      };
    }
  }
});
