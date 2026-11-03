import { Board } from "@penpot/plugin-types";
import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget";
import { Assert } from "../util/Assert";

/**
 * Moves the shape a target resolves to into another parent, via the Plugin API's
 * `appendChild`. Used to move a shape out of the main of a component, which must
 * remove the corresponding shape from its copies.
 */
export class OpMoveShape extends Operation {
    /**
     * @param target - resolves the shape to move (role or situation-derived)
     * @param newParent - resolves the board that receives the shape
     * @param targetLabel - a human-readable name for the moved shape, for the log
     * @param parentLabel - a human-readable name for the new parent, for the log
     */
    constructor(
        private readonly target: ShapeTarget,
        private readonly newParent: ShapeTarget,
        private readonly targetLabel: string = String(target),
        private readonly parentLabel: string = String(newParent)
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const shape = resolveTarget(this.target, situation);
        const parent = resolveTarget(this.newParent, situation) as Board;
        parent.appendChild(shape);
    }

    /**
     * Asserts that the shape `target` resolves to now sits in this operation's
     * new parent.
     *
     * @param situation - the situation to resolve the targets against
     * @param target - resolves the shape expected to have moved
     */
    assertHasMoved(situation: Situation, target: ShapeTarget): void {
        const shape = resolveTarget(target, situation);
        const parent = resolveTarget(this.newParent, situation);
        Assert.that(
            shape.parent?.id === parent.id,
            `expected "${shape.name}" inside "${parent.name}" but its parent is "${shape.parent?.name}"`
        );
    }

    toString(): string {
        return `move ${this.targetLabel} into ${this.parentLabel}`;
    }
}
