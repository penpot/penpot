export class Point {
  static create(x = 0, y = 0) {
    return new Point(x, y);
  }

  constructor(x = 0, y = 0) {
    this.x = x;
    this.y = y;
  }

  get length() {
    return Math.hypot(this.x, this.y);
  }

  get angle() {
    return Math.atan2(this.y, this.x);
  }

  set(x, y) {
    this.x = x;
    this.y = y;
    return this;
  }

  add({ x, y }) {
    return this.set(this.x + x, this.y + y);
  }

  subtract({ x, y }) {
    return this.set(this.x - x, this.y - y);
  }

  multiply({ x, y }) {
    return this.set(this.x * x, this.y * y);
  }

  divide({ x, y }) {
    return this.set(this.x / x, this.y / y);
  }

  scale(sx, sy = sx) {
    return this.set(this.x * sx, this.y * sy);
  }

  rotate(angle) {
    const c = Math.cos(angle);
    const s = Math.sin(angle);
    return this.set(c * this.x - s * this.y, s * this.x + c * this.y);
  }

  perpLeft() {
    return this.set(this.y, -this.x);
  }

  perpRight() {
    return this.set(-this.y, this.x);
  }

  normalize() {
    const length = this.length;
    return this.set(this.x / length, this.y / length);
  }

  negate() {
    return this.set(-this.x, -this.y);
  }

  dot({ x, y }) {
    return this.x * x + this.y * y;
  }

  cross({ x, y }) {
    return this.x * y + this.y * x;
  }

  toFixed(fractionDigits = 0) {
    return `Point(${this.x.toFixed(fractionDigits)}, ${this.y.toFixed(fractionDigits)})`;
  }

  toString() {
    return `Point(${this.x}, ${this.y})`;
  }
}

export class Rect {
  #size;
  #position;

  constructor(size = new Point(), position = new Point()) {
    this.#size = size ?? new Point();
    this.#position = position ?? new Point();
  }

  get x() {
    return this.#position.x;
  }

  set x(newValue) {
    this.#position.x = newValue;
  }

  get y() {
    return this.#position.y;
  }

  set y(newValue) {
    this.#position.y = newValue;
  }

  get width() {
    return this.#size.x;
  }

  set width(newValue) {
    this.#size.x = newValue;
  }

  get height() {
    return this.#size.y;
  }

  set height(newValue) {
    this.#size.y = newValue;
  }

  get left() {
    return this.#position.x;
  }

  set left(newValue) {
    this.#position.x = newValue;
  }

  get right() {
    return this.#position.x + this.#size.x;
  }

  set right(newValue) {
    this.#size.x = newValue - this.#position.x;
  }

  get top() {
    return this.#position.y;
  }

  set top(newValue) {
    this.#position.y = newValue;
  }

  get bottom() {
    return this.#position.y + this.#size.y;
  }

  set bottom(newValue) {
    this.#size.y = newValue - this.#position.y;
  }
}
