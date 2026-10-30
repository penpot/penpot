/** Browsers the extension is built for, one directory of `dist/` each. */
export const BROWSERS = ["chrome", "firefox"];

const ICONS = {
    16: "icons/icon-16.png",
    32: "icons/icon-32.png",
    48: "icons/icon-48.png",
    128: "icons/icon-128.png",
};

const CONTENT_SCRIPTS = [
    {
        matches: ["https://design.penpot.app/*", "https://*.penpot.dev/*", "https://localhost/*", "http://localhost/*"],
        js: ["content.js"],
        run_at: "document_idle",
    },
];

/**
 * Creates the extension manifest for a browser.
 *
 * Chrome runs the background as a module service worker. Firefox runs it as an event page and
 * needs an add-on ID.
 *
 * @param {"chrome" | "firefox"} browser - The browser to build for
 */
export function createManifest(browser) {
    const common = {
        manifest_version: 3,
        name: "Penpot MCP",
        version: "0.1.0",
        description: "Keeps the browser from discarding Penpot tabs connected to MCP.",
        icons: ICONS,
        permissions: ["storage"],
        action: {
            default_title: "Penpot MCP",
            default_popup: "popup.html",
            default_icon: { 16: ICONS[16], 32: ICONS[32] },
        },
        content_scripts: CONTENT_SCRIPTS,
    };
    if (browser === "chrome") {
        return {
            ...common,
            minimum_chrome_version: "116",
            background: { service_worker: "background.js", type: "module" },
        };
    }
    return {
        ...common,
        background: { scripts: ["background.js"] },
        browser_specific_settings: {
            gecko: {
                id: "mcp-extension@penpot.app",
                // the first version that knows data_collection_permissions
                strict_min_version: "140.0",
                data_collection_permissions: { required: ["none"] },
            },
        },
    };
}
