import { Point } from './geom';

export class Viewport {
  #zoom;
  #position = new Point();

  constructor(init) {
    this.#zoom = init?.zoom || 1;
    this.#position.x = init?.x || 0;
    this.#position.y = init?.y || 0;
  }

  get zoom() {
    return this.#zoom;
  }

  get x() {
    return this.#position.x;
  }

  get y() {
    return this.#position.y;
  }

  set zoom(newZoom) {
    if (!Number.isFinite(newZoom)) throw new TypeError("Invalid new zoom");
    this.#zoom = newZoom;
  }

  set x(newX) {
    if (!Number.isFinite(newX)) throw new TypeError("Invalid new x");
    this.#position.x = newX;
  }

  set y(newY) {
    if (!Number.isFinite(newY)) throw new TypeError("Invalid new y");
    this.#position.y = newY;
  }

  pan(dx, dy) {
    this.#position.x += dx / this.#zoom
    this.#position.y += dy / this.#zoom
  }
}
