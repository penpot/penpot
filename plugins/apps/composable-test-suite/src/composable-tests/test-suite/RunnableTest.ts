import { Operation } from "../core/Operation.ts";
import { Situation } from "../core/Situation.ts";
import { TestResult } from "./TestResult.ts";

/**
 * One concrete, runnable test: a single enumerated variant of a case, carrying a
 * stable id (its identity across enumeration, the UI, and run requests), a display
 * name, and the operation to apply. Running applies the operation to a fresh,
 * empty situation (so variants do not interfere) and captures any failure as a
 * failing result. The operation's first steps are responsible for laying the
 * foundations (creating the starting configuration).
 */
export class RunnableTest {
    /**
     * @param id - stable opaque identity (used as the UI key and in run requests)
     * @param name - display name (e.g. "instance #1")
     * @param operation - the concrete (fully chosen) trajectory to apply
     */
    constructor(
        public readonly id: string,
        public readonly name: string,
        private readonly operation: Operation
    ) {}

    /** Runs this test against a fresh situation, returning its result. */
    async run(posY: number): Promise<TestResult> {
        const situation = new Situation(posY);
        try {
            await this.operation.applyTo(situation);
            return TestResult.pass(this.name, situation.transcript);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            return TestResult.fail(this.name, message, situation.transcript);
        }
    }
}
