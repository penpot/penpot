/**
 * An immutable solid colour value. Combines a hex colour string with an opacity,
 * giving the tests a single comparable value for a shape's solid fill.
 */
export class Color {
    /**
     * @param hex - the colour as an upper-case hex string (e.g. "#FF5533")
     * @param opacity - the opacity in [0, 1] (defaults to fully opaque)
     */
    constructor(
        public readonly hex: string,
        public readonly opacity: number = 1
    ) {}

    /** Indicates whether this colour equals `other` (same hex, same opacity). */
    equals(other: Color): boolean {
        return this.hex === other.hex && this.opacity === other.opacity;
    }

    /** A human-readable representation, for assertion messages. */
    toString(): string {
        return this.opacity === 1 ? this.hex : `${this.hex}@${this.opacity}`;
    }
}
