import { Color } from "./color.js";

export class FillSolid {
  color = new Color();

  get opacity() {
    return this.color.a;
  }

  set opacity(newOpacity) {
    this.color.a = newOpacity;
  }
}

export class Fill {
  static BYTE_LENGTH = 160;

  static Type = {
    SOLID: 0,
    LINEAR_GRADIENT: 1,
    RADIAL_GRADIENT: 2,
    IMAGE: 3,
  };

  #type;
  #solid = new FillSolid();

  constructor(type = Fill.Type.SOLID) {
    this.#type = type;
  }

  get type() {
    return this.#type;
  }

  get solid() {
    return this.#solid;
  }
}
