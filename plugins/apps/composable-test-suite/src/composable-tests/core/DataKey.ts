/**
 * A typed, identity-based key for arbitrary data held in a situation — the
 * non-shape analogue of a `Role`. Where a role names a participant shape, a data
 * key names a piece of operation state that is NOT a shape (e.g. a per-level list
 * of nested instance heads). Unlike per-op data (keyed by a single operation's
 * identity), a data key is meant to be SHARED by several cooperating operations
 * that must read and write one common structure, so the key — not any one op —
 * is the identity of the data.
 *
 * Keys are compared by reference identity (two distinct `DataKey` instances are
 * different keys, even with the same label). A data key is an internal mechanism
 * of the op class that owns it: construct it privately and expose typed accessors,
 * never the key itself. The phantom type parameter `T` records the value type.
 */
export class DataKey<T> {
    /** Phantom marker carrying the value type; never read at runtime. */
    declare private readonly _valueType: T;

    /**
     * @param label - a stable, human-readable label (used only in diagnostics)
     */
    constructor(public readonly label: string) {}

    toString(): string {
        return this.label;
    }
}
