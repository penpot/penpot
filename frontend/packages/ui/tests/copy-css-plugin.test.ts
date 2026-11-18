import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, expect, it } from "vitest";
import { copyCssPlugin } from "../vite.config.mts";

const temporaryDirectories: string[] = [];

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    rmSync(directory, { recursive: true, force: true });
  }
});

it("copies the generated CSS using paths based on the package directory", () => {
  const temporaryDirectory = mkdtempSync(
    path.join(os.tmpdir(), "penpot-ui-css-copy-"),
  );
  temporaryDirectories.push(temporaryDirectory);

  const packageDirectory = path.join(
    temporaryDirectory,
    "frontend",
    "packages",
    "ui",
  );
  const cssPath = path.join(packageDirectory, "dist", "ui.css");
  const destinationPath = path.join(
    temporaryDirectory,
    "frontend",
    "resources",
    "public",
    "css",
    "ui.css",
  );

  mkdirSync(path.dirname(cssPath), { recursive: true });
  writeFileSync(cssPath, ".ui { color: red; }");

  copyCssPlugin(packageDirectory).closeBundle();

  expect(readFileSync(destinationPath, "utf8")).toBe(".ui { color: red; }");
});
