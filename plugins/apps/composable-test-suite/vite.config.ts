import { defineConfig } from "vite";
import livePreview from "vite-live-preview";

// Builds the plugin sandbox entry (plugin.js) and the UI (index.html) into dist/,
// alongside the static manifest + icon copied from public/. The live-preview
// plugin serves the built dist/ (so the manifest's `plugin.js` resolves) and
// reloads on rebuild, giving a watch-build-serve dev loop via `pnpm start`.
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
        // 4202 is the conventional dev port shared by the plugins in this workspace
        port: 4202,
        cors: true,
    },
});
