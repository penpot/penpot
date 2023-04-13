import Point from './Point'

/**
 * Matrix 2x3
 *
 * a c e
 * b d f
 */
@unmanaged
export default class Matrix {
  a: f32 = 1
  b: f32 = 0
  c: f32 = 0
  d: f32 = 1
  e: f32 = 0 // tx
  f: f32 = 0 // ty

  static multiply(out: Matrix, a: Matrix, b: Matrix): Matrix
  {
    return out.set(
      a.a * b.a + a.c * b.b,
      a.b * b.a + a.d * b.b,
      a.a * b.c + a.c * b.d,
      a.b * b.c + a.d * b.d,
      a.a * b.e + a.c * b.f + a.e,
      a.b * b.e + a.d * b.f + a.f
    )
  }

  static transform(out: Point, p: Point, m: Matrix): Point {
    return out.set(
      p.x * m.a + p.y * m.c + m.e,
      p.x * m.b + p.y * m.d + m.f
    )
  }

  static fromTranslation(point: Point): Matrix {
    return new Matrix(
      1, 0, 0, 1, point.x, point.y
    )
  }

  constructor(
    a: f32 = 1,
    b: f32 = 0,
    c: f32 = 0,
    d: f32 = 1,
    e: f32 = 0,
    f: f32 = 0
  ) {
    this.a = a
    this.b = b
    this.c = c
    this.d = d
    this.e = e
    this.f = f
  }

  @inline
  set(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32): Matrix
  {
    this.a = a
    this.b = b
    this.c = c
    this.d = d
    this.e = e
    this.f = f
    return this
  }

  setTranslation(point: Point): Matrix {
    this.e = point.x
    this.f = point.y
    return this
  }

  setNegatedTranslation(point: Point): Matrix {
    this.e = -point.x
    this.f = -point.y
    return this
  }

  identity(): Matrix {
    return this.set(1, 0, 0, 1, 0, 0)
  }

  copy(other: Matrix): Matrix {
    return this.set(
      other.a,
      other.b,
      other.c,
      other.d,
      other.e,
      other.f
    )
  }

  clone(): Matrix {
    return new Matrix(
      this.a,
      this.b,
      this.c,
      this.d,
      this.e,
      this.f
    )
  }

  transform(point: Point, transformed: Point = new Point()): Point {
    return transformed.set(
      point.x * this.a + point.y * this.c + this.e,
      point.x * this.b + point.y * this.d + this.f
    )
  }

  determinant(): f32 {
    return this.a * this.d - this.b * this.c
  }

  invert(): Matrix|null {
    const det: f32 = this.determinant()
    if (!det) {
      return null
    }
    const idet: f32 = 1.0 / det;
    return this.set(
      this.d * idet,
      -this.b * idet,
      -this.c * idet,
      this.a * idet,
      (this.c * this.f - this.d * this.e) * idet,
      (this.b * this.e - this.a * this.f) * idet
    )
  }

  multiply(other: Matrix): Matrix
  {
    return this.set(
      this.a * other.a + this.c * other.b,
      this.b * other.a + this.d * other.b,
      this.a * other.c + this.c * other.d,
      this.b * other.c + this.d * other.d,
      this.a * other.e + this.c * other.f + this.e,
      this.b * other.e + this.d * other.f + this.f
    )
  }

  rotate(angle: f32): Matrix
  {
    const c = Mathf.cos(angle)
    const s = Mathf.sin(angle)
    return this.set(
      this.a * c + this.c * s,
      this.b * c + this.d * s,
      this.a * -s + this.c * c,
      this.b * -s + this.d * c,
      this.e,
      this.f
    )
  }

  scale(scale: Point): Matrix
  {
    return this.set(
      this.a * scale.x,
      this.b * scale.x,
      this.c * scale.y,
      this.d * scale.y,
      this.e,
      this.f
    )
  }

  translate(translation: Point): Matrix
  {
    return this.set(
      this.a,
      this.b,
      this.c,
      this.d,
      this.a * translation.x + this.c * translation.y + this.e,
      this.b * translation.x + this.d * translation.y + this.f
    )
  }

  toString(): string
  {
    return `Matrix(${this.a}, ${this.b}, ${this.c}, ${this.d}, ${this.e}, ${this.f})`
  }
}
