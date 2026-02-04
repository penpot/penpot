import { defineConfig, mergeConfig } from "vite";
import baseConfig from "./vite.config";

export default mergeConfig(
    baseConfig,
    defineConfig({
        base: "./",
        plugins: [],
    })
);
