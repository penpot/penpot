import { LibraryComponent } from "@penpot/plugin-types";
import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget";
import { PenpotSync } from "../util/PenpotSync";

/**
 * Swaps the copy head a target resolves to for an instance of another
 * component, via the Plugin API's `swapComponent()`. The new head keeps the
 * old head's id, so a target that found the old head finds the new one.
 *
 * Unlike the swap in the workspace UI, the API keeps the head's overrides
 * (it swaps with keep-touched, F33 in the stabilization plan).
 */
export class OpSwap extends Operation {
    /**
     * @param target - resolves the copy head to swap
     * @param component - resolves the component to swap to
     * @param label - a human-readable description of the swap, used in the
     *     applied-operation log
     */
    constructor(
        private readonly target: ShapeTarget,
        private readonly component: (situation: Situation) => LibraryComponent,
        private readonly label: string
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        // act on the settled document, as a user would
        await PenpotSync.awaitPropagation();
        const to = this.component(situation);
        resolveTarget(this.target, situation).swapComponent(to);
        await PenpotSync.awaitPropagation();
        // an API call that fails only logs, so a case would go on as if nothing happened
        if (resolveTarget(this.target, situation).component()?.id !== to.id) {
            throw new Error(`the swap did not take effect: ${this}`);
        }
    }

    toString(): string {
        return `swap ${this.label}`;
    }
}
