/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
*/
"use strict";

goog.provide("uxbox.util.uuid.impl");
goog.require("uxbox.util.uuid.rng");

goog.scope(function() {
  const impl = uxbox.util.uuid.impl;
  const rng = uxbox.util.uuid.rng;
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

  impl.v4 = function() {
    const buf = rng.getBytes(16);
    buf[6] = (buf[6] & 0x0f) | 0x40;
    buf[8] = (buf[8] & 0x3f) | 0x80;
    return toHexString(buf);
  };
})
