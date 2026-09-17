#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { fileURLToPath, pathToFileURL } from "node:url";
import { randomUUID } from "node:crypto";
import { spawn, execFileSync } from "node:child_process";
import { parseArgs } from "node:util";
import { scenarios } from "../resources/wasm-playground/scenes/index.js";
import {
  collectCases,
  WEBGL_OPTIONS,
} from "../resources/wasm-playground/benchmarks/contract.js";
import {
  summarizeRun,
  formatSummary,
} from "../resources/wasm-playground/benchmarks/statistics.js";
import {
  compareRuns,
  formatComparison,
} from "./renderer-benchmarks/compare.js";

const frontend = fileURLToPath(new URL("../", import.meta.url));
const root = path.resolve(frontend, "..");
const LIMITS =
  "Renderer submission timings only: not GPU completion, displayed pixels, whole-editor responsiveness, or visual correctness.";

export function readOptions(argv) {
  const { values, positionals } = parseArgs({
    args: argv,
    allowPositionals: true,
    options: {
      help: { type: "boolean" },
      headed: { type: "boolean", default: false },
      diagnostic: { type: "boolean", default: false },
      "base-url": { type: "string", default: "http://localhost:3000" },
      output: { type: "string" },
      filter: { type: "string", default: "" },
      seed: { type: "string", default: "42" },
      warmups: { type: "string", default: "3" },
      repetitions: { type: "string", default: "10" },
      "timeout-ms": { type: "string", default: "30000" },
    },
  });
  const command = positionals[0] ?? "run";
  if (!["run", "compare"].includes(command))
    throw new Error(`Unknown command: ${command}`);
  const number = (name, minimum, maximum) => {
    const value = Number(values[name]);
    if (!Number.isSafeInteger(value) || value < minimum || value > maximum)
      throw new Error(`Invalid --${name}`);
    return value;
  };
  if (
    !values.help &&
    (command === "run" ? positionals.length > 1 : positionals.length !== 3)
  )
    throw new Error(
      "Use run [options] or compare baseline.json candidate.json [options]",
    );
  const baseUrl = new URL(values["base-url"]);
  if (
    !["http:", "https:"].includes(baseUrl.protocol) ||
    baseUrl.username ||
    baseUrl.password
  )
    throw new Error(
      "--base-url must be an HTTP(S) server URL without credentials",
    );
  return {
    command,
    files: positionals.slice(1),
    help: values.help,
    headed: values.headed,
    diagnostic: values.diagnostic,
    baseUrl: baseUrl.href,
    output: values.output,
    filter: values.filter,
    seed: number("seed", 0, 0xffffffff),
    warmups: number("warmups", 0, 1000),
    repetitions: number("repetitions", 1, 10000),
    timeoutMs: number("timeout-ms", 100, 600000),
  };
}

export async function bounded(operation, milliseconds, signal) {
  let timer;
  let onAbort;
  try {
    return await Promise.race([
      operation,
      new Promise((_, reject) => {
        timer = setTimeout(
          () =>
            reject(new Error(`Node watchdog expired after ${milliseconds} ms`)),
          milliseconds,
        );
      }),
      new Promise((_, reject) => {
        onAbort = () => reject(signal.reason ?? new Error("Interrupted"));
        if (signal?.aborted) onAbort();
        else signal?.addEventListener("abort", onAbort, { once: true });
      }),
    ]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", onAbort);
  }
}

async function save(output, result) {
  await fs.mkdir(path.dirname(output), { recursive: true });
  const temporary = `${output}.tmp`;
  await fs.writeFile(temporary, `${JSON.stringify(result, null, 2)}\n`);
  await fs.rename(temporary, output);
}

function execute(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: "inherit", ...options });
    child.once("error", reject);
    child.once("exit", (code, signal) =>
      code === 0
        ? resolve()
        : reject(new Error(`${command} failed (${signal ?? code})`)),
    );
  });
}

async function requireFile(filename, instruction) {
  try {
    await fs.access(filename);
  } catch {
    throw new Error(instruction);
  }
}

// Cargo metadata --no-deps reads the manifest without requiring unused targets'
// dependencies to be cached. Only local feature aliases need expansion here.
export function defaultFeatures(definitions) {
  const enabled = new Set();
  const visit = (name) => {
    if (enabled.has(name)) return;
    enabled.add(name);
    for (const child of definitions[name] ?? []) visit(child);
  };
  visit("default");
  if ([...enabled].some((name) => /^(stats|profile)(-|$)/.test(name)))
    throw new Error(
      "Default Cargo features enable renderer diagnostics; use a plain release manifest",
    );
  return [...enabled].sort();
}

async function preflight(options) {
  await requireFile(
    "/opt/emsdk/emsdk_env.sh",
    "Run inside the prepared Penpot development container (Emscripten is missing).",
  );
  await requireFile(
    path.join(root, "render-wasm/node_modules/esbuild/package.json"),
    "Prepare renderer dependencies with pnpm install in render-wasm first.",
  );
  const { chromium } = await import("playwright");
  await requireFile(
    chromium.executablePath(),
    "Chromium is missing; run the frontend scripts/setup preparation command first.",
  );
  try {
    const response = await fetch(options.baseUrl, {
      signal: AbortSignal.timeout(3000),
    });
    // A static root may legitimately be absent before assets have been copied.
    if (response.status >= 500) throw new Error(`HTTP ${response.status}`);
  } catch (error) {
    throw new Error(
      `Static server unavailable at ${options.baseUrl}; run node scripts/e2e-server.js from frontend first. ${error.message}`,
    );
  }
  return chromium;
}

export function recordAttempt(benchmarkCase, attempt, index, warmups) {
  if (attempt.environment) {
    benchmarkCase.environment ??= attempt.environment;
    if (
      JSON.stringify(benchmarkCase.environment) !==
      JSON.stringify(attempt.environment)
    ) {
      attempt.status = "failed";
      attempt.error = "Effective renderer environment changed within the case";
    }
  }
  benchmarkCase.attempts.push({ index, warmup: index < warmups, ...attempt });
  if (attempt.status !== "ok") benchmarkCase.status = "failed";
}

export function finishCases(cases, expected, status) {
  for (const c of cases) {
    c.unattempted = expected - c.attempts.length;
    if (c.status === "running") c.status = status;
  }
}

export async function run(options) {
  const abort = new AbortController();
  const interrupted = () => abort.abort(new Error("Benchmark interrupted"));
  process.once("SIGINT", interrupted);
  process.once("SIGTERM", interrupted);
  const runId = randomUUID();
  const output = path.resolve(
    options.output ??
      path.join(os.tmpdir(), `renderer-benchmarks-${runId}.json`),
  );
  const browserArgs = ["--enable-gpu"];
  const configuration = {
    viewport: { width: 1920, height: 1080 },
    dpr: 2,
    webglOptions: WEBGL_OPTIONS,
    rendererOptions: { debug: 0, browser: 1, backgroundArgb: 0xfffabada },
    warmups: options.warmups,
    timeoutMs: options.timeoutMs,
  };
  const git = (args) =>
    execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
  const result = {
    schemaVersion: 1,
    runId,
    timestamp: new Date().toISOString(),
    status: "running",
    scope: LIMITS,
    warnings: [],
    metadata: {
      git: {
        sha: git(["rev-parse", "HEAD"]),
        dirty: Boolean(git(["status", "--porcelain"])),
      },
      build: {
        mode: "release",
        featurePolicy: "manifest-defaults",
        features: null,
        target: "frontend",
        prepared: true,
      },
      environment: {
        node: process.version,
        os: os.platform(),
        osRelease: os.release(),
        arch: os.arch(),
        cpu: os.cpus()[0]?.model ?? "unknown",
        host: os.hostname(),
        headless: !options.headed,
        browserArgs,
      },
      configuration,
      baseUrl: options.baseUrl,
      serverReachable: false,
      masterSeed: options.seed,
      repetitions: options.repetitions,
      metricSemantics:
        "renderer-boundaries-v1;module-factory-includes-wasm-fetch;upload-includes-initial-view-index;finalization-includes-set-view-end",
    },
    cases: collectCases(scenarios, options.seed, options.filter),
  };
  let server;
  let browser;
  let context;
  let page;
  let pageErrors = [];
  let suiteStart;
  const closeContext = async () => {
    if (!context) return;
    const previous = context;
    const previousPage = page;
    context = null;
    page = null;
    try {
      if (previousPage)
        await bounded(
          previousPage.evaluate(() => globalThis.rendererBenchmark?.dispose()),
          2000,
        );
    } catch {
      /* A failed or blocked page still has to be closed. */
    }
    await bounded(previous.close(), 5000);
  };
  const openPage = async () => {
    context = await browser.newContext({
      viewport: configuration.viewport,
      deviceScaleFactor: configuration.dpr,
      serviceWorkers: "block",
    });
    page = await context.newPage();
    pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("crash", () => pageErrors.push("Browser page crashed"));
    await page.goto(
      new URL(`wasm-playground/benchmark.html?run=${runId}`, options.baseUrl)
        .href,
      { timeout: options.timeoutMs },
    );
    await page.waitForFunction(
      () => globalThis.rendererBenchmarkReady === true,
      null,
      { timeout: options.timeoutMs },
    );
  };
  try {
    if (result.metadata.git.dirty) {
      result.warnings.push(
        "Dirty worktree: local development evidence, not a reproducible canonical record.",
      );
      console.warn(result.warnings.at(-1));
    }
    const chromium = await preflight(options);
    result.metadata.serverReachable = true;
    const packageJson = JSON.parse(
      await fs.readFile(
        new URL("../node_modules/playwright/package.json", import.meta.url),
        "utf8",
      ),
    );
    result.metadata.environment.playwright = packageJson.version;
    const manifest = JSON.parse(
      execFileSync(
        "cargo",
        [
          "metadata",
          "--offline",
          "--locked",
          "--no-deps",
          "--format-version",
          "1",
        ],
        { cwd: path.join(root, "render-wasm"), encoding: "utf8" },
      ),
    );
    const renderer = manifest.packages.find(
      (item) =>
        item.manifest_path === path.join(root, "render-wasm/Cargo.toml"),
    );
    if (!renderer)
      throw new Error("Renderer package missing from Cargo metadata");
    result.metadata.build.features = defaultFeatures(renderer.features);
    // Disallow package-manager network fallback in a prepared run.
    const buildStart = performance.now();
    await execute("./build", ["frontend", "--offline", "--locked"], {
      cwd: path.join(root, "render-wasm"),
      signal: abort.signal,
      env: {
        ...process.env,
        PENPOT_WASM_PREPARED: "1",
        PENPOT_WASM_FUNCTION_NAMES: "0",
        BUILD_MODE: "release",
        NODE_ENV: "production",
        RENDER_TARGET: "frontend",
        BUILD_NAME: "render-wasm-benchmark",
        CARGO_BUILD_TARGET: "wasm32-unknown-emscripten",
        COREPACK_ENABLE_NETWORK: "0",
        CARGO_NET_OFFLINE: "true",
        VERSION: runId,
      },
    });
    result.metadata.build.durationMs = performance.now() - buildStart;
    const { copyWasmPlayground } = await import("./_helpers.js");
    const previousCwd = process.cwd();
    try {
      process.chdir(frontend);
      await copyWasmPlayground();
    } finally {
      process.chdir(previousCwd);
    }
    const markerName = "benchmark-run.json";
    await fs.writeFile(
      path.join(frontend, "resources/public/wasm-playground", markerName),
      JSON.stringify({ runId }),
    );
    const marker = await fetch(
      new URL(`wasm-playground/${markerName}?run=${runId}`, options.baseUrl),
      { signal: AbortSignal.timeout(3000), cache: "no-store" },
    );
    if (!marker.ok || (await marker.json()).runId !== runId)
      throw new Error(
        "The static server does not serve this workspace's current benchmark assets",
      );
    suiteStart = performance.now();
    server = await chromium.launchServer({
      headless: !options.headed,
      args: browserArgs,
    });
    browser = await chromium.connect(server.wsEndpoint());
    result.metadata.environment.chromium = browser.version();
    const attemptOptions = {
      ...configuration,
      moduleUrl: new URL(
        `js/render-wasm-benchmark.js?run=${runId}`,
        options.baseUrl,
      ).href,
    };
    for (const benchmarkCase of result.cases) {
      if (abort.signal.aborted) throw abort.signal.reason;
      console.log(`Running ${benchmarkCase.id}`);
      benchmarkCase.status = "running";
      for (
        let index = 0;
        index < options.warmups + options.repetitions;
        index++
      ) {
        let attempt;
        try {
          if (!page) await bounded(openPage(), options.timeoutMs, abort.signal);
          attempt = await bounded(
            page.evaluate(
              ([c, o]) => globalThis.rendererBenchmark.runAttempt(c, o),
              [benchmarkCase, attemptOptions],
            ),
            options.timeoutMs * 2 + 5000,
            abort.signal,
          );
        } catch (error) {
          attempt = {
            status: "failed",
            metrics: {},
            invalidMetrics: {},
            error: String(error.stack ?? error),
            slices: [],
            cachedSlices: [],
          };
        }
        if (pageErrors.length) {
          attempt.status = "failed";
          attempt.error = [attempt.error, ...pageErrors]
            .filter(Boolean)
            .join("\n");
        }
        recordAttempt(benchmarkCase, attempt, index, options.warmups);
        if (attempt.environment) {
          if (attempt.environment.webgl.acceleration === "software") {
            const warning =
              "Software WebGL: compare only equivalent renderer environments.";
            if (!result.warnings.includes(warning)) {
              result.warnings.push(warning);
              console.warn(warning);
            }
          }
        }
        if (attempt.status !== "ok") benchmarkCase.status = "failed";
        // Persist only outside browser timing; every failed attempt remains visible.
        await save(output, result);
        if (abort.signal.aborted) {
          benchmarkCase.status = "interrupted";
          throw abort.signal.reason;
        }
        if (!browser.isConnected())
          throw new Error(
            "Browser disconnected; remaining cases were not attempted",
          );
        if (attempt.status === "failed") {
          // Cache history cannot be restored after failure: stop this case, then
          // continue independent cases. Do not silently replace failed samples.
          benchmarkCase.unattempted =
            options.warmups + options.repetitions - index - 1;
          await closeContext();
          break;
        }
        if (benchmarkCase.group === "fresh-module-context")
          await closeContext();
      }
      await closeContext();
      if (benchmarkCase.status === "running") benchmarkCase.status = "complete";
    }
    result.status = result.cases.every((c) => c.status === "complete")
      ? "complete"
      : "failed";
  } catch (error) {
    result.status = abort.signal.aborted ? "interrupted" : "failed";
    result.error = String(error.stack ?? error);
    console.error(result.error);
  } finally {
    finishCases(
      result.cases,
      options.warmups + options.repetitions,
      result.status,
    );
    if (suiteStart !== undefined)
      result.metadata.suiteDurationMs = performance.now() - suiteStart;
    result.summary = summarizeRun(result);
    // Render a report once, after every scored case. Report work never separates
    // attempts or cases. The same data also goes to JSON and the terminal.
    if (browser?.isConnected() && !abort.signal.aborted) {
      try {
        if (!page) await bounded(openPage(), 5000);
        await bounded(
          page.evaluate(
            (value) => globalThis.rendererBenchmark.showResult(value),
            result,
          ),
          5000,
        );
      } catch (error) {
        result.warnings.push(`Page report unavailable: ${error.message}`);
      }
    }
    try {
      await closeContext();
    } catch {
      /* Browser cleanup below also closes blocked pages. */
    }
    // The browser server exposes its child process for a bounded hard cleanup.
    try {
      if (browser) await bounded(browser.close(), 5000);
    } catch {
      server?.process()?.kill("SIGKILL");
    }
    try {
      if (server) await bounded(server.close(), 5000);
    } catch {
      server?.process()?.kill("SIGKILL");
    }
    process.removeListener("SIGINT", interrupted);
    process.removeListener("SIGTERM", interrupted);
    result.finishedAt = new Date().toISOString();
    await save(output, result);
  }
  console.log(LIMITS);
  console.log(formatSummary(result.summary));
  console.log(`Result: ${output}`);
  return result.status === "complete" ? 0 : 1;
}

export async function main(argv = process.argv.slice(2)) {
  const options = readOptions(argv);
  if (options.help) {
    console.log(
      "Renderer benchmarks\n  node scripts/renderer-benchmarks.js run [--filter ID] [--seed N] [--warmups N] [--repetitions N] [--timeout-ms N] [--headed] [--base-url URL] [--output FILE]\n  node scripts/renderer-benchmarks.js compare BASELINE CANDIDATE [--diagnostic] [--output FILE]\nRun requires a prepared development container and an existing static server on port 3000. Defaults: 3 warmups, 10 measured attempts, 30s timeout; provisional pending calibration.\n" +
        LIMITS,
    );
    return 0;
  }
  if (options.command === "compare") {
    const results = await Promise.all(
      options.files.map(async (file) =>
        JSON.parse(await fs.readFile(file, "utf8")),
      ),
    );
    const comparison = compareRuns(...results, {
      diagnostic: options.diagnostic,
    });
    console.log(LIMITS);
    console.log(formatComparison(comparison));
    if (options.output) await save(path.resolve(options.output), comparison);
    return 0;
  }
  return run(options);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
