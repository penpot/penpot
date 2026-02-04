import { defineConfig } from "vite";
import livePreview from "vite-live-preview";

// Debug: Log the environment variables
console.log("MULTI_USER_MODE env:", process.env.MULTI_USER_MODE);
console.log("Will define IS_MULTI_USER_MODE as:", JSON.stringify(process.env.MULTI_USER_MODE === "true"));

let WS_URI = "http://localhost:4402";
console.log("Will define PENPOT_MCP_WEBSOCKET_URL as:", JSON.stringify(WS_URI));

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
        host: "0.0.0.0",
        port: 4400,
        cors: true,
        allowedHosts: [],
    },
    define: {
        IS_MULTI_USER_MODE: JSON.stringify(process.env.MULTI_USER_MODE === "true"),
        PENPOT_MCP_WEBSOCKET_URL: JSON.stringify(WS_URI),
    },
});
