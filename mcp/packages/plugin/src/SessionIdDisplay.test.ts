import assert from "node:assert/strict";
import test from "node:test";
import { SessionIdDisplay } from "./SessionIdDisplay.ts";

class DisplayFixture {
    public readonly value = { textContent: "" };
    public readonly feedback = { textContent: "" };
    public onCopy!: () => Promise<void>;
    public readonly button = {
        disabled: true,
        addEventListener: (_event: string, listener: () => Promise<void>) => {
            this.onCopy = listener;
        },
    };
    public readonly root = {
        hidden: true,
        querySelector: (selector: string) =>
            ({
                "#session-id-value": this.value,
                "#copy-session-id-btn": this.button,
                "#session-id-feedback": this.feedback,
            })[selector],
    };
    public readonly copied: string[] = [];
    public readonly display: SessionIdDisplay;

    constructor(
        writeText = async (text: string) => {
            this.copied.push(text);
        }
    ) {
        this.display = new SessionIdDisplay(this.root as unknown as HTMLElement, { writeText });
    }
}

test("shows and copies the exact session ID, then clears it on disconnect", async () => {
    const ui = new DisplayFixture();
    ui.display.setSessionId("pq3gxqddgj");
    assert.equal(ui.root.hidden, false);
    assert.equal(ui.value.textContent, "pq3gxqddgj");
    await ui.onCopy();
    assert.deepEqual(ui.copied, ["pq3gxqddgj"]);
    assert.equal(ui.feedback.textContent, "Copied");
    ui.display.setSessionId(null);
    assert.equal(ui.root.hidden, true);
    assert.equal(ui.value.textContent, "");
    assert.equal(ui.feedback.textContent, "");
    assert.equal(ui.button.disabled, true);
    await ui.onCopy();
    assert.equal(ui.copied.length, 1);
});

test("reports clipboard denial and keeps the ID available for manual copying", async () => {
    const ui = new DisplayFixture(async () => {
        throw new Error("Permission denied");
    });
    ui.display.setSessionId("pq3gxqddgj");
    await ui.onCopy();
    assert.match(ui.feedback.textContent, /Could not copy/);
    assert.equal(ui.value.textContent, "pq3gxqddgj");
});

test("ignores late clipboard feedback after the session changes", async () => {
    let finish!: () => void;
    const ui = new DisplayFixture(
        () =>
            new Promise<void>((resolve) => {
                finish = resolve;
            })
    );
    ui.display.setSessionId("pq3gxqddgj");
    const pending = ui.onCopy();
    ui.display.setSessionId("jablgbun4p");
    finish();
    await pending;
    assert.equal(ui.feedback.textContent, "");
});
