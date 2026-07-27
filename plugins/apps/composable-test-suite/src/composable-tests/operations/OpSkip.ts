import { Situation } from "../core/Situation.ts";
import { Operation } from "../core/Operation.ts";

/**
 * The identity operation: applies nothing. Used as the "absent" branch of an
 * optional step, so a sweep can include a variant in which that step did not run.
 */
export class OpSkip extends Operation {
    async applyTo(_situation: Situation): Promise<void> {
        // intentionally does nothing
    }

    toString(): string {
        return "skip";
    }

    /** A skip did nothing, so it is left out of the applied-operation log. */
    override isRecorded(): boolean {
        return false;
    }
}
