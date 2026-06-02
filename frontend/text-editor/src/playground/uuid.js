export class UUID extends Uint32Array {
  static BYTE_LENGTH = this.BYTES_PER_ELEMENT * 4;

  /**
   * @type {UUID}
   */
  static ZERO = new UUID("00000000-0000-0000-0000-000000000000");

  /**
   * Constructor
   *
   * @param {string} [id]
   */
  constructor(id = crypto.randomUUID()) {
    super(4);
    const hex = id.replace(/-/g, "");
    for (let i = 0; i < this.length; i++) {
      this[i] = parseInt(hex.slice(i * 8, (i + 1) * 8), 16);
    }
  }

  [Symbol.toPrimitive]() {
    return this.toString();
  }

  valueOf() {
    return this.toString();
  }

  toString() {
    let str = "";
    for (let i = 0; i < this.length; i++) {
      str += this[i].toString(16).padStart(8, "0");
    }
    return (
      str.slice(0, 8) +
      "-" +
      str.slice(8, 12) +
      "-" +
      str.slice(12, 16) +
      "-" +
      str.slice(16, 20) +
      "-" +
      str.slice(20)
    );
  }
}
