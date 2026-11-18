// Runs the compiled unit test bundle with V8 coverage enabled and writes
// Istanbul reports mapped back to the ClojureScript sources through the
// source maps of the shadow-cljs test build.
//
// Expects the test bundle to be built (`pnpm run build:test`). Extra
// arguments are passed to the test runner. Exits with the status of the
// tests. Reports land in `coverage/`, with `unloaded.txt` listing the
// namespaces in scope that no test loads (`scripts/coverage-summary.mjs`
// renders both as markdown).

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { parse } from "acorn";
import { convert } from "ast-v8-to-istanbul";
import { createCoverageMap } from "@vitest/istanbul-lib-coverage";
import { create, createContext } from "@vitest/istanbul-lib-report";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const REPO_ROOT = path.resolve(ROOT, "..");
const TEST_BUNDLE = path.join(ROOT, "target/tests/test.js");
const RUNTIME_DIR = path.join(ROOT, "target/tests/cljs-runtime");
const RAW_DIR = path.join(ROOT, "target/coverage-raw");
const REPORT_DIR = path.join(ROOT, "coverage");
const SOURCE_DIR = path.join(ROOT, "src");

// UI components are left out: coverage measures the logic namespaces.
const EXCLUDED_DIRS = ["app/main/ui/"];

function inScope(relPath) {
  return (
    /^app\/.*\.clj[sc]$/.test(relPath) &&
    !EXCLUDED_DIRS.some((dir) => relPath.startsWith(dir))
  );
}

function runTests(args) {
  fs.rmSync(RAW_DIR, { recursive: true, force: true });
  const result = spawnSync("node", [TEST_BUNDLE, ...args], {
    stdio: "inherit",
    env: { ...process.env, NODE_V8_COVERAGE: RAW_DIR },
  });
  return result.status ?? 1;
}

// Resolves the sources of a source map to absolute paths under `src/`;
// returns null when the map has no source in scope.
function resolveSources(sourceMap) {
  let anyInScope = false;
  const sources = sourceMap.sources.map((source) => {
    const relPath = path.join(sourceMap.sourceRoot ?? "", source);
    if (inScope(relPath) && fs.existsSync(path.join(SOURCE_DIR, relPath))) {
      anyInScope = true;
      return path.join(SOURCE_DIR, relPath);
    }
    return source;
  });
  return anyInScope ? { ...sourceMap, sources, sourceRoot: "" } : null;
}

async function collectCoverage() {
  const coverageMap = createCoverageMap({});
  const runtimeUrl = pathToFileURL(RUNTIME_DIR).href + "/";

  for (const rawFile of fs.readdirSync(RAW_DIR)) {
    const raw = JSON.parse(
      fs.readFileSync(path.join(RAW_DIR, rawFile), "utf8"),
    );

    for (const script of raw.result) {
      if (!script.url.startsWith(runtimeUrl)) continue;

      const file = fileURLToPath(script.url);
      const mapFile = file + ".map";
      if (!fs.existsSync(mapFile)) continue;

      const sourceMap = resolveSources(
        JSON.parse(fs.readFileSync(mapFile, "utf8")),
      );
      if (!sourceMap) continue;

      const code = fs.readFileSync(file, "utf8");
      const data = await convert({
        ast: parse(code, { ecmaVersion: "latest", sourceType: "module" }),
        code,
        wrapperLength: 0,
        coverage: { url: script.url, functions: script.functions },
        sourceMap,
      });

      for (const [sourcePath, fileCoverage] of Object.entries(data)) {
        if (inScope(path.relative(SOURCE_DIR, sourcePath))) {
          coverageMap.merge({ [sourcePath]: fileCoverage });
        }
      }
    }
  }
  return coverageMap;
}

function listScopeFiles(dir = path.join(SOURCE_DIR, "app")) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) return listScopeFiles(fullPath);
    return inScope(path.relative(SOURCE_DIR, fullPath)) ? [fullPath] : [];
  });
}

function writeReports(coverageMap) {
  fs.rmSync(REPORT_DIR, { recursive: true, force: true });
  const context = createContext({
    dir: REPORT_DIR,
    coverageMap,
    defaultSummarizer: "nested",
  });
  create("lcovonly", { projectRoot: REPO_ROOT }).execute(context);
  create("json-summary").execute(context);
  create("html", { subdir: "html" }).execute(context);
  create("text-summary").execute(context);
}

const testStatus = runTests(process.argv.slice(2));

const coverageMap = await collectCoverage();
const loaded = new Set(coverageMap.files());
const unloaded = listScopeFiles()
  .filter((file) => !loaded.has(file))
  .sort();

writeReports(coverageMap);
fs.writeFileSync(
  path.join(REPORT_DIR, "unloaded.txt"),
  unloaded.map((file) => path.relative(REPO_ROOT, file) + "\n").join(""),
);
fs.rmSync(RAW_DIR, { recursive: true, force: true });

process.exit(testStatus);
