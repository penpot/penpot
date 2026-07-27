import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget";

/**
 * Switches a variant-component copy head to a sibling member. Resolves the
 * instance to switch from a `ShapeTarget`, then switches it (on the single variant
 * property, position 0) to the sibling member whose value is `value` — the
 * `switchVariant` action discovers that sibling within the variant container. The
 * switch propagates outward across nesting levels via the component watcher,
 * exactly like a component swap.
 *
 * The target is resolved like any operation target (e.g. the nested instance head
 * at a given level), so this op knows nothing about nesting — it just switches
 * whatever head it is given.
 */
export class OpSwitchVariant extends Operation {
    /** The single variant property's position (this framework uses one property). */
    private static readonly PROPERTY_POSITION = 0;

    /**
     * @param target - resolves the variant-component instance head to switch
     * @param value - the sibling member's selector value to switch to
     */
    constructor(
        private readonly target: ShapeTarget,
        private readonly value: string
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const head = resolveTarget(this.target, situation);
        head.switchVariant(OpSwitchVariant.PROPERTY_POSITION, this.value);
    }

    toString(): string {
        return `switch variant to ${this.value}`;
    }
}
