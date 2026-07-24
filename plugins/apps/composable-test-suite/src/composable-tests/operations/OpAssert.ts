import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { PenpotSync } from "../util/PenpotSync.ts";

/**
 * An assertion step that observes the situation without changing it. Runs the
 * supplied assertion function against the situation; the function performs the
 * checks (via the `Assert` helpers) and throws on failure, which the runner
 * captures. The inline-assertion analogue of an edit operation, so checks can be
 * placed at any point in a trajectory.
 *
 * To tolerate reads that race ahead of an edit's not-yet-settled propagation, the
 * assertion is tried once immediately (the fast path, paying no delay when the
 * document is already consistent); only if it fails is propagation awaited and the
 * assertion retried once, whose outcome is final.
 */
export class OpAssert extends Operation {
    /**
     * @param description - a short description of what is asserted
     * @param assertion - performs the assertions against the situation
     */
    constructor(
        private readonly description: string,
        private readonly assertion: (situation: Situation) => void
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        try {
            this.assertion(situation);
        } catch {
            // a read may have raced propagation; let it settle and check once more
            await PenpotSync.awaitPropagation();
            this.assertion(situation);
        }
    }

    toString(): string {
        return `assert ${this.description}`;
    }
}
