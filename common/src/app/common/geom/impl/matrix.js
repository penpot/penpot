/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */
"use strict";

goog.require("cljs.core");
goog.provide("app.common.geom.impl.matrix");

goog.scope(function() {
  const self = app.common.geom.impl.matrix;

  self.create = function(a, b, c, d, e, f) {
    const matrix = new Float64Array(6);
    matrix[0] = a;
    matrix[1] = b;
    matrix[2] = c;
    matrix[3] = d;
    matrix[4] = e;
    matrix[5] = f;

    return matrix;
  }


  // result[0] = (m1[0] * m2[0]) + (m1[2] * m2[1]);
  // result[1] = (m1[1] * m2[0]) + (m1[3] * m2[1]);
  // result[2] = (m1[0] * m2[2]) + (m1[2] * m2[3]);
  // result[3] = (m1[1] * m2[2]) + (m1[3] * m2[3]);
  // result[4] = (m1[0] * m2[4]) + (m1[2] * m2[5]) + m1[4];
  // result[5] = (m1[1] * m2[4]) + (m1[3] * m2[5]) + m1[5];


  self.multiply = function(m1, m2) {
    // var result = new Float64Array(6);

    // var m1a = m1[0];
    // var m1b = m1[1];
    // var m1c = m1[2];
    // var m1d = m1[3];
    // var m1e = m1[4];
    // var m1f = m1[5];

    // var m2a = m2[0];
    // var m2b = m2[1];
    // var m2c = m2[2];
    // var m2d = m2[3];
    // var m2e = m2[4];
    // var m2f = m2[5];

    // result[0] = (m1a * m2a) + (m1c * m2b);
    // result[1] = (m1b * m2a) + (m1d * m2b);
    // result[2] = (m1a * m2c) + (m1c * m2d);
    // result[3] = (m1b * m2c) + (m1d * m2d);
    // result[4] = (m1a * m2e) + (m1c * m2f) + m1e;
    // result[5] = (m1b * m2e) + (m1d * m2f) + m1f;

    // return result;
    return m1;
  }

});
