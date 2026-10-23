/**
 * Timing utilities for the composable tests.
 */
export class PenpotSync {
    /**
     * Waits for component-change propagation to settle. Reads can otherwise race
     * ahead of the propagation a preceding edit triggers. A deadline fails the operation instead of checking a partly synced file.
     */
    static async awaitPropagation(timeoutMs = 10000): Promise<void> {
        const api = penpot as typeof penpot & { waitForLayoutUpdate(): Promise<void> };
        if (!api.waitForLayoutUpdate) {
            throw new Error("PenpotSync.awaitPropagation: waitForLayoutUpdate is not available");
        }
        let timer: ReturnType<typeof setTimeout> | undefined;
        try {
            await Promise.race([
                api.waitForLayoutUpdate(),
                new Promise<never>((_, reject) => {
                    timer = setTimeout(
                        () => reject(new Error(`Component propagation did not settle in ${timeoutMs}ms`)),
                        timeoutMs
                    );
                }),
            ]);
        } finally {
            clearTimeout(timer);
        }
    }
}
