import { cpSync } from "fs";

// copy static assets and data to dist
cpSync("src/static", "dist/static", { recursive: true });
cpSync("data", "dist/data", { recursive: true });
