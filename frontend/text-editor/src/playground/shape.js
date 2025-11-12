import { UUID } from "./uuid.js";
import { Rect } from "./geom.js";
import { TextContent } from "./text.js";

export class Shape {
  static Type = {
    Frame: 0,
    Group: 1,
    Bool: 2,
    Rect: 3,
    Path: 4,
    Text: 5,
    Circle: 6,
    SVGRaw: 7,
    isType(type) {
      return Object.values(this).includes(type);
    },
  };

  static RootId = UUID.ZERO;

  #id;
  #type;
  #parentId;
  #selrect;
  #childrenIds = [];
  #textContent = null;
  #rotation = 0;

  constructor(init) {
    this.#id = init?.id ?? new UUID();
    if (this.#id && !(this.#id instanceof UUID)) {
      throw new TypeError("Invalid shape id");
    }
    this.#type = init?.type ?? Shape.Type.Rect;
    if (!Shape.Type.isType(this.#type)) {
      throw new TypeError("Invalid shape type");
    }
    this.#parentId = init?.parentId ?? Shape.RootId;
    if (this.#parentId && !(this.#parentId instanceof UUID)) {
      throw new TypeError("Invalid shape parent id");
    }
    this.#selrect = init?.selrect ?? new Rect();
    if (this.#selrect && !(this.#selrect instanceof Rect)) {
      throw new TypeError("Invalid shape selrect");
    }
    this.#childrenIds = init?.childrenIds ?? [];
    if (
      !Array.isArray(this.#childrenIds) ||
      !this.#childrenIds.every((id) => id instanceof UUID)
    ) {
      throw new TypeError("Invalid shape children ids");
    }
    this.#textContent = init?.textContent ?? null;
    if (this.#textContent && !(this.#textContent instanceof TextContent)) {
      throw new TypeError("Invalid shape text content");
    }
  }

  get id() {
    return this.#id;
  }

  get type() {
    return this.#type;
  }

  get parentId() {
    return this.#parentId;
  }

  get selrect() {
    return this.#selrect;
  }

  get childrenIds() {
    return this.#childrenIds;
  }

  get textContent() {
    return this.#textContent;
  }

  get rotation() {
    return this.#rotation
  }

  set rotation(newRotation) {
    if (!Number.isFinite(newRotation)) {
      throw new TypeError('Invalid rotation')
    }
    this.#rotation = newRotation
  }
}
