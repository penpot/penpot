/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 *
 * Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>
*/

// NOTE: this code is unused, but is preserved for the case when we
// note that the cljs impl has not not enough performance.

goog.provide("uxbox.util.geom.matrix_impl");
goog.provide("uxbox.util.geom.matrix_impl.Matrix");
goog.require("goog.math");
goog.require("uxbox.util.geom.point_impl");

goog.scope(function() {
  const self = uxbox.util.geom.matrix_impl;
  const gpt = uxbox.util.geom.point_impl;
  const math = goog.math;

  /**
   * @param {number} a
   * @param {number} b
   * @param {number} c
   * @param {number} d
   * @param {number} e
   * @param {number} f
   * @struct
   */
  class Matrix {
    constructor(a, b, c, d, e, f) {
      this.a = a;
      this.b = b;
      this.c = c;
      this.d = d;
      this.e = e;
      this.f = f;
    }

    [Symbol.iterator]() {
      return [["a", this.a]
              ["b", this.b]];
    }

    toString() {
      return `matrix(${this.a},${this.b},${this.c},${this.d},${this.e},${this.f})`;
    }

    copy() {
      return new Matrix(
        this.a,
        this.b,
        this.c,
        this.d,
        this.e,
        this.f
      );
    }
  }

  self.Matrix = Matrix;

  /**
   * @param {number?} a
   * @param {number?} b
   * @param {number?} c
   * @param {number?} d
   * @param {number?} e
   * @param {number?} f
   * @return {Matrix}
   */
  self.matrix = function(a, b, c, d, e, f) {
    if (a === undefined) {
      return new Matrix(1,0,0,1,0,0);
    } else {
      return new Matrix(a,b,c,d,e,f);
    }
  };

  /**
   * @param {?} m
   * @return {boolean}
   */
  function isMatrix(m) {
    return m instanceof Matrix;
  }

  self.isMatrix = isMatrix


  /**
   * @param {Matrix} m1
   * @param {Matrix} m2
   * @return {Matrix}
   */
  self.multiplyUnsafe = function(m1, m2) {
    const a = m1.a * m2.a + m1.c * m2.b;
    const b = m1.b * m2.a + m1.d * m2.b;
    const c = m1.a * m2.c + m1.c * m2.d;
    const d = m1.b * m2.c + m1.d * m2.d;
    const e = m1.a * m2.e + m1.c * m2.f + m1.e;
    const f = m1.b * m2.e + m1.d * m2.f + m1.f;
    m1.a = a;
    m1.b = b;
    m1.c = c;
    m1.d = d;
    m1.e = e;
    m1.f = f;
    return m1;
  }

  /**
   * @param {Matrix} m1
   * @param {Matrix} m2
   * @return {Matrix}
   */
  self.multiply = function(m1, m2) {
    m1 = m1.copy();
    return self.multiplyUnsafe(m1, m2);
  }

  /**
   * @param {...Matrix} matrices
   * @return {Matrix}
   */
  self.compose = function(...matrices) {
    switch (matrices.length) {
    case 0:
      throw new Error('no matrices provided')

    case 1:
      return matrices[0]

    case 2:
      return self.multiply(matrices[0], matrices[1])

    default: {
      let result = matrices[0].copy();
      for (let i=1; i<matrices.length; i++) {
        result = self.multiplyUnsafe(result, matrices[i]);
      }
      return result;
    }
    }
  };

  /**
   * @param {gpt.Point} p
   * @return {Matrix}
   */
  self.translateMatrix = function(p) {
    return new Matrix(1, 0, 0, 1, p.x, p.y);
  };

  /**
   * @param {gpt.Point} p
   * @return {Matrix}
   */
  self.scaleMatrix = function(p) {
    return new Matrix(p.x, 0, 0, p.y, 0, 0);
  };

  /**
   * @param {number} angle
   * @return {Matrix}
   */
  self.rotateMatrix = function(angle) {
    const r = math.toRadiants(angle);
    return new Matrix(
      Math.cos(r),
      Math.sin(r),
      -Math.sin(r),
      Math.cos(r),
      0,
      0
    );
  };

  /**
   * @param {Matrix} m
   * @param {gpt.Point} p
   * @return {Matrix}
   */
  self.translate = function(m, p) {
    return self.multiply(m, self.translateMatrix(p));
  }

  /**
   * @param {Matrix} m
   * @param {angle} angle
   * @param {gpt.Point?} center
   * @return {Matrix}
   */
  self.rotate = function(m, angle, center) {
    if (center === undefined) {
      return self.multiply(m, self.rotateMatrix(angle));
    } else {
      return self.compose(
        m,
        self.translateMatrix(center),
        self.rotateMatrix(angle),
        self.translateMatrix(gpt.negate(center))
      );
    }
  };

  /**
   * @param {Matrix} m
   * @param {gpt.Point} scale
   * @param {gpt.Point?} center
   * @return {Matrix}
   */
  self.scale = function(m, scale, center) {
    if (center === undefined) {
      return self.multiply(m, self.scaleMatrix(scale));
    } else {
      return self.compose(
        m,
        self.translateMatrix(center),
        self.scaleMatrix(scale),
        self.translateMatrix(gpt.negate(center))
      );
    }
  };
});
