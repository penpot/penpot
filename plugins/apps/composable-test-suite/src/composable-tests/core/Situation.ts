import { Shape } from "@penpot/plugin-types";
import { Role } from "./Role";
import { DataKey } from "./DataKey";

/**
 * The mutable state a test trajectory operates on. Carries the role bindings
 * (meaningful shapes of the configuration, resolved to live Plugin API handles)
 * and the ordered log of operations applied so far.
 *
 * Unlike a pure in-memory model, the situation operates on the LIVE Penpot
 * document: an operation mutates the document through the Plugin API and updates
 * the bindings. Role lookup is strict — an unbound role throws diagnostically
 * rather than returning a nullish value.
 */
export class Situation {
    private static readonly SHAPE_SPACING = 10; // pixels to advance X for next shape

    private readonly roles = new Map<string, Shape>();
    private readonly appliedLog: string[] = [];
    private readonly appliedIds = new Set<number>();
    private readonly opData = new Map<number, unknown>();
    private readonly keyedData = new Map<DataKey<unknown>, unknown>();
    private nextShapeX = 0;
    private nextShapeY = 0;

    constructor(shapePosY: number = 0) {
        this.nextShapeY = shapePosY;
    }

    /**
     * Binds `role` to `shape`, replacing any existing binding. Returns this
     * situation to allow fluent setup.
     */
    bind<T extends Shape>(role: Role<T>, shape: T): this {
        this.roles.set(role.name, shape);
        return this;
    }

    /**
     * Positions `shape` at the next designated coordinates, then advances the X
     * coordinate for the next shape.
     * Shapes are laid out in a horizontal row with a fixed spacing between them.
     *
     * @param shape - the shape to position
     */
    applyPosAdvanceX(shape: Shape): void {
        shape.x = this.nextShapeX;
        shape.y = this.nextShapeY;
        this.nextShapeX += shape.width + Situation.SHAPE_SPACING; // advance X for next shape
    }

    /**
     * Resolves `role` to its bound shape. Throws a diagnostic error naming the
     * absent role and the roles that are bound, never returning nullish.
     */
    get<T extends Shape>(role: Role<T>): T {
        const shape = this.roles.get(role.name);
        if (shape === undefined) {
            const present = Array.from(this.roles.keys()).join(", ");
            throw new Error(`Unbound role "${role.name}". Bound roles: [${present}]`);
        }
        return shape as T;
    }

    /** Indicates whether `role` is currently bound. */
    has(role: Role): boolean {
        return this.roles.has(role.name);
    }

    /**
     * Records that an operation described by `description` was applied, appending
     * it to the ordered log.
     */
    recordApplication(description: string): void {
        this.appliedLog.push(description);
    }

    /** The ordered transcript of applied operations, for failure diagnostics. */
    get transcript(): readonly string[] {
        return this.appliedLog;
    }

    /**
     * Marks `operation` as applied in this trajectory (by its stable identity).
     * Accepts anything carrying an `id`, to avoid a dependency on the operation
     * class.
     */
    markApplied(operation: { id: number }): void {
        this.appliedIds.add(operation.id);
    }

    /**
     * Indicates whether `operation` was applied in this trajectory. Used by
     * assertions to branch on which optional steps a given enumerated variant
     * took.
     */
    wasApplied(operation: { id: number }): boolean {
        return this.appliedIds.has(operation.id);
    }

    /**
     * Stores operation-specific data for `operation` (keyed by its stable identity),
     * replacing any previous value. This is the situation's generic per-operation
     * store; operations are expected to wrap it in typed accessors rather than
     * callers reading it directly. The value type is the operation's own concern.
     */
    setData(operation: { id: number }, value: unknown): void {
        this.opData.set(operation.id, value);
    }

    /**
     * Retrieves the data previously stored for `operation`, or `undefined` if none.
     * The caller (the owning operation) knows the value's type and narrows it.
     */
    getData(operation: { id: number }): unknown {
        return this.opData.get(operation.id);
    }

    /**
     * Stores data under `key`, replacing any previous value. This is the situation's
     * shared keyed store: several cooperating operations may read and write one
     * value under a common `DataKey`. The owning op class wraps this in typed
     * accessors rather than exposing the key. The value type is `T`.
     */
    setKeyedData<T>(key: DataKey<T>, value: T): void {
        this.keyedData.set(key as DataKey<unknown>, value);
    }

    /**
     * Retrieves the data stored under `key`, or `undefined` if none. The owning op
     * class knows the value's type via the key's type parameter.
     */
    getKeyedData<T>(key: DataKey<T>): T | undefined {
        return this.keyedData.get(key as DataKey<unknown>) as T | undefined;
    }
}
