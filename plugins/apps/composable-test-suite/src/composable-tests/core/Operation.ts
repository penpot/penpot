import { Situation } from "./Situation";

/**
 * A single step in a test trajectory. Most operations transform the situation
 * (an edit, a structural change); some only observe it (an assertion). Reified
 * as an object (strategy pattern) so steps are composable and self-describing.
 *
 * Each operation carries a stable identity assigned at construction. Applying it
 * records that identity in the situation, so `Situation.wasApplied` can later be
 * asked whether a particular operation instance ran in the current trajectory —
 * the basis for branching assertions over an enumerated sweep. (Bind an operation
 * to a value once and reuse that value, so the identity asked about is the one
 * that ran.)
 *
 * Application is asynchronous because Plugin API mutations and their propagation
 * may settle asynchronously. An operation mutates the live document and the
 * situation's bindings in place.
 */
export abstract class Operation {
    /** Source of stable per-instance ids; incremented as each operation is created. */
    private static nextId = 0;

    /** This operation instance's stable identity. */
    readonly id: number = Operation.nextId++;

    /** Applies this operation to `situation`, mutating it in place. */
    abstract applyTo(situation: Situation): Promise<void>;

    /** A short, human-readable representation, recorded in the applied-operation log. */
    abstract toString(): string;

    /**
     * Indicates whether applying this operation should be recorded in the
     * situation's applied-operation log. True for operations that do something;
     * overridden to false by no-ops (e.g. the skip of an untaken optional branch),
     * so the log reflects only the operations that were actually applied.
     */
    isRecorded(): boolean {
        return true;
    }

    /**
     * Expands this operation into the concrete variants it stands for — every
     * combination of choices it (and its children) contain, with no branch points
     * remaining. A plain operation has a single variant: itself. Composites that
     * introduce choice (a sequence of sub-operations, a branch point) override this
     * to combine their children's variants. The runner runs each returned variant
     * against a freshly-built situation.
     */
    enumerateVariants(): Operation[] {
        return [this];
    }
}
