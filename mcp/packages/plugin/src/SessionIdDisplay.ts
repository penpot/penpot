/** Displays the active session ID and provides clipboard feedback. */
export class SessionIdDisplay {
    private sessionId: string | null = null;
    private readonly value: HTMLElement;
    private readonly button: HTMLButtonElement;
    private readonly feedback: HTMLElement;

    private readonly root: HTMLElement;
    private readonly clipboard: Pick<Clipboard, "writeText">;

    constructor(root: HTMLElement, clipboard: Pick<Clipboard, "writeText">) {
        this.root = root;
        this.clipboard = clipboard;
        this.value = root.querySelector<HTMLElement>("#session-id-value")!;
        this.button = root.querySelector<HTMLButtonElement>("#copy-session-id-btn")!;
        this.feedback = root.querySelector<HTMLElement>("#session-id-feedback")!;
        this.button.addEventListener("click", () => this.copy());
        this.setSessionId(null);
    }

    public setSessionId(sessionId: string | null): void {
        this.sessionId = sessionId;
        this.root.hidden = sessionId === null;
        this.value.textContent = sessionId ?? "";
        this.button.disabled = sessionId === null;
        this.feedback.textContent = "";
    }

    protected async copy(): Promise<void> {
        const sessionId = this.sessionId;
        if (!sessionId) return;
        try {
            await this.clipboard.writeText(sessionId);
            if (this.sessionId === sessionId) this.feedback.textContent = "Copied";
        } catch {
            if (this.sessionId === sessionId) {
                this.feedback.textContent = "Could not copy. Select the ID to copy it manually.";
            }
        }
    }
}
