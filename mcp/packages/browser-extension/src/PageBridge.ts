import type { ExtensionHello } from "@penpot/mcp-common";
import { parseAnnouncement, type ContentMessage } from "./messages.ts";

/** Page operations the bridge uses; the content script passes its window. */
export interface PageWindow {
    readonly location: { readonly origin: string };
    postMessage(message: unknown, targetOrigin: string): void;
    addEventListener(type: string, listener: (event: any) => void): void;
}

/** Channel from the content script to the service worker. */
export interface WorkerChannel {
    sendMessage(message: ContentMessage): Promise<unknown>;
}

/**
 * Relays the MCP session announced by a Penpot page to the extension's service worker.
 *
 * The bridge asks the page for its session on load, when the page reports it has started
 * listening, and when the page returns from the back/forward cache. It accepts only messages the
 * page posted on its own window from its own origin.
 */
export class PageBridge {
    constructor(
        private readonly page: PageWindow,
        private readonly worker: WorkerChannel
    ) {}

    /** Starts listening to the page and asks it for its session. */
    start(): void {
        this.page.addEventListener("message", (event: MessageEvent) => this.handleMessage(event));
        this.page.addEventListener("pagehide", () => this.send({ type: "page-hidden" }));
        this.page.addEventListener("pageshow", (event: PageTransitionEvent) => {
            if (event.persisted) {
                this.sayHello();
            }
        });
        this.sayHello();
    }

    private sayHello(): void {
        const hello: ExtensionHello = { type: "penpot-mcp-extension:hello" };
        this.page.postMessage(hello, this.page.location.origin);
    }

    private handleMessage(event: MessageEvent): void {
        if (event.source !== this.page || event.origin !== this.page.location.origin) {
            return;
        }
        // the page started listening after the hello sent on load
        if (event.data?.type === "penpot-mcp:ready") {
            this.sayHello();
            return;
        }
        const announcement = parseAnnouncement(event.data);
        if (announcement) {
            this.send({ type: "session", announcement });
        }
    }

    private send(message: ContentMessage): void {
        try {
            // the worker cannot be reached after the extension is reloaded or removed
            this.worker.sendMessage(message).catch(() => {});
        } catch {
            // ignored for the same reason
        }
    }
}
