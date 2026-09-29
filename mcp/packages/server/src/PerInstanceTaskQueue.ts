/** Serializes mutations sent to each individual Penpot plugin instance. */
export class PerInstanceTaskQueue {
    private readonly tails = new Map<string, Promise<void>>();

    /** Runs a task after earlier tasks for the same instance have settled. */
    public async run<T>(key: string, operation: () => Promise<T>): Promise<T> {
        const previous = this.tails.get(key) ?? Promise.resolve();
        let release!: () => void;
        const current = new Promise<void>((resolve) => {
            release = resolve;
        });
        const tail = previous.catch(() => undefined).then(() => current);
        this.tails.set(key, tail);

        await previous.catch(() => undefined);
        try {
            return await operation();
        } finally {
            release();
            if (this.tails.get(key) === tail) {
                this.tails.delete(key);
            }
        }
    }
}
