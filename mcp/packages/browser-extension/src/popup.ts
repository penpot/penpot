import type { PopupState, PopupStateRequest } from "./PopupState.ts";

const REFRESH_INTERVAL_MS = 1_000;

const sessionsList = document.getElementById("sessions") as HTMLUListElement;

function render(state: PopupState): void {
    const items = state.sessions.map((session) => `${session.sessionId} (tab ${session.tabId})`);
    sessionsList.replaceChildren(
        ...(items.length ? items : ["No Penpot tab is connected to MCP."]).map((text) => {
            const item = document.createElement("li");
            item.textContent = text;
            item.classList.toggle("empty", items.length === 0);
            return item;
        })
    );
}

async function refresh(): Promise<void> {
    const request: PopupStateRequest = { type: "popup:get-state" };
    const state: PopupState | undefined = await chrome.runtime.sendMessage(request);
    if (state) {
        render(state);
    }
}

void refresh();
setInterval(() => void refresh(), REFRESH_INTERVAL_MS);
