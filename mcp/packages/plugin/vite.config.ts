import { defineConfig } from "vite";
import livePreview from "vite-live-preview";
import { createRequire } from "module";

const require = createRequire(import.meta.url);
const rootPkg = require("../../package.json");

let WS_URI = process.env.WS_URI || "http://localhost:4402";
let SERVER_HOST = process.env.PENPOT_MCP_PLUGIN_SERVER_HOST ?? "localhost";
let MCP_VERSION = JSON.stringify(rootPkg.version);

console.log("PENPOT_MCP_WEBSOCKET_URL:", JSON.stringify(WS_URI));
console.log("PENPOT_MCP_VERSION:", MCP_VERSION);

export default defineConfig({
    base: "./",
    plugins: [
        livePreview({
            reload: true,
            config: {
                build: {
                    sourcemap: true,
                },
            },
        }),
    ],
    build: {
        rollupOptions: {
            input: {
                plugin: "src/plugin.ts",
                index: "./index.html",
            },
            output: {
                entryFileNames: "[name].js",
            },
        },
    },
    preview: {
        host: SERVER_HOST,
        port: 4400,
        cors: true,
        allowedHosts: [],
    },
    define: {
        PENPOT_MCP_WEBSOCKET_URL: JSON.stringify(WS_URI),
        PENPOT_MCP_VERSION: MCP_VERSION,
    },
});
