import { Operation } from "../core/Operation.ts";

/**
 * A complete test: a named operation — the full trajectory, including the step
 * that lays the foundations (creating the starting configuration), any edits or
 * structural changes, and the assertions. Running it applies the operation to a
 * fresh, empty situation.
 */
export class TestCase {
    /**
     * @param identifier - a meaningful CamelCase case identifier, stating the
     *     tested behaviour (e.g. "MainEditSyncs")
     * @param description - what the case tests, in plain, highly understandable
     *     terms and in three parts, in this order: (1) the situation SETUP — what
     *     configuration is created, so the reader can picture it; (2) the ACTIONS
     *     and variations applied to it; (3) the REQUIREMENT that is asserted.
     *     A few sentences, starting with an upper-case letter.
     * @param operation - the trajectory to apply, foundations first, including
     *     assertions
     */
    constructor(
        public readonly identifier: string,
        public readonly description: string,
        public readonly operation: Operation
    ) {}
}
