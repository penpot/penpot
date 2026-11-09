import assert from "node:assert/strict";
import { access } from "node:fs/promises";
import test from "node:test";
import { BROWSERS, createManifest } from "../scripts/manifest.mjs";

const manifests = BROWSERS.map((browser) => createManifest(browser));
const [chrome, firefox] = manifests;

test("runs the background as a module service worker in Chrome and as an event page in Firefox", () => {
    assert.deepEqual(chrome.background, { service_worker: "background.js", type: "module" });
    assert.deepEqual(firefox.background, { scripts: ["background.js"] });
});

test("requests only storage", () => {
    for (const manifest of manifests) {
        assert.equal(manifest.manifest_version, 3);
        assert.deepEqual(manifest.permissions, ["storage"]);
        assert.equal(manifest.optional_permissions, undefined);
        assert.equal(manifest.host_permissions, undefined);
    }
});

test("gives the Firefox build an add-on ID and a minimum version", () => {
    assert.deepEqual(firefox.browser_specific_settings, {
        gecko: {
            id: "mcp-extension@penpot.app",
            strict_min_version: "140.0",
            data_collection_permissions: { required: ["none"] },
        },
    });
    assert.equal(chrome.browser_specific_settings, undefined);
});

test("references icons and a popup that exist in public/", async () => {
    for (const manifest of manifests) {
        const paths = [
            ...Object.values(manifest.icons as Record<string, string>),
            ...Object.values(manifest.action.default_icon as Record<string, string>),
            manifest.action.default_popup,
        ];
        for (const path of paths) {
            await access(new URL(`../public/${path}`, import.meta.url));
        }
    }
});
