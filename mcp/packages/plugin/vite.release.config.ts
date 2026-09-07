import { defineConfig, mergeConfig } from "vite";
import baseConfig from "./vite.config.ts";

export default mergeConfig(
    baseConfig,
    defineConfig({
        plugins: [],
    })
);
