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

goog.provide("uxbox.util.geom.point_impl");
goog.provide("uxbox.util.geom.point_impl.Point");
goog.require("goog.math");

goog.scope(function() {
  const self = uxbox.util.geom.point_impl;
  const math = goog.math;

  /**
   * @param {number} x
   * @param {number} y
   * @struct
   */
  class Point {
    constructor(x, y) {
      this.x = x;
      this.y = y;
    }

    toString() {
      return "point(" + this.x + ", " + this.y + ")";
    }
  }

  self.Point = Point;

  self.point = function(x, y) {
    let xv = null;
    let yv = null;

    if (x === undefined) {
      return new Point(0, 0);
    } else {
      xv = x;
    }

    if (y === undefined) {
        yv = x;
    } else {
      yv = y;
    }

    return new Point(xv, yv);
  };

  function isPoint(p) {
    return p instanceof Point;
  }

  self.isPoint = isPoint;

  /**
   * @param {Point} p
   * @param {number} angle
   * @return {Point}
   */
  self.rotate = function(p, angle) {
    const r = math.toRadians(angle);
    const sin = Math.sin(r);
    const cos = Math.cos(r);

    const x = p.x;
    const y = p.y;

    const point = new Point(
      x * cos - y * sin,
      x * sin + y * cos
    );

    return self.roundTo(point, 6)
  };

  /**
   * @param {Point} p
   * @param {Point} other
   * @return {Point}
   */
  self.add = function(p, other) {
    return new Point(
      p.x + other.x,
      p.y + other.y
    );
  };

  /**
   * @param {Point} p
   * @param {Point} other
   * @return {Point}
   */
  self.subtract = function(p, other) {
    return new Point(
      p.x - other.x,
      p.y - other.y
    );
  };

  /**
   * @param {Point} p
   * @param {Point} other
   * @return {Point}
   */
  self.multiply = function(p, other) {
    return new Point(
      p.x * other.x,
      p.y * other.y
    );
  };

  /**
   * @param {Point} p
   * @param {Point} other
   * @return {Point}
   */
  self.divide = function(p, other) {
    return new Point(
      p.x / other.x,
      p.y / other.y
    );
  };

  /**
   * @param {Point} p
   * @return {Point}
   */
  self.negate = function(p) {
    const x = p.x, y = p.y;
    return new Point(
      x === 0 ? x : x * -1,
      y === 0 ? y : y * -1
    );
  };

  /**
   * @param {Point} p
   * @param {Point} other
   * @return {number}
   */
  self.distance = function(p, other) {
    const dx = p.x - other.x;
    const dy = p.y - other.y;
    return Math.sqrt(Math.pow(dx, 2),
                     Math.pow(dy, 2));
  };

  /**
   * @param {Point} p
   * @param {Point} center
   * @return {number}
   */
  self.angle = function(p, center) {
    if (center !== undefined) {
      p = self.subtract(p, center);
    }

    return math.toDegrees(Math.atan2(p.y, p.x));
  };

  /**
   * @param {Point} p
   * @param {Point} other
   * @return {number}
   */
  self.length = function(p) {
    return Math.sqrt(Math.pow(p.x, 2) + Math.pow(p.y, 2));
  };

  /**
   * @param {Point} p
   * @return {number}
   */
  self.angle2other = function(p, other) {
    let angle = ((p.x * other.x) + (p.y * other.y)) / (self.length(p) * self.length(other));

    if (angle < -1) {
      angle =  -1;
    } else if (angle > 1) {
      angle = 1;
    }

    angle = Math.acos(angle);
    angle = math.toDegrees(angle);
    return parseFloat(angle.toFixed(6));
  };

  /**
   * @param {Point} p
   * @param {number} angle
   * @return {Point}
   */
  self.updateAngle = function(p, angle) {
    const len = self.length(p);
    const r = math.toRadiants(angle);

    return new Point(
      Math.cos(r) * len,
      Math.sin(r) * len
    );
  };

  /**
   * @param {Point} p
   * @param {number} decimals
   * @return {Point}
   */
  self.roundTo = function(p, decimals) {
    return new Point(
      parseFloat(p.x.toFixed(decimals)),
      parseFloat(p.y.toFixed(decimals))
    );
  };

  // class Matrix {
  //   constructor() {
  //     this.a = 1;
  //     this.b = 0;
  //     this.c = 0;
  //     this.d = 1;
  //     this.e = 0;
  //     this.f = 0;
  //   }
  // }

  // self = uxbox.util.geom.matrix_impl;
  // self.Matrix = Matrix;
  // self.sayHello = function() {
  //   console.log("hello");
  // }
});
