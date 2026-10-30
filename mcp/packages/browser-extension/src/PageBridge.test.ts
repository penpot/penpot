import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import type { ContentMessage } from "./messages.ts";
import { PageBridge, type PageWindow, type WorkerChannel } from "./PageBridge.ts";

const ORIGIN = "https://design.penpot.app";

/** Page recording what the bridge posts and dispatching events to it. */
class FakePage implements PageWindow {
    readonly location = { origin: ORIGIN };
    readonly posted: { message: unknown; targetOrigin: string }[] = [];
    private readonly listeners = new Map<string, ((event: any) => void)[]>();

    postMessage(message: unknown, targetOrigin: string): void {
        this.posted.push({ message, targetOrigin });
    }

    addEventListener(type: string, listener: (event: any) => void): void {
        this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
    }

    dispatch(type: string, event: object = {}): void {
        for (const listener of this.listeners.get(type) ?? []) listener(event);
    }

    /** Posts a message on the page as the Penpot app does. */
    post(data: unknown, origin = ORIGIN, source: unknown = this): void {
        this.dispatch("message", { data, origin, source });
    }
}

/** Worker channel recording what the bridge sends. */
class FakeWorker implements WorkerChannel {
    readonly received: ContentMessage[] = [];

    async sendMessage(message: ContentMessage): Promise<unknown> {
        this.received.push(message);
        return undefined;
    }
}

const announcement = { type: "penpot-mcp:session", sessionId: "abcdefghij", status: "connected" };

let page: FakePage;
let worker: FakeWorker;

beforeEach(() => {
    page = new FakePage();
    worker = new FakeWorker();
    new PageBridge(page, worker).start();
});

test("asks the page for its session on start, restricted to the page's origin", () => {
    assert.deepEqual(page.posted, [{ message: { type: "penpot-mcp-extension:hello" }, targetOrigin: ORIGIN }]);
});

test("says hello again when the page reports it has started listening", () => {
    page.post({ type: "penpot-mcp:ready" });
    page.post({ type: "penpot-mcp:ready" }, "https://evil.example");
    assert.equal(page.posted.length, 2);
    assert.deepEqual(page.posted[1], { message: { type: "penpot-mcp-extension:hello" }, targetOrigin: ORIGIN });
});

test("forwards the page's session announcements to the worker", () => {
    page.post(announcement);
    assert.deepEqual(worker.received, [{ type: "session", announcement }]);
});

test("ignores announcements from other origins or other windows", () => {
    page.post(announcement, "https://evil.example");
    page.post(announcement, ORIGIN, {});
    assert.deepEqual(worker.received, []);
});

test("ignores invalid announcements and its own hello", () => {
    page.post({ ...announcement, status: "unknown" });
    page.post({ type: "penpot-mcp-extension:hello" });
    assert.deepEqual(worker.received, []);
});

test("reports a hidden page and asks again when it returns from the back/forward cache", () => {
    page.dispatch("pagehide");
    page.dispatch("pageshow", { persisted: false });
    assert.equal(page.posted.length, 1);
    page.dispatch("pageshow", { persisted: true });
    assert.deepEqual(worker.received, [{ type: "page-hidden" }]);
    assert.equal(page.posted.length, 2);
});

test("keeps working when the worker cannot be reached", () => {
    const failing = new FakePage();
    new PageBridge(failing, {
        sendMessage: () => {
            throw new Error("Extension context invalidated.");
        },
    }).start();
    failing.post(announcement);
    new PageBridge(failing, {
        sendMessage: () => Promise.reject(new Error("No receiver")),
    }).start();
    failing.post(announcement);
});
