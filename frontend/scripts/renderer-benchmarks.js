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
  tagExpectedFeatureDiff,
} from "./renderer-benchmarks/compare.js";

const frontend = fileURLToPath(new URL("../", import.meta.url));
const root = path.resolve(frontend, "..");
const LIMITS =
  "Renderer submission timings only: not GPU completion, displayed pixels, whole-editor responsiveness, or visual correctness.";

// Runner-controlled build overrides shared by run and ab. VERSION stays out:
// every run stamps its own runId as VERSION in the child environment.
export const BUILD_ENV = {
  PENPOT_WASM_PREPARED: "1",
  PENPOT_WASM_FUNCTION_NAMES: "0",
  BUILD_MODE: "release",
  NODE_ENV: "production",
  RENDER_TARGET: "frontend",
  BUILD_NAME: "render-wasm-benchmark",
  CARGO_BUILD_TARGET: "wasm32-unknown-emscripten",
  COREPACK_ENABLE_NETWORK: "0",
  CARGO_NET_OFFLINE: "true",
};

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
      features: { type: "string", default: "" },
      "max-features": { type: "string", default: "3" },
      confirm: { type: "boolean", default: false },
      "dry-run": { type: "boolean", default: false },
      "no-build": { type: "boolean", default: false },
    },
  });
  const command = positionals[0] ?? "run";
  if (!["run", "compare", "ab"].includes(command))
    throw new Error(`Unknown command: ${command}`);
  const number = (name, minimum, maximum) => {
    const value = Number(values[name]);
    if (!Number.isSafeInteger(value) || value < minimum || value > maximum)
      throw new Error(`Invalid --${name}`);
    return value;
  };
  if (
    !values.help &&
    (command === "compare" ? positionals.length !== 3 : positionals.length > 1)
  )
    throw new Error(
      "Use run [options], ab --features a,b [options], or compare baseline.json candidate.json [options]",
    );
  const featureList = values.features
    .split(",")
    .map((token) => token.trim())
    .filter(Boolean);
  for (const token of featureList) {
    if (!/^[a-z0-9-_]+$/.test(token))
      throw new Error(`Invalid --features token: ${token}`);
  }
  const features = [...new Set(featureList)].sort();
  const maxFeatures = number("max-features", 1, 6);
  if (command !== "ab" && values["dry-run"])
    throw new Error("--dry-run is only valid for ab");
  if (values["no-build"] && command !== "run")
    throw new Error("--no-build is only valid for run");
  if (values["no-build"] && features.length)
    throw new Error("--no-build skips the build, so --features cannot apply");
  if (command === "ab" && !values.help) {
    if (features.some((name) => /^(stats|profile)(-|$)/.test(name)))
      throw new Error(
        "ab rejects renderer diagnostic features; record them with run --features instead",
      );
    if (features.length < 1)
      throw new Error("ab requires --features with at least one feature");
    if (features.length > maxFeatures)
      throw new Error(
        `ab requests ${features.length} features but --max-features is ${maxFeatures}`,
      );
  }
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
    features,
    maxFeatures,
    confirm: values.confirm,
    dryRun: values["dry-run"],
    noBuild: values["no-build"],
  };
}

// All on/off configurations of sorted unique feature names in binary-reflected
// Gray order, plus the Boolean-lattice edges (pairs differing in exactly one
// feature). features[0] is the most significant bit. Pure: no I/O.
export function planFeatureMatrix(features) {
  const configurations = [];
  for (let index = 0; index < 2 ** features.length; index++) {
    const gray = index ^ (index >> 1);
    const slug = features
      .map((_, bit) => (gray & (1 << (features.length - 1 - bit)) ? "1" : "0"))
      .join("");
    configurations.push({
      slug,
      features: features.filter((_, bit) => slug[bit] === "1"),
    });
  }
  const edges = [];
  configurations.forEach((config) => {
    features.forEach((name, bit) => {
      if (config.slug[bit] === "0") {
        edges.push({
          from: config.slug,
          to: config.slug.slice(0, bit) + "1" + config.slug.slice(bit + 1),
          toggledFeature: name,
        });
      }
    });
  });
  return { configurations, edges };
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

// Reads the renderer package's feature definitions from the Cargo manifest
// without requiring unused targets' dependencies to be cached.
export function readRendererManifest() {
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
    (item) => item.manifest_path === path.join(root, "render-wasm/Cargo.toml"),
  );
  if (!renderer)
    throw new Error("Renderer package missing from Cargo metadata");
  return renderer.features;
}

// Validates requested explicit features against the manifest definitions.
// Returns the resolved manifest defaults (synthetic "default" node included).
// Pure: no I/O.
export function validateFeatureSelection(definitions, features, command) {
  const resolved = defaultFeatures(definitions);
  const enabled = resolved.filter((name) => name !== "default");
  for (const name of features) {
    if (!Object.hasOwn(definitions, name))
      throw new Error(`Unknown Cargo feature: ${name}`);
  }
  const overlap = features.filter((name) => enabled.includes(name));
  if (overlap.length)
    throw new Error(
      `Requested features already enabled by default: ${overlap.join(",")}`,
    );
  if (command === "ab" && enabled.length)
    throw new Error(
      `ab requires an empty default feature set; found: ${enabled.join(",")}`,
    );
  return resolved;
}

const AMBIENT_ENV_EXACT = new Set([
  "RUSTFLAGS",
  "RUSTC_WRAPPER",
  "CARGO_TARGET_DIR",
  "CARGO_HOME",
  "CC",
  "CFLAGS",
  "CXXFLAGS",
  "LDFLAGS",
  "PENPOT_WASM_FUNCTION_NAMES",
]);

// Records the ambient build environment allowlist. Never the full process
// environment. Pure: no I/O.
export function recordAmbientEnv(env = process.env) {
  const recorded = {};
  for (const [key, value] of Object.entries(env)) {
    if (
      AMBIENT_ENV_EXACT.has(key) ||
      key.startsWith("CARGO_PROFILE_") ||
      key.startsWith("EMSDK")
    ) {
      recorded[key] = String(value);
    }
  }
  return recorded;
}

async function preflight(options) {
  if (options.noBuild) {
    for (const artifact of [
      "render-wasm-benchmark.js",
      "render-wasm-benchmark.wasm",
    ])
      await requireFile(
        path.join(frontend, "resources/public/js", artifact),
        "No existing renderer benchmark build; run once without --no-build first.",
      );
  } else {
    await requireFile(
      "/opt/emsdk/emsdk_env.sh",
      "Run inside the prepared Penpot development container (Emscripten is missing).",
    );
    await requireFile(
      path.join(root, "render-wasm/node_modules/esbuild/package.json"),
      "Prepare renderer dependencies with pnpm install in render-wasm first.",
    );
  }
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
    schemaVersion: 2,
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
        built: !options.noBuild,
        featurePolicy: "manifest-defaults",
        features: [],
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
    if (options.noBuild) {
      const warning =
        "Build skipped (--no-build): the measured artifact was not rebuilt from this revision.";
      result.warnings.push(warning);
      console.warn(warning);
    } else {
      const requested = [...(options.features ?? [])].sort();
      const resolved = validateFeatureSelection(
        readRendererManifest(),
        requested,
        options.command,
      );
      result.metadata.build.features = requested;
      result.metadata.build.defaultFeatures = resolved;
      if (requested.length) result.metadata.build.featurePolicy = "explicit";
      result.metadata.build.env = { ...BUILD_ENV };
      result.metadata.build.ambientEnv = recordAmbientEnv();
      // Disallow package-manager network fallback in a prepared run.
      const buildStart = performance.now();
      await execute(
        "./build",
        [
          "frontend",
          "--offline",
          "--locked",
          ...(requested.length ? ["--features", requested.join(",")] : []),
        ],
        {
          cwd: path.join(root, "render-wasm"),
          signal: abort.signal,
          env: { ...process.env, ...BUILD_ENV, VERSION: runId },
        },
      );
      result.metadata.build.durationMs = performance.now() - buildStart;
    }
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

// Classifies one configuration output for the feature-matrix loop: whether the
// matrix continues with the next configuration or stops and marks the rest
// unattempted. Takes the parsed config JSON, or null when the file is missing
// or unparseable. Pure: no I/O.
export function classifyConfigResult(result) {
  if (result === null || result === undefined)
    return {
      outcome: "stop",
      status: "unattempted",
      error: "configuration output is missing or invalid",
    };
  if (result.status === "interrupted")
    return { outcome: "stop", status: "interrupted" };
  if (typeof result.metadata?.build?.durationMs !== "number")
    return {
      outcome: "stop",
      status: "failed",
      error: "build or preflight failed before any suite ran",
    };
  const attempts = (result.cases ?? []).reduce(
    (sum, benchmarkCase) => sum + (benchmarkCase.attempts?.length ?? 0),
    0,
  );
  if (attempts === 0)
    return {
      outcome: "stop",
      status: "failed",
      error: "suite recorded no attempts",
    };
  return {
    outcome: "continue",
    status: result.status === "complete" ? "complete" : "failed",
  };
}

// Refuses an existing feature-matrix output root: it must be absent, or an
// empty directory. Never deletes or overwrites previous matrix output.
async function ensureFreshAbOutputRoot(outputRoot) {
  let stat;
  try {
    stat = await fs.stat(outputRoot);
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (!stat.isDirectory())
    throw new Error(`ab output root already exists: ${outputRoot}`);
  if ((await fs.readdir(outputRoot)).length)
    throw new Error(
      `ab output root already exists and is not empty: ${outputRoot}`,
    );
}

// Builds and runs every on/off configuration of the requested features, then
// compares the Boolean-lattice edges. Each configuration runs in Gray order
// with its own build and browser. Diagnostic tooling only: exit 0 reports
// that every edge has a comparison, never a performance verdict. An
// interruption between configurations or edges still writes matrix.json and
// exits 1. Callers may inject an AbortSignal; otherwise ab watches SIGINT and
// SIGTERM itself.
export async function ab(
  options,
  {
    runFn = run,
    compareFn = compareRuns,
    manifestFn = readRendererManifest,
    signal = null,
  } = {},
) {
  const abId = randomUUID();
  const outputRoot = options.output
    ? path.resolve(options.output)
    : path.join(
        os.tmpdir(),
        `renderer-ab-${new Date().toISOString().replaceAll(":", "-")}-${abId.slice(0, 8)}`,
      );
  await ensureFreshAbOutputRoot(outputRoot);
  const definitions = await manifestFn();
  validateFeatureSelection(definitions, options.features, "ab");
  const { configurations, edges } = planFeatureMatrix(options.features);
  const cases = collectCases(scenarios, options.seed, options.filter);
  const attemptsPerCase = options.warmups + options.repetitions;
  const plan = [
    `Feature matrix: ${options.features.join(",")}`,
    ...configurations.map(
      (config) => `  ${config.slug}: [${config.features.join(",")}]`,
    ),
    ...edges.map(
      (edge) => `  ${edge.from}__${edge.to}: ${edge.toggledFeature}`,
    ),
    `${cases.length} cases, ${attemptsPerCase} attempts per case, one browser per configuration.`,
  ].join("\n");
  console.log(plan);
  if (options.dryRun) return 0;
  if ((configurations.length > 8 || options.filter === "") && !options.confirm)
    throw new Error(
      "ab plans more work than confirmed; rerun with --confirm to proceed",
    );
  let watched = signal;
  let releaseSignal = null;
  if (watched === null) {
    const abort = new AbortController();
    watched = abort.signal;
    const interrupt = () => abort.abort(new Error("Benchmark interrupted"));
    process.once("SIGINT", interrupt);
    process.once("SIGTERM", interrupt);
    releaseSignal = () => {
      process.removeListener("SIGINT", interrupt);
      process.removeListener("SIGTERM", interrupt);
    };
  }
  await fs.mkdir(path.join(outputRoot, "configs"), { recursive: true });
  await fs.mkdir(path.join(outputRoot, "comparisons"), { recursive: true });
  const parsed = new Map();
  const configurationRecords = [];
  let stopped = false;
  const unattemptedRecord = (config) => ({
    slug: config.slug,
    features: config.features,
    status: "unattempted",
    file: path.join(outputRoot, "configs", `${config.slug}.json`),
    runId: null,
    runStatus: "unattempted",
  });
  for (const config of configurations) {
    if (watched.aborted) {
      configurationRecords.push({
        ...unattemptedRecord(config),
        error: "Benchmark interrupted",
      });
      continue;
    }
    if (stopped) {
      configurationRecords.push(unattemptedRecord(config));
      continue;
    }
    const configPath = path.join(outputRoot, "configs", `${config.slug}.json`);
    await runFn({
      ...options,
      command: "ab",
      features: config.features,
      output: configPath,
    });
    let result = null;
    try {
      result = JSON.parse(await fs.readFile(configPath, "utf8"));
    } catch {
      result = null;
    }
    const verdict = classifyConfigResult(result);
    if (result !== null) parsed.set(config.slug, result);
    configurationRecords.push({
      slug: config.slug,
      features: config.features,
      status: verdict.status,
      file: configPath,
      runId: result?.runId ?? null,
      runStatus: result?.status ?? "unattempted",
      ...(verdict.error ? { error: verdict.error } : {}),
    });
    console.log(`${config.slug}: ${verdict.status}`);
    if (verdict.outcome === "stop") stopped = true;
  }
  const comparisonRecords = [];
  for (const edge of edges) {
    if (watched.aborted) {
      comparisonRecords.push({ ...edge, status: "not-attempted", file: null });
      continue;
    }
    const from = parsed.get(edge.from);
    const to = parsed.get(edge.to);
    if (!from || !to) {
      comparisonRecords.push({ ...edge, status: "not-attempted", file: null });
      continue;
    }
    const comparison = tagExpectedFeatureDiff(
      await compareFn(from, to, { diagnostic: true }),
      edge.toggledFeature,
    );
    // An edge is controlled when the intended feature toggle is the only
    // mismatch. Anything else (environment, seeds, manifest defaults shifting
    // mid-matrix) keeps the strong diagnostic-only reading.
    const controlled =
      comparison.mismatches?.every((entry) => entry.expected === true) ?? false;
    const comparisonPath = path.join(
      outputRoot,
      "comparisons",
      `${edge.from}__${edge.to}.json`,
    );
    await save(comparisonPath, { ...comparison, edge, controlled });
    console.log(`${edge.from}__${edge.to}: ${comparisonPath}`);
    if (!controlled)
      console.warn(
        `${edge.from}__${edge.to}: unexpected mismatches beyond the intended feature toggle`,
      );
    comparisonRecords.push({
      ...edge,
      status: "compared",
      file: comparisonPath,
      compatible: comparison.compatible,
      diagnostic: comparison.diagnostic,
      controlled,
    });
  }
  const status =
    configurationRecords.every((record) =>
      ["complete", "failed"].includes(record.status),
    ) && comparisonRecords.every((record) => record.status === "compared")
      ? "complete"
      : "failed";
  await save(path.join(outputRoot, "matrix.json"), {
    schemaVersion: 1,
    abId,
    timestamp: new Date().toISOString(),
    features: options.features,
    maxFeatures: options.maxFeatures,
    filter: options.filter,
    outputRoot,
    configurations: configurationRecords,
    comparisons: comparisonRecords,
    status,
    warnings: [LIMITS],
  });
  console.log(`Matrix: ${path.join(outputRoot, "matrix.json")}`);
  // ab runs once per process; release before the single return. An unexpected
  // throw past this point exits through main's handler with the listeners.
  releaseSignal?.();
  return status === "complete" ? 0 : 1;
}

export async function main(argv = process.argv.slice(2)) {
  const options = readOptions(argv);
  if (options.help) {
    console.log(
      "Renderer benchmarks\n  node scripts/renderer-benchmarks.js run [--filter ID] [--seed N] [--warmups N] [--repetitions N] [--timeout-ms N] [--headed] [--base-url URL] [--features a,b] [--output FILE] [--no-build]\n  node scripts/renderer-benchmarks.js ab --features a,b [--max-features N] [--confirm] [--dry-run] [--filter ID] [--seed N] [--warmups N] [--repetitions N] [--timeout-ms N] [--headed] [--base-url URL] [--output DIR]\n  node scripts/renderer-benchmarks.js compare BASELINE CANDIDATE [--diagnostic] [--output FILE]\nRun requires a prepared development container and an existing static server on port 3000. --no-build reuses the existing renderer artifact instead of building and cannot be combined with --features or ab. Defaults: 3 warmups, 10 measured attempts, 30s timeout; provisional pending calibration.\n" +
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
  if (options.command === "ab") return ab(options);
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
