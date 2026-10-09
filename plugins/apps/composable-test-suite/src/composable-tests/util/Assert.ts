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

    /**
     * Asserts a strict expected failure for `bug` (its row in the component
     * stabilization plan's suspected bugs): `expected` is the value that should
     * hold, but while the bug is open `actual` must be exactly `pinned`, the
     * wrong value the bug gives. Any other value fails, `expected` included, so
     * the pin goes only with the fix.
     */
    static knownFailure<T>(
        bug: string,
        actual: T,
        expected: T,
        pinned: T,
        equals: (a: T, b: T) => boolean,
        render: (v: T) => string = String
    ): void {
        if (!equals(actual, pinned)) {
            const fixed = equals(actual, expected) ? ". The expected value now holds: drop the pin in the fix" : "";
            throw new AssertionError(
                `known failure ${bug}: expected ${render(expected)}, pinned to the bug's ` +
                    `${render(pinned)}, but was ${render(actual)}${fixed}`
            );
        }
    }
}
