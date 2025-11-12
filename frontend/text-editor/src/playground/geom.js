export class Point {
  static create(x = 0.0, y = 0.0) {
    return new Point(x, y);
  }

  constructor(x = 0.0, y = 0.0) {
    this.x = x || 0.0;
    this.y = y || 0.0;
  }

  get length() {
    return Math.hypot(this.x, this.y);
  }

  get lengthSquared() {
    return this.x * this.x + this.y * this.y;
  }

  get angle() {
    return Math.atan2(this.y, this.x);
  }

  set(x, y) {
    this.x = x ?? this.x;
    this.y = y ?? this.y;
    return this;
  }

  reset() {
    return this.set(0, 0);
  }

  copy({ x, y }) {
    return this.set(x, y);
  }

  clone() {
    return new Point(this.x, this.y);
  }

  polar(angle, length = 1.0) {
    return this.set(
      Math.cos(angle) * length,
      Math.sin(angle) * length
    );
  }

  add({ x, y }) {
    return this.set(this.x + x, this.y + y);
  }

  addScaled({ x, y }, sx, sy = sx) {
    return this.set(this.x + x * sx, this.y + y * sy);
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

  angleTo({ x, y }) {
    return Math.atan2(this.y - y, this.x - x);
  }

  distanceTo({ x, y }) {
    return Math.hypot(this.x - x, this.y - y);
  }

  toFixed(fractionDigits = 0) {
    return `Point(${this.x.toFixed(fractionDigits)}, ${this.y.toFixed(fractionDigits)})`;
  }

  toString() {
    return `Point(${this.x}, ${this.y})`;
  }
}

export class Rect {
  static create(x, y, width, height) {
    return new Rect(
      new Point(width, height),
      new Point(x, y),
    );
  }

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

  get aspectRatio() {
    return this.width / this.height;
  }

  get isHorizontal() {
    return this.width > this.height;
  }

  get isVertical() {
    return this.width < this.height;
  }

  get isSquare() {
    return this.width === this.height;
  }

  set(x, y, width, height) {
    this.#position.set(x, y);
    this.#size.set(width, height);
    return this;
  }

  reset() {
    return this.set(0, 0, 0, 0);
  }

  copy({ x, y, width, height }) {
    return this.set(x, y, width, height);
  }

  clone() {
    return new Rect(
      this.#size.clone(),
      this.#position.clone(),
    );
  }

  toFixed(fractionDigits = 0) {
    return `Rect(${this.x.toFixed(fractionDigits)}, ${this.y.toFixed(fractionDigits)}, ${this.width.toFixed(fractionDigits)}, ${this.height.toFixed(fractionDigits)})`;
  }

  toString() {
    return `Rect(${this.x}, ${this.y}, ${this.width}, ${this.height})`;
  }
}
