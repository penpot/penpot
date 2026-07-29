/**
 * Signals a failed test assertion. Thrown by assertion helpers and caught by the
 * test runner, which turns it into a failed result.
 */
export class AssertionError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "AssertionError";
    }
}

/**
 * Provides assertion helpers for use inside a test's assertion phase. Each helper
 * throws an `AssertionError` on failure; the runner catches it.
 */
export class Assert {
    /** Asserts that `condition` holds, failing with `message` otherwise. */
    static that(condition: boolean, message: string): void {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Asserts that `actual` equals `expected` using `equals`, failing with a
     * message that includes both values (rendered via `render`).
     */
    static equal<T>(actual: T, expected: T, equals: (a: T, b: T) => boolean, render: (v: T) => string = String): void {
        if (!equals(actual, expected)) {
            throw new AssertionError(`expected ${render(expected)} but was ${render(actual)}`);
        }
    }
}
