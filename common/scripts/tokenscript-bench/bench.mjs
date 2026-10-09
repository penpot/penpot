#!/usr/bin/env node
// Compares token resolution performance of
//
//   ts-old    the TypeScript interpreter penpot shipped (@penpot/tokenscript),
//             fetched with `git archive` from the last commit that has it,
//             with the interpreter version pinned in that commit's lockfile
//   ts-local  optional: a local checkout of tokenscript-interpreter (--ts-local)
//   sd        StyleDictionary + sd-transforms, configured like penpot
//   cljs      the Clojure port (app.common.tokenscript), shadow-cljs release build
//   jvm       the Clojure port on the JVM
//
// Usage (from the repo root or anywhere):
//
//   node common/scripts/tokenscript-bench/bench.mjs [options]
//
// See --help. Results go to common/target/tokenscript-bench/<timestamp>/
// (results.json, report.md); the report is also printed.

import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { cpus, platform, release, totalmem } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { inputFixture, profiles, syntheticFixtures } from "./fixtures.mjs";
import { render } from "./report.mjs";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const commonDir = resolve(scriptDir, "../..");
const repoDir = resolve(commonDir, "..");
const frontendModules = join(repoDir, "frontend/node_modules");
const oldPackagePath = "frontend/packages/tokenscript";

const { values: opts } = parseArgs({
  options: {
    engines: { type: "string", default: "ts-old,sd,cljs,jvm" },
    profiles: { type: "string", default: Object.keys(profiles).join(",") },
    sizes: { type: "string", default: "100,500,1000,2500,5000,10000" },
    input: { type: "string", multiple: true, default: [] },
    ops: { type: "string", default: "build,update,parse" },
    "base-ref": { type: "string" },
    "ts-local": { type: "string" },
    "budget-ms": { type: "string", default: "2000" },
    "warmup-ms": { type: "string", default: "500" },
    "jvm-warmup-ms": { type: "string", default: "3000" },
    "min-runs": { type: "string", default: "5" },
    "max-runs": { type: "string", default: "200" },
    "timeout-ms": { type: "string", default: "900000" },
    "max-sd-size": { type: "string", default: "10000" },
    out: { type: "string" },
    baseline: { type: "string" },
    "skip-build": { type: "boolean", default: false },
    quick: { type: "boolean", default: false },
    help: { type: "boolean", default: false },
  },
});

if (opts.help) {
  console.log(`Usage: node common/scripts/tokenscript-bench/bench.mjs [options]

  --engines LIST       ts-old,ts-local,sd,cljs,jvm (default ts-old,sd,cljs,jvm;
                       ts-local is added when --ts-local is given)
  --profiles LIST      ${Object.keys(profiles).join(",")}
  --sizes LIST         token counts (default 100,500,1000,2500,5000,10000)
  --input NAME=FILE    extra fixture from real data: JSON object of
                       name -> {type, value}; repeatable
  --ops LIST           build,update,parse (default all; parse is the port's
                       uncached parsing of every value, other engines skip it)
  --base-ref REF       git ref with ${oldPackagePath} (default: auto)
  --ts-local DIR       tokenscript-interpreter checkout (needs dist/, npm run build:lib)
  --budget-ms N        sampling time per case (default 2000)
  --warmup-ms N        warmup per case on node (default 500)
  --jvm-warmup-ms N    warmup per case on the JVM (default 3000)
  --min-runs N         (default 5)   --max-runs N  (default 200)
  --max-sd-size N      skip StyleDictionary above this size (default 10000)
  --timeout-ms N       per engine process (default 900000)
  --out DIR            output directory
  --baseline FILE      results.json of an earlier run: reuses its fixtures,
                       keeps the engines that are not run again and keeps
                       the earlier results of re-run engines as "<engine>-before"
  --skip-build         reuse target/tokenscript-bench/engine.cjs
  --quick              sizes 100,1000, budget 500 ms, warmup 200 ms`);
  process.exit(0);
}

// --quick only replaces options that were not given explicitly.
if (opts.quick) {
  const given = (flag) =>
    process.argv.some((a) => a === `--${flag}` || a.startsWith(`--${flag}=`));
  const quick = {
    sizes: "100,1000",
    "budget-ms": "500",
    "warmup-ms": "200",
    "jvm-warmup-ms": "1500",
  };
  for (const [k, v] of Object.entries(quick)) if (!given(k)) opts[k] = v;
}

const list = (s) =>
  s
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean);
const int = (s) => Number.parseInt(s, 10);
const log = (...xs) => console.error("[bench]", ...xs);

let engines = list(opts.engines);
if (opts["ts-local"] && !engines.includes("ts-local"))
  engines.splice(engines.indexOf("ts-old") + 1, 0, "ts-local");

// Git helpers -------------------------------------------------------------------

const git = (args, options = {}) =>
  execFileSync("git", args, { cwd: repoDir, maxBuffer: 1 << 28, ...options });
const gitOk = (args) =>
  spawnSync("git", args, { cwd: repoDir, stdio: "ignore" }).status === 0;

function oldPackageRef() {
  if (opts["base-ref"]) return opts["base-ref"];
  if (gitOk(["cat-file", "-e", `HEAD:${oldPackagePath}/index.js`]))
    return "HEAD";
  const last = git([
    "rev-list",
    "-1",
    "HEAD",
    "--",
    `${oldPackagePath}/index.js`,
  ])
    .toString()
    .trim();
  if (!last)
    throw new Error(`no commit with ${oldPackagePath} found, pass --base-ref`);
  return `${last}^`;
}

// Resolves the ESM entry file of an installed package.
function packageEntry(dir) {
  const json = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
  let entry = json.exports?.["."] ?? json.exports ?? json.module ?? json.main;
  while (entry && typeof entry === "object")
    entry = entry.import ?? entry.default ?? entry.node;
  return join(dir, entry);
}

// Extracts the old @penpot/tokenscript package and installs the interpreter
// version its lockfile pinned. Cached per commit.
function setupOldPackage() {
  const ref = oldPackageRef();
  const sha = git(["rev-parse", "--short", ref]).toString().trim();
  const root = join(commonDir, "target/tokenscript-bench", `ts-old-${sha}`);
  const pkgDir = join(root, oldPackagePath);
  const interpreterDir = join(
    pkgDir,
    "node_modules/@tokens-studio/tokenscript-interpreter",
  );

  if (!existsSync(interpreterDir)) {
    log(`extracting ${oldPackagePath} from ${ref} (${sha})`);
    mkdirSync(root, { recursive: true });
    const archive = git(["archive", ref, oldPackagePath]);
    execFileSync("tar", ["-x", "-C", root], { input: archive });

    const pkgJsonPath = join(pkgDir, "package.json");
    const pkgJson = JSON.parse(readFileSync(pkgJsonPath, "utf8"));
    const range =
      pkgJson.dependencies["@tokens-studio/tokenscript-interpreter"];
    let version = range;
    try {
      const lock = git(["show", `${ref}:frontend/pnpm-lock.yaml`]).toString();
      version =
        lock.match(
          /@tokens-studio\/tokenscript-interpreter@(\d+\.\d+\.\d+[\w.-]*)/,
        )?.[1] ?? range;
    } catch {}
    // Install with npm; the package declares pnpm, which corepack would enforce.
    delete pkgJson.packageManager;
    writeFileSync(pkgJsonPath, JSON.stringify(pkgJson, null, 2));

    log(`installing @tokens-studio/tokenscript-interpreter@${version}`);
    execFileSync(
      "npm",
      [
        "install",
        "--no-audit",
        "--no-fund",
        "--no-package-lock",
        "--ignore-scripts",
        `@tokens-studio/tokenscript-interpreter@${version}`,
      ],
      { cwd: pkgDir, stdio: "inherit" },
    );
  }

  return {
    ref: `${ref} (${sha})`,
    interpreter: packageEntry(interpreterDir),
    schemas: join(pkgDir, "schemas.js"),
  };
}

// Build -----------------------------------------------------------------------

const cljsEngine = join(commonDir, "target/tokenscript-bench/engine.cjs");

function buildCljs() {
  if (opts["skip-build"] && existsSync(cljsEngine)) return;
  log("building the port (shadow-cljs release tokenscript-bench)");
  execFileSync(
    "clojure",
    [
      "-M:dev",
      "-m",
      "shadow.cljs.devtools.cli",
      "release",
      "tokenscript-bench",
    ],
    {
      cwd: commonDir,
      stdio: ["ignore", "inherit", "inherit"],
    },
  );
}

// Run -------------------------------------------------------------------------

function runProcess(cmd, args, cwd, label) {
  const t0 = Date.now();
  const res = spawnSync(cmd, args, {
    cwd,
    encoding: "utf8",
    timeout: int(opts["timeout-ms"]),
    maxBuffer: 1 << 26,
  });
  const secs = ((Date.now() - t0) / 1000).toFixed(1);
  if (res.error?.code === "ETIMEDOUT" || res.signal === "SIGTERM")
    return { ok: false, error: `timeout after ${secs}s` };
  if (res.status !== 0) {
    const tail = `${res.stderr ?? ""}${res.stdout ?? ""}`
      .trim()
      .split("\n")
      .slice(-15)
      .join("\n");
    log(`${label} failed (${secs}s):\n${tail}`);
    return {
      ok: false,
      error: tail.split("\n").at(-1) || `exit ${res.status}`,
    };
  }
  log(`${label} done (${secs}s)`);
  return { ok: true };
}

function main() {
  const outDir = resolve(
    opts.out ??
      join(
        commonDir,
        "target/tokenscript-bench",
        new Date().toISOString().replace(/[:.]/g, "-"),
      ),
  );
  const fixturesDir = join(outDir, "fixtures");
  const jobsDir = join(outDir, "jobs");
  mkdirSync(fixturesDir, { recursive: true });
  mkdirSync(jobsDir, { recursive: true });

  const needsOld = engines.includes("ts-old") || engines.includes("ts-local");
  const old = needsOld ? setupOldPackage() : null;
  if (engines.includes("cljs")) buildCljs();

  let tsLocal = null;
  if (engines.includes("ts-local")) {
    const dir = resolve(opts["ts-local"] ?? "");
    const entry = join(dir, "dist/lib/index.js");
    if (!existsSync(entry))
      throw new Error(
        `${entry} not found: run \`npm run build:lib\` in ${dir}`,
      );
    tsLocal = entry;
  }

  // Fixtures (from the baseline run when given, so both measure the same data)
  const baseline = opts.baseline
    ? JSON.parse(readFileSync(resolve(opts.baseline), "utf8"))
    : null;
  const fixtures = baseline
    ? baseline.meta.fixtures.map((id) =>
        JSON.parse(
          readFileSync(
            join(dirname(resolve(opts.baseline)), "fixtures", `${id}.json`),
            "utf8",
          ),
        ),
      )
    : syntheticFixtures(list(opts.profiles), list(opts.sizes).map(int));
  for (const spec of baseline ? [] : opts.input) {
    const [name, file] = spec.includes("=")
      ? spec.split("=")
      : [spec.replace(/.*\/|\.json$/g, ""), spec];
    fixtures.push(
      inputFixture(name, JSON.parse(readFileSync(resolve(file), "utf8"))),
    );
  }
  for (const f of fixtures)
    writeFileSync(join(fixturesDir, `${f.id}.json`), JSON.stringify(f));
  log(`${fixtures.length} fixtures in ${fixturesDir}`);

  const common = {
    fixturesDir,
    ops: list(opts.ops),
    budgetMs: int(opts["budget-ms"]),
    warmupMs: int(opts["warmup-ms"]),
    minRuns: int(opts["min-runs"]),
    maxRuns: int(opts["max-runs"]),
  };

  const supported = (engine, f) =>
    f.engines?.[engine] !== false &&
    !(engine === "sd" && f.size > int(opts["max-sd-size"]));

  const engineMeta = [];
  const results = [];
  const collect = (engine, outFile, ids) => {
    if (!existsSync(outFile)) return false;
    const data = JSON.parse(readFileSync(outFile, "utf8"));
    if (!engineMeta.some((e) => e.engine === engine)) {
      engineMeta.push({
        engine,
        version: data.version,
        runtime: data.runtime,
        initMs: data.initMs,
      });
    }
    results.push(...data.results.filter((r) => ids.includes(r.fixture)));
    return true;
  };
  const failed = (engine, f, op, error) =>
    results.push({
      engine,
      fixture: f.id,
      profile: f.profile,
      size: f.size,
      op,
      error,
    });

  for (const engine of engines) {
    const engineFixtures = fixtures.filter((f) => supported(engine, f));
    const extra =
      engine === "ts-old"
        ? { interpreter: old.interpreter, schemas: old.schemas }
        : engine === "ts-local"
          ? { interpreter: tsLocal, schemas: old.schemas }
          : engine === "sd"
            ? { nodeModules: frontendModules, ops: ["build"] }
            : {};

    if (engine === "jvm") {
      // One JVM for all fixtures: startup and class loading are too slow
      // for a process per fixture.
      const ids = engineFixtures.map((f) => f.id);
      const job = {
        ...common,
        engine,
        ids,
        warmupMs: int(opts["jvm-warmup-ms"]),
        out: join(jobsDir, "jvm.out.json"),
      };
      const jobFile = join(jobsDir, "jvm.json");
      writeFileSync(jobFile, JSON.stringify(job));
      log(`jvm: ${ids.length} fixtures`);
      const res = runProcess(
        "clojure",
        ["-J-Xmx4g", "-M:dev", "-m", "tokenscript-bench", jobFile],
        commonDir,
        "jvm",
      );
      if (!res.ok || !collect(engine, job.out, ids))
        for (const f of engineFixtures)
          failed(engine, f, "build", res.error ?? "no output");
      continue;
    }

    const script =
      engine === "cljs"
        ? cljsEngine
        : engine === "sd"
          ? join(scriptDir, "engine-sd.mjs")
          : join(scriptDir, "engine-ts.mjs");

    for (const f of engineFixtures) {
      const job = {
        ...common,
        ...extra,
        engine,
        ids: [f.id],
        out: join(jobsDir, `${engine}-${f.id}.out.json`),
      };
      const jobFile = join(jobsDir, `${engine}-${f.id}.json`);
      writeFileSync(jobFile, JSON.stringify(job));
      const res = runProcess(
        process.execPath,
        ["--expose-gc", "--max-old-space-size=8192", script, jobFile],
        commonDir,
        `${engine} ${f.id}`,
      );
      if (!res.ok || !collect(engine, job.out, [f.id]))
        failed(engine, f, "build", res.error ?? "no output");
    }
  }

  let commit = "?";
  try {
    commit = git(["rev-parse", "--short", "HEAD"]).toString().trim();
    if (
      git(["status", "--porcelain", "--untracked-files=no"]).toString().trim()
    )
      commit += " (with local changes)";
  } catch {}

  const current = engines.map(
    (e) =>
      engineMeta.find((m) => m.engine === e) ?? {
        engine: e,
        version: "?",
        runtime: "?",
      },
  );
  let allEngines = current;
  let allResults = results;
  if (baseline) {
    const rerun = new Set(engines);
    const rename = (e) => (rerun.has(e) ? `${e}-before` : e);
    allEngines = [];
    for (const e of baseline.engines) {
      if (rerun.has(e.engine)) {
        allEngines.push({ ...e, engine: rename(e.engine) });
        allEngines.push(current.find((c) => c.engine === e.engine));
      } else {
        allEngines.push(e);
      }
    }
    for (const c of current)
      if (!allEngines.some((e) => e.engine === c.engine)) allEngines.push(c);
    allResults = [
      ...baseline.results.map((r) => ({ ...r, engine: rename(r.engine) })),
      ...results,
    ];
  }

  const data = {
    meta: {
      date: new Date().toISOString(),
      commit,
      baseline: baseline
        ? { date: baseline.meta.date, commit: baseline.meta.commit }
        : undefined,
      oldRef: old?.ref ?? baseline?.meta.oldRef,
      cpu: cpus()[0]?.model?.trim(),
      cores: cpus().length,
      memoryGb: Math.round(totalmem() / 2 ** 30),
      platform: `${platform()} ${release()}`,
      options: {
        budgetMs: common.budgetMs,
        warmupMs: common.warmupMs,
        jvmWarmupMs: int(opts["jvm-warmup-ms"]),
        minRuns: common.minRuns,
        maxRuns: common.maxRuns,
      },
      fixtures: fixtures.map((f) => f.id),
      profiles: baseline
        ? baseline.meta.profiles
        : Object.fromEntries([
            ...list(opts.profiles).map((p) => [p, profiles[p].description]),
            ...fixtures
              .filter((f) => f.description === "real token data")
              .map((f) => [f.profile, "real token data"]),
          ]),
    },
    engines: allEngines,
    results: allResults,
  };

  writeFileSync(join(outDir, "results.json"), JSON.stringify(data, null, 2));
  const md = render(data);
  writeFileSync(join(outDir, "report.md"), md);
  process.stdout.write(`${md}\n`);
  log(`results: ${join(outDir, "results.json")}`);
  log(`report:  ${join(outDir, "report.md")}`);
}

main();
