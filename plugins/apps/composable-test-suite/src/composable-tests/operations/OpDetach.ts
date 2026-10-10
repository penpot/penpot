import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget";
import { PenpotSync } from "../util/PenpotSync";

/**
 * Detaches the copy head a target resolves to from its component, via the
 * Plugin API's `detach()`. The copy's shapes lose their component links; its
 * nested copies stay copies, one nesting level up.
 */
export class OpDetach extends Operation {
    /**
     * @param target - resolves the copy head to detach
     * @param targetLabel - a human-readable name for the detached shape, used in
     *     the applied-operation log; defaults to the target's own string form
     */
    constructor(
        private readonly target: ShapeTarget,
        private readonly targetLabel: string = String(target)
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        // act on the settled document, as a user would
        await PenpotSync.awaitPropagation();
        const shape = resolveTarget(this.target, situation);
        shape.detach();
        await PenpotSync.awaitPropagation();
        // an API call that fails only logs, so a case would go on as if nothing happened
        if (shape.component() != null) {
            throw new Error(`the detach did not take effect: ${this}`);
        }
    }

    toString(): string {
        return `detach ${this.targetLabel}`;
    }
}
