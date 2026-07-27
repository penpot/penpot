/**
 * Timing utilities for the composable tests.
 */
export class PenpotSync {
    /** The fixed settling delay, in milliseconds, used by `awaitPropagation`. */
    private static readonly PROPAGATION_MS = 200;

    /**
     * Waits for component-change propagation to settle. Reads can otherwise race
     * ahead of the propagation a preceding edit triggers. This is a pragmatic
     * fixed delay; it is intended to be replaced once the Plugin API offers an
     * explicit "wait for propagation" primitive.
     */
    static awaitPropagation(): Promise<void> {
        return new Promise((resolve) => setTimeout(resolve, PenpotSync.PROPAGATION_MS));
    }
}
