
export class Viewport {
  #zoom;
  #x;
  #y;

  constructor(init) {
    this.#zoom = init?.zoom || 1;
    this.#x = init?.x || 0;
    this.#y = init?.y || 0;
  }

  get zoom() {
    return this.#zoom;
  }
  get x() {
    return this.#x;
  }
  get y() {
    return this.#y;
  }

  set zoom(newZoom) {
    if (!Number.isFinite(newZoom)) throw new TypeError("Invalid new zoom");
    this.#zoom = newZoom;
  }

  set x(newX) {
    if (!Number.isFinite(newX)) throw new TypeError("Invalid new x");
    this.#x = newX;
  }

  set y(newY) {
    if (!Number.isFinite(newY)) throw new TypeError("Invalid new y");
    this.#y = newY;
  }

  pan(dx, dy) {
    this.#x += dx
    this.#y += dy
  }
}
