import type { LocalStorage } from "@penpot/plugin-types";
import type { ContentRollbackEvent } from "../../common/src";

/**
 * Storage of the rollbacks a {@link RollbackLog} has not handed over yet, which outlives a reload
 * of the plugin or of the browser tab.
 */
export interface RollbackStore {
    /**
     * Returns the stored rollbacks, oldest first.
     */
    load(): ContentRollbackEvent[];

    /**
     * Replaces the stored rollbacks.
     *
     * @param events - the rollbacks not handed over yet, oldest first
     */
    save(events: ContentRollbackEvent[]): void;
}

/**
 * Rollback store in Penpot's plugin local storage, with one entry per file.
 *
 * The plugin runs for one open file, and a reload opens the same file again, so the entry of that
 * file holds what the reload would otherwise lose. The storage is the browser's, so it is kept
 * across reloads but not shared with other browsers.
 */
export class LocalStorageRollbackStore implements RollbackStore {
    private static readonly KEY_PREFIX = "rollbacks/";

    private readonly storage: Pick<LocalStorage, "getItem" | "setItem" | "removeItem">;
    private readonly fileId: string;

    /**
     * @param storage - the plugin local storage (`penpot.localStorage`)
     * @param fileId - the id of the file the plugin runs for
     */
    constructor(storage: Pick<LocalStorage, "getItem" | "setItem" | "removeItem">, fileId: string) {
        this.storage = storage;
        this.fileId = fileId;
    }

    private get key(): string {
        return LocalStorageRollbackStore.KEY_PREFIX + this.fileId;
    }

    load(): ContentRollbackEvent[] {
        const raw = this.storage.getItem(this.key);
        if (!raw) {
            return [];
        }
        try {
            const events = JSON.parse(raw);
            return Array.isArray(events) && events.every(isRollbackEvent) ? events : [];
        } catch {
            return [];
        }
    }

    save(events: ContentRollbackEvent[]): void {
        if (events.length === 0) {
            this.storage.removeItem(this.key);
        } else {
            this.storage.setItem(this.key, JSON.stringify(events));
        }
    }
}

/**
 * Tells whether the given value has the shape of a rollback event.
 *
 * @param value - a value read from storage
 */
function isRollbackEvent(value: any): value is ContentRollbackEvent {
    const isIdList = (ids: unknown) => Array.isArray(ids) && ids.every((id) => typeof id === "string");
    return (
        typeof value === "object" &&
        value !== null &&
        isIdList(value.fileIds) &&
        isIdList(value.pageIds) &&
        isIdList(value.shapeIds)
    );
}

/**
 * Log of the rollbacks of rejected changes that the MCP server has not been told about yet.
 *
 * A rollback happens after the task that made the rejected changes has returned (the backend
 * rejects a save seconds later), so the log hands its events to the next task response. With a
 * store, the log also keeps its events across a reload, until a response carries them.
 */
export class RollbackLog {
    private readonly store?: RollbackStore;
    private events: ContentRollbackEvent[];

    /**
     * @param store - the storage that keeps the events across reloads; without one, or when it
     *   fails, the events live in memory only
     */
    constructor(store?: RollbackStore) {
        this.store = store;
        this.events = this.load();
    }

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
        this.events.push(copyEvent(event));
        this.save();
    }

    /**
     * Puts back rollbacks that a sent response carried but no tool execution received (the
     * server had given up on that task), ahead of the ones recorded since.
     *
     * @param events - the returned rollbacks, oldest first
     */
    restore(events: ContentRollbackEvent[]): void {
        this.events = [...events.map(copyEvent), ...this.events];
        this.save();
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
        if (count === 0) {
            return;
        }
        this.events = this.events.slice(count);
        this.save();
    }

    private load(): ContentRollbackEvent[] {
        try {
            return this.store?.load() ?? [];
        } catch (error) {
            console.warn("Unable to read the stored rollbacks:", error);
            return [];
        }
    }

    private save(): void {
        try {
            this.store?.save(this.events);
        } catch (error) {
            console.warn("Unable to store the rollbacks:", error);
        }
    }
}

/**
 * Copies a rollback event into a plain object.
 *
 * @param event - the rollback, possibly a proxy or array-like from Penpot
 */
function copyEvent(event: ContentRollbackEvent): ContentRollbackEvent {
    return {
        fileIds: [...event.fileIds],
        pageIds: [...event.pageIds],
        shapeIds: [...event.shapeIds],
    };
}
