import { Shape } from "@penpot/plugin-types";
import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget";
import { PenpotSync } from "../util/PenpotSync";

/**
 * Resets the overrides of the copy head a target resolves to, via the Plugin
 * API's `resetOverrides()`: its subtree becomes equal to its main again.
 */
export class OpReset extends Operation {
    /**
     * @param target - resolves the copy head to reset
     * @param targetLabel - a human-readable name for the reset shape, used in
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
        // the published @penpot/plugin-types the suite pins predates `resetOverrides`
        const shape = resolveTarget(this.target, situation) as Shape & { resetOverrides(): void };
        shape.resetOverrides();
        await PenpotSync.awaitPropagation();
    }

    toString(): string {
        return `reset overrides of ${this.targetLabel}`;
    }
}
