import { build } from "esbuild";
import { cp, rm, writeFile } from "node:fs/promises";
import { BROWSERS, createManifest } from "./manifest.mjs";

const TARGETS = { chrome: "chrome116", firefox: "firefox140" };

await rm("dist", { recursive: true, force: true });
for (const browser of BROWSERS) {
    const outdir = `dist/${browser}`;
    const options = { bundle: true, target: TARGETS[browser], outdir, logLevel: "info" };
    // Chrome loads the service worker as a module, Firefox the event page as a classic script
    await build({ ...options, entryPoints: ["src/background.ts"], format: browser === "chrome" ? "esm" : "iife" });
    await build({ ...options, entryPoints: ["src/popup.ts"], format: "esm" });
    // content scripts load as classic scripts
    await build({ ...options, entryPoints: ["src/content.ts"], format: "iife" });
    await cp("public", outdir, { recursive: true });
    await writeFile(`${outdir}/manifest.json`, JSON.stringify(createManifest(browser), null, 4) + "\n");
}
