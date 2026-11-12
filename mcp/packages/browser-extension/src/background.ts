/**
 * Service worker of the Penpot MCP extension.
 *
 * It keeps the map from MCP sessions to the tabs holding them, and keeps the browser from
 * discarding those tabs.
 *
 * Chrome may stop the worker, and Firefox its event page, at any time, so listeners are
 * registered synchronously at start and state lives in session storage.
 */
import { ChromeDiscardControl, ChromeSessionStore } from "./chrome.ts";
import { DiscardGuard } from "./DiscardGuard.ts";
import { parseAnnouncement, type ContentMessage } from "./messages.ts";
import { getPopupState, type PopupStateRequest } from "./PopupState.ts";
import { SessionMap } from "./SessionMap.ts";

const sessions = new SessionMap(new ChromeSessionStore());
sessions.observe(new DiscardGuard(new ChromeDiscardControl()));

chrome.runtime.onMessage.addListener((message: ContentMessage | PopupStateRequest, sender, sendResponse) => {
    if (sender.id !== chrome.runtime.id) {
        return;
    }
    const tab = sender.tab;
    if (message?.type === "popup:get-state" && !tab) {
        void sessions.ready().then(() => sendResponse(getPopupState(sessions)));
        // the response is sent asynchronously
        return true;
    }
    if (tab?.id === undefined) {
        return;
    }
    if (message?.type === "page-hidden") {
        void sessions.removeTab(tab.id);
    } else if (message?.type === "session") {
        const announcement = parseAnnouncement(message.announcement);
        if (announcement) {
            void sessions.announce({ tabId: tab.id, windowId: tab.windowId }, announcement);
        }
    }
});

chrome.tabs.onRemoved.addListener((tabId) => void sessions.removeTab(tabId));

chrome.tabs.onReplaced.addListener((_addedTabId, removedTabId) => void sessions.removeTab(removedTabId));
