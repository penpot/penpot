import {
  cpSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
} from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { expect, it } from "vitest";

it("exports the built CSS and copies it to the app", () => {
  const packageDir = path.resolve(import.meta.dirname, "..");
  const frontendDir = path.resolve(packageDir, "../..");
  const temporaryDir = mkdtempSync(path.join(os.tmpdir(), "penpot-ui-build-"));
  const isolatedFrontend = path.join(temporaryDir, "frontend");
  const isolatedPackage = path.join(isolatedFrontend, "packages", "ui");

  try {
    mkdirSync(isolatedPackage, { recursive: true });
    for (const entry of [
      "src",
      "package.json",
      "vite.config.mts",
      "tsconfig.json",
      "tsconfig.lib.json",
      "tsconfig.spec.json",
      "tsconfig.storybook.json",
      ".babelrc",
    ]) {
      cpSync(path.join(packageDir, entry), path.join(isolatedPackage, entry), {
        recursive: true,
      });
    }
    symlinkSync(
      path.join(packageDir, "node_modules"),
      path.join(isolatedPackage, "node_modules"),
    );
    symlinkSync(
      path.join(frontendDir, "node_modules"),
      path.join(isolatedFrontend, "node_modules"),
    );
    symlinkSync(
      path.join(frontendDir, "src"),
      path.join(isolatedFrontend, "src"),
    );

    const build = spawnSync(
      process.execPath,
      [
        path.join(frontendDir, "node_modules/vite/bin/vite.js"),
        "build",
        "--configLoader",
        "runner",
      ],
      { cwd: isolatedPackage, encoding: "utf8" },
    );
    expect(build.status, `${build.stdout}\n${build.stderr}`).toBe(0);

    const resolve = createRequire(
      path.join(isolatedPackage, "package.json"),
    ).resolve;
    const exportedCss = resolve("@penpot/ui/style.css");
    const css = readFileSync(exportedCss, "utf8");
    const copiedCss = readFileSync(
      path.join(isolatedFrontend, "resources/public/css/ui.css"),
      "utf8",
    );
    expect(css.length).toBeGreaterThan(0);
    expect(copiedCss).toBe(css);
  } finally {
    rmSync(temporaryDir, { recursive: true, force: true });
  }
}, 120_000);
