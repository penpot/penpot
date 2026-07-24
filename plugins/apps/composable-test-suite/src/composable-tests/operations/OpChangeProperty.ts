import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget";
import { ShapeProp } from "../model/ShapeProp.ts";

/**
 * Sets a typed property to a given value on the shape a target resolves to. The
 * edit operation: writes `value` via the `property` strategy onto the shape that
 * `target` resolves to in the situation (a role, or a function deriving the shape
 * from the situation). Generic over the property's value type `T`.
 */
export class OpChangeProperty<T> extends Operation {
    /**
     * @param target - resolves the shape to edit (role or situation-derived)
     * @param property - the property strategy used to write the value
     * @param value - the value to write
     * @param targetLabel - a human-readable name for the edited shape, used in the
     *     applied-operation log (a function target has no readable name of its own);
     *     defaults to the target's own string form
     */
    constructor(
        private readonly target: ShapeTarget,
        private readonly property: ShapeProp<T>,
        private readonly value: T,
        private readonly targetLabel: string = String(target)
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const shape = resolveTarget(this.target, situation);
        this.property.write(shape, this.value);
    }

    /**
     * Asserts that the shape `target` resolves to now shows this operation's
     * property change — i.e. that reading this op's property from that shape
     * yields the value this op wrote. Lets a case check that an edit arrived on
     * another shape (e.g. propagated from a main to its copy) without restating
     * the property or the value.
     *
     * @param situation - the situation to resolve `target` against
     * @param target - resolves the shape expected to show the change
     */
    assertHasChangedProperty(situation: Situation, target: ShapeTarget): void {
        const shape = resolveTarget(target, situation);
        this.property.assertEqual(this.property.read(shape), this.value);
    }

    toString(): string {
        return `change ${this.property.name} of ${this.targetLabel} to ${String(this.value)}`;
    }
}
