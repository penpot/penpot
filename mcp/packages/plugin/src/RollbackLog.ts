import type { ContentRollbackEvent } from "../../common/src";

/**
 * Log of the rollbacks of rejected changes that the MCP server has not been told about yet.
 *
 * A rollback happens after the task that made the rejected changes has returned (the backend
 * rejects a save seconds later), so the log hands its events to the next task response.
 */
export class RollbackLog {
    private events: ContentRollbackEvent[] = [];

    /**
     * Starts recording the rollbacks Penpot reports.
     *
     * Penpot versions without the `contentrollback` event reject the subscription; the log then
     * stays empty.
     */
    listen(): void {
        try {
            // @ts-ignore - contentrollback is not yet in the released plugin types
            penpot.on("contentrollback", (event: ContentRollbackEvent) => this.record(event));
        } catch (error) {
            console.warn("Penpot does not report rollbacks:", error);
        }
    }

    /**
     * Adds a rollback to the log.
     *
     * @param event - the rollback as reported by Penpot
     */
    record(event: ContentRollbackEvent): void {
        this.events.push({
            fileIds: [...event.fileIds],
            pageIds: [...event.pageIds],
            shapeIds: [...event.shapeIds],
        });
    }

    /**
     * Returns the rollbacks not yet acknowledged, oldest first.
     */
    pending(): ContentRollbackEvent[] {
        return [...this.events];
    }

    /**
     * Removes the oldest `count` rollbacks, once a response carrying them has been sent.
     *
     * @param count - the number of rollbacks the sent response carried
     */
    acknowledge(count: number): void {
        this.events = this.events.slice(count);
    }
}
