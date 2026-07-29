import { createLogger } from "../logger";

/**
 * Counting semaphore for bounding the parallelism of asynchronous operations.
 *
 * Calls in excess of the configured maximum are queued in FIFO order and
 * proceed once an earlier holder has released its permit.
 */
export class Semaphore {
    private static readonly logger = createLogger("Semaphore");

    private available: number;
    private readonly waiters: Array<() => void> = [];

    /**
     * @param name - identifier used in log messages so the source of contention is recognisable
     * @param maxConcurrent - the maximum number of permits that may be held simultaneously
     */
    constructor(
        private readonly name: string,
        maxConcurrent: number
    ) {
        if (maxConcurrent < 1) {
            throw new Error(`maxConcurrent must be at least 1; got ${maxConcurrent}`);
        }
        this.available = maxConcurrent;
    }

    /**
     * Acquires a permit, runs the given function, and releases the permit when it
     * settles - including on rejection. Queues if no permit is currently available.
     *
     * @param fn - the function to run while holding the permit
     * @returns the value returned by fn
     */
    public async withPermit<T>(fn: () => Promise<T>): Promise<T> {
        await this.acquire();
        try {
            return await fn();
        } finally {
            this.release();
        }
    }

    private acquire(): Promise<void> {
        if (this.available > 0) {
            this.available--;
            return Promise.resolve();
        }
        return new Promise<void>((resolve) => {
            this.waiters.push(resolve);
            Semaphore.logger.info(
                "Semaphore '%s' saturated; request queued (%d waiting)",
                this.name,
                this.waiters.length
            );
        });
    }

    private release(): void {
        const next = this.waiters.shift();
        if (next !== undefined) {
            // pass the permit directly to the next waiter without touching `available`
            next();
        } else {
            this.available++;
        }
    }
}
