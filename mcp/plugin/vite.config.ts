import { defineConfig } from "vite";
import livePreview from "vite-live-preview";

// Debug: Log the environment variables
console.log("MULTI_USER_MODE env:", process.env.MULTI_USER_MODE);
console.log("Will define IS_MULTI_USER_MODE as:", JSON.stringify(process.env.MULTI_USER_MODE === "true"));

const serverAddress = process.env.PENPOT_MCP_SERVER_ADDRESS || "localhost";
const websocketPort = process.env.PENPOT_MCP_WEBSOCKET_PORT || "4402";
const websocketUrl = `ws://${serverAddress}:${websocketPort}`;
console.log("Will define PENPOT_MCP_WEBSOCKET_URL as:", JSON.stringify(websocketUrl));

export default defineConfig({
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
        port: 4400,
        cors: true,
        allowedHosts: process.env.PENPOT_MCP_PLUGIN_SERVER_LISTEN_ADDRESS
            ? process.env.PENPOT_MCP_PLUGIN_SERVER_LISTEN_ADDRESS.split(",").map((h) => h.trim())
            : [],
    },
    define: {
        IS_MULTI_USER_MODE: JSON.stringify(process.env.MULTI_USER_MODE === "true"),
        PENPOT_MCP_WEBSOCKET_URL: JSON.stringify(websocketUrl),
    },
});
