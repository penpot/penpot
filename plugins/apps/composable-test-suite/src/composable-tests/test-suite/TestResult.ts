/**
 * The outcome of running one concrete variant of a test case. An immutable record
 * carrying the variant's display name, whether it passed, and — on failure — the
 * error message and the transcript of operations that had been applied.
 */
export class TestResult {
    private constructor(
        public readonly name: string,
        public readonly passed: boolean,
        public readonly errorMessage: string | undefined,
        public readonly transcript: readonly string[]
    ) {}

    /** Creates a passing result named `name`. */
    static pass(name: string, transcript: readonly string[]): TestResult {
        return new TestResult(name, true, undefined, transcript);
    }

    /** Creates a failing result named `name` with `errorMessage` and `transcript`. */
    static fail(name: string, errorMessage: string, transcript: readonly string[]): TestResult {
        return new TestResult(name, false, errorMessage, transcript);
    }
}
