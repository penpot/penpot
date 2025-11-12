export class ColorChannel {
  #value = 0.0;

  constructor(value = 0.0) {
    this.#value = value || 0.0;
  }

  get value() {
    return this.#value;
  }

  set value(newValue) {
    if (!Number.isFinite(newValue))
      throw new TypeError("Invalid color channel value");
    this.#value = newValue;
  }

  get valueAsUint8() {
    return Math.max(0, Math.min(0xff, Math.floor(this.#value * 0xff)));
  }

  set valueAsUint8(newValue) {
    if (!Number.isFinite(newValue))
      throw new TypeError("Invalid color channel value as Uint8");
    this.#value = Math.max(0, Math.min(0xff, Math.floor(newValue))) / 0xff;
  }

  toString(format) {
    switch (format) {
      case "hex":
        return this.valueAsUint8.toString(16).padStart(2, "0");
      default:
        return this.valueAsUint8.toString();
    }
  }
}

export class Color {
  static fromHex(string) {
    if (string.length === 4 || string.length === 5) {
      const hr = string.slice(1, 2);
      const hg = string.slice(2, 3);
      const hb = string.slice(3, 4);
      const ha = string.length === 5 ? string.slice(4, 5) : "f";
      const r = parseInt(hr + hr, 16);
      const g = parseInt(hg + hg, 16);
      const b = parseInt(hb + hb, 16);
      const a = parseInt(ha + ha, 16);
      return Color.fromUint8(r, g, b, a);
    } else if (string.length === 7 || string.length === 9) {
      const r = parseInt(string.slice(1, 3), 16);
      const g = parseInt(string.slice(3, 5), 16);
      const b = parseInt(string.slice(5, 7), 16);
      const a = parseInt(string.length === 9 ? string.slice(7, 9) : "ff", 16);
      return Color.fromUint8(r, g, b, a);
    } else {
      throw new TypeError("Invalid hex string");
    }
  }

  static fromUint8(r, g, b, a) {
    return new Color(r / 0xff, g / 0xff, b / 0xff, a / 0xff);
  }

  static parse(string) {
    if (string.startsWith("#")) {
      return Color.fromHex(string);
    } else if (string.startsWith("rgb")) {
      throw new Error("Not implemented");
    } else if (string.startsWith("hsl")) {
      throw new Error("Not implemented");
    }
  }

  #r = new ColorChannel(0);
  #g = new ColorChannel(0);
  #b = new ColorChannel(0);
  #a = new ColorChannel(1);

  constructor(r = 0, g = 0, b = 0, a = 1) {
    this.#r.value = r || 0;
    this.#g.value = g || 0;
    this.#b.value = b || 0;
    this.#a.value = a || 1;
  }

  get r() {
    return this.#r.value;
  }
  get g() {
    return this.#g.value;
  }
  get b() {
    return this.#b.value;
  }
  get a() {
    return this.#a.value;
  }

  set r(newValue) {
    this.#r.value = newValue;
  }
  set g(newValue) {
    this.#g.value = newValue;
  }
  set b(newValue) {
    this.#b.value = newValue;
  }
  set a(newValue) {
    this.#a.value = newValue;
  }

  get r8() {
    return this.#r.valueAsUint8;
  }
  get g8() {
    return this.#g.valueAsUint8;
  }
  get b8() {
    return this.#b.valueAsUint8;
  }
  get a8() {
    return this.#a.valueAsUint8;
  }

  get argb32() {
    return ((this.a8 << 24) | (this.r8 << 16) | (this.g8 << 8) | this.b8) >>> 0;
  }
}
