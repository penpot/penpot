import { defineConfig } from "vite";
import livePreview from "vite-live-preview";

let WS_URI = process.env.WS_URI || "http://localhost:4402";

console.log("Will define PENPOT_MCP_WEBSOCKET_URL as:", JSON.stringify(WS_URI));

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
        host: "0.0.0.0",
        port: 4400,
        cors: true,
        allowedHosts: [],
    },
    define: {
        PENPOT_MCP_WEBSOCKET_URL: JSON.stringify(WS_URI),
    },
});
