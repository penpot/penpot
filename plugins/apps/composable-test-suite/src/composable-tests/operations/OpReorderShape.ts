import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget";

/**
 * Reorders the shape a target resolves to within its parent, via the Plugin API's
 * `setParentIndex`. Used to reorder a sub-head IN THE MAIN of a component, which is
 * the operation that breaks copies' swap-slot alignment (see {@link ../cases/caseE}).
 */
export class OpReorderShape extends Operation {
    /**
     * @param target - resolves the shape to reorder (role or situation-derived)
     * @param toIndex - the new 0-based index within the parent
     * @param targetLabel - a human-readable name for the reordered shape, for the log
     */
    constructor(
        private readonly target: ShapeTarget,
        private readonly toIndex: number,
        private readonly targetLabel: string = String(target)
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const shape = resolveTarget(this.target, situation);
        shape.setParentIndex(this.toIndex);
    }

    toString(): string {
        return `reorder ${this.targetLabel} to index ${this.toIndex}`;
    }
}
