import { Shape, Fill } from "@penpot/plugin-types";
import { Color } from "./Color";
import { Assert } from "../util/Assert.ts";

/**
 * A typed, readable and writable property of a shape. Abstracts a single visual
 * attribute (e.g. the solid fill colour) behind a uniform read/write/compare
 * interface, so operations and assertions can be generic over the property
 * rather than hard-coding one attribute. The type parameter `T` is the
 * property's value type.
 */
export interface ShapeProp<T> {
    /** A short name for the property, for diagnostics (e.g. "fill-color"). */
    readonly name: string;

    /** Reads the property's current value from `shape`. */
    read(shape: Shape): T;

    /** Writes `value` as the property's value on `shape`. */
    write(shape: Shape, value: T): void;

    /** Indicates whether two values of this property are equal. */
    equals(a: T, b: T): boolean;

    /** Asserts that `actual` equals `expected`, failing with a value-rich message. */
    assertEqual(actual: T, expected: T): void;
}

/**
 * Base class for shape properties supplying the comparison-derived behaviour:
 * given a subclass's `equals` and `render`, it provides `assertEqual`. Concrete
 * properties implement only `name`, `read`, `write`, `equals`, and `render`.
 */
export abstract class ShapePropBase<T> implements ShapeProp<T> {
    abstract readonly name: string;
    abstract read(shape: Shape): T;
    abstract write(shape: Shape, value: T): void;
    abstract equals(a: T, b: T): boolean;

    /** Renders a value of this property for assertion messages. */
    protected abstract render(value: T): string;

    assertEqual(actual: T, expected: T): void {
        Assert.equal(
            actual,
            expected,
            (a, b) => this.equals(a, b),
            (v) => this.render(v)
        );
    }
}

/**
 * The solid fill colour of a shape. Reads and writes the shape's first fill as a
 * single solid `Color`; intended for shapes whose fill is one solid colour.
 */
export class ShapePropFillColor extends ShapePropBase<Color> {
    readonly name = "fill-color";

    read(shape: Shape): Color {
        const fills = shape.fills as Fill[];
        if (!Array.isArray(fills) || fills.length === 0) {
            throw new Error(`Shape "${shape.name}" has no fills to read a colour from`);
        }
        const fill = fills[0];
        const hex = fill.fillColor;
        if (hex === undefined) {
            throw new Error(`Shape "${shape.name}"'s first fill is not a solid colour`);
        }
        return new Color(hex, fill.fillOpacity ?? 1);
    }

    write(shape: Shape, value: Color): void {
        shape.fills = [{ fillColor: value.hex, fillOpacity: value.opacity }];
    }

    equals(a: Color, b: Color): boolean {
        return a.equals(b);
    }

    protected render(value: Color): string {
        return value.toString();
    }
}

/**
 * Base class for numeric shape properties, supplying tolerance-based equality
 * (guarding against floating-point noise in values read back from the document)
 * and plain-number rendering. Concrete properties implement `name`, `read`, and
 * `write`.
 */
export abstract class ShapePropNumberBase extends ShapePropBase<number> {
    // Tolerance combining an absolute floor with a magnitude-scaled relative
    // term, wide enough to absorb float32 geometry noise in values read back.
    private static readonly ABS_EPSILON = 1e-4;
    private static readonly REL_EPSILON = 1e-5;

    equals(a: number, b: number): boolean {
        const tolerance =
            ShapePropNumberBase.ABS_EPSILON + ShapePropNumberBase.REL_EPSILON * Math.max(Math.abs(a), Math.abs(b));
        return Math.abs(a - b) <= tolerance;
    }

    protected render(value: number): string {
        return String(value);
    }
}

/**
 * The rotation of a shape, in degrees, with respect to its centre. Reads and
 * writes the shape's `rotation` directly.
 */
export class ShapePropRotation extends ShapePropNumberBase {
    readonly name = "rotation";

    read(shape: Shape): number {
        return shape.rotation;
    }

    write(shape: Shape, value: number): void {
        shape.rotation = value;
    }
}

/**
 * The height of a shape. `height` is read-only in the Plugin API, so writing goes
 * through `resize`, keeping the current width.
 */
export class ShapePropHeight extends ShapePropNumberBase {
    readonly name = "height";

    read(shape: Shape): number {
        return shape.height;
    }

    write(shape: Shape, value: number): void {
        shape.resize(shape.width, value);
    }
}
