// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

// Opt-in: build the release runner and the test bundle first:
//
//   pnpm run build:renderer-benchmarks:runner
//   pnpm run build:renderer-benchmarks:tests
//   RENDER_WASM_RUNNER_BOOT=target/performance/render-wasm/runner.cjs \
//     node --test test/performance_tests/render_wasm/cli_integration.cjs
//
// The normal unit test build does not build this CLI.

const assert = require("node:assert/strict");
const { execFile, spawn } = require("node:child_process");
const { once } = require("node:events");
const { setTimeout: pause } = require("node:timers/promises");
const { randomUUID } = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { before, test } = require("node:test");
const { pathToFileURL } = require("node:url");
const { promisify } = require("node:util");

const execute = promisify(execFile);
const testOptions = { timeout: 60000 };
let bootstrap;
let frontendRoot;
let transit;

before(async () => {
  assert.ok(
    process.env.RENDER_WASM_RUNNER_BOOT,
    "Set RENDER_WASM_RUNNER_BOOT to the release-compiled runner.cjs",
  );
  bootstrap = path.resolve(process.env.RENDER_WASM_RUNNER_BOOT);
  assert.ok(
    fs.existsSync(bootstrap) && fs.statSync(bootstrap).isFile(),
    "Run pnpm run build:renderer-benchmarks:runner before the CLI tests",
  );
  bootstrap = fs.realpathSync(bootstrap);
  frontendRoot = path.resolve(path.dirname(bootstrap), "../../..");
  assert.ok(fs.statSync(path.join(frontendRoot, "deps.edn")).isFile());
  assert.ok(
    fs
      .statSync(
        path.join(frontendRoot, "performance", "benches", "render_wasm"),
      )
      .isDirectory(),
  );
  assert.ok(fs.statSync(path.join(frontendRoot, "node_modules")).isDirectory());

  // Load the actual Transit JS library emitted by Shadow, without starting
  // either the CLJS test runner or the production CLI in this process.
  const transitModule = path.join(
    frontendRoot,
    "target/performance-tests/cljs-runtime/com.cognitect.transit.js",
  );
  assert.ok(
    fs.existsSync(transitModule),
    "Run pnpm run build:renderer-benchmarks:tests before the CLI tests",
  );
  await import(pathToFileURL(transitModule).href);
  transit = globalThis.com.cognitect.transit;
});

function createFrontend(t) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "render-wasm-cli-"));
  t.after(() => fs.rmSync(temporary, { recursive: true, force: true }));
  const root = path.join(temporary, "frontend");
  const managed = path.join(root, "target", "performance", "render-wasm");
  const renderer = path.join(temporary, "render-wasm");
  const runner = path.join(managed, "runner.cjs");
  fs.mkdirSync(path.join(root, "performance", "benches", "render_wasm"), {
    recursive: true,
  });
  fs.writeFileSync(path.join(root, "deps.edn"), "{}\n");
  fs.mkdirSync(managed, { recursive: true });
  fs.copyFileSync(bootstrap, runner);
  fs.copyFileSync(
    path.join(path.dirname(bootstrap), "commands.cjs"),
    path.join(managed, "commands.cjs"),
  );
  fs.symlinkSync(
    path.join(frontendRoot, "node_modules"),
    path.join(root, "node_modules"),
    "dir",
  );
  fs.mkdirSync(renderer);
  fs.mkdirSync(path.join(managed, "compiler-cache"));
  fs.writeFileSync(
    path.join(managed, "compiler-cache", "retained.cache"),
    "cache\n",
  );
  return {
    managed,
    runner,
    compiler: path.join(renderer, "build"),
    rendererRoot: renderer,
    builds: path.join(managed, "builds"),
    runs: path.join(managed, "runs"),
    comparisons: path.join(managed, "feature-comparisons"),
    env: {},
  };
}

async function runCli(fixture, args) {
  try {
    const output = await execute(
      process.execPath,
      [...(fixture.nodeArgs ?? []), fixture.runner, ...args],
      {
        cwd: os.tmpdir(),
        encoding: "utf8",
        timeout: 15000,
        killSignal: "SIGKILL",
        maxBuffer: 1024 * 1024,
        env: { ...process.env, ...fixture.env },
      },
    );
    return { exitCode: 0, ...output };
  } catch (error) {
    // A timeout, signal or spawn failure must fail the test, rather than
    // masquerade as the expected nonzero CLI exit.
    if (!Number.isInteger(error.code) || error.killed || error.signal) {
      throw error;
    }
    return {
      exitCode: error.code,
      stdout: error.stdout,
      stderr: error.stderr,
    };
  }
}

function readManifest(file) {
  return transit.reader("json").read(fs.readFileSync(file, "utf8"));
}

function manifestText(id, status, type) {
  const k = transit.keyword;
  return transit
    .writer("json")
    .write(
      transit.map([k("type"), k(type), k("id"), id, k("status"), k(status)]),
    );
}

function seedBuilds(fixture) {
  for (const status of ["building", "failed", "ready"]) {
    const id = randomUUID();
    const directory = path.join(fixture.builds, id);
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(
      path.join(directory, "build.transit"),
      manifestText(id, status, "renderer-build"),
    );
    fs.writeFileSync(path.join(directory, "build.log"), `${status} build\n`);
    fs.writeFileSync(path.join(directory, "keep.txt"), "keep\n");
  }
}

function seedRuns(fixture) {
  for (const status of ["complete", "failed"]) {
    const id = randomUUID();
    const directory = path.join(fixture.runs, id);
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(
      path.join(directory, "run.transit"),
      `${status} run output\n`,
    );
    fs.writeFileSync(path.join(directory, "run.log"), "run\n");
    fs.writeFileSync(path.join(directory, "case-load.PNG"), "screenshot\n");
    fs.writeFileSync(path.join(directory, "keep.txt"), "keep\n");
  }
}

function seedComparisons(fixture) {
  const id = randomUUID();
  const directory = path.join(fixture.comparisons, id);
  const config = path.join(directory, "configs", "00");
  const edges = path.join(directory, "comparisons");
  fs.mkdirSync(config, { recursive: true });
  fs.mkdirSync(edges, { recursive: true });
  fs.mkdirSync(path.join(config, "shot.PNG"), { recursive: true });
  fs.writeFileSync(
    path.join(directory, "feature-comparison.transit"),
    "comparison\n",
  );
  fs.writeFileSync(
    path.join(directory, "feature-comparison.log"),
    "progress\n",
  );
  fs.writeFileSync(path.join(directory, "keep.txt"), "keep\n");
  for (const file of ["run.transit", "run.log", "case-load.PNG"]) {
    fs.writeFileSync(path.join(config, file), "configuration output\n");
  }
  fs.writeFileSync(path.join(config, "keep.txt"), "keep\n");
  fs.writeFileSync(path.join(config, "shot.PNG", "keep.txt"), "keep\n");
  for (const file of ["00__10.transit", "keep.txt"]) {
    fs.writeFileSync(path.join(edges, file), "edge output\n");
  }
  fs.writeFileSync(path.join(fixture.comparisons, "keep.txt"), "keep\n");
}

function installCargoMetadata(fixture, definitions) {
  const manifest = path.join(fixture.rendererRoot, "Cargo.toml");
  const bin = path.join(path.dirname(fixture.rendererRoot), "fixture-bin");
  const metadata = path.join(fixture.rendererRoot, "metadata.json");
  const cargo = path.join(bin, "cargo");
  fs.writeFileSync(
    manifest,
    "[package]\nname = 'fixture'\nversion = '0.1.0'\n",
  );
  fs.writeFileSync(
    path.join(fixture.rendererRoot, "Cargo.lock"),
    "version = 4\n",
  );
  fs.mkdirSync(bin, { recursive: true });
  fs.writeFileSync(
    metadata,
    JSON.stringify({
      packages: [{ manifest_path: manifest, features: definitions }],
    }),
  );
  fs.writeFileSync(
    cargo,
    `#!/usr/bin/env node\nconst fs = require("node:fs");\nprocess.stdout.write(fs.readFileSync(process.env.CARGO_METADATA_FIXTURE, "utf8"));\n`,
    { mode: 0o755 },
  );
  fixture.env.PATH = `${bin}${path.delimiter}${process.env.PATH}`;
  fixture.env.CARGO_METADATA_FIXTURE = metadata;
  return cargo;
}

function installPreparedCompiler(fixture, breakLog) {
  const k = transit.keyword;
  const m = (pairs) => transit.map(pairs);
  const marker = "RENDER_FIXTURE_BUILD_ID";
  const identity = transit
    .writer("json")
    .write(m([k("build-id"), marker, k("cljs-profile-marks"), false]));
  const descriptor = m([
    k("id"),
    k("fixture/load"),
    k("scene"),
    k("fixture"),
    k("scene-description"),
    "fixture",
    k("scene-seed"),
    42,
    k("params"),
    m([]),
    k("view"),
    m([k("scale"), 1, k("x"), 0, k("y"), 0]),
    k("context"),
    k("fresh"),
    k("completion"),
    k("render-full"),
    k("batch-size"),
    1,
    k("preparation"),
    m([]),
  ]);
  const catalog = transit
    .writer("json")
    .write(m([k("build-id"), marker, k("cases"), [descriptor]]));
  const frontend = path.resolve(fixture.managed, "../../..");
  const host = path.join(
    frontend,
    "performance",
    "benches",
    "render_wasm",
    "browser",
  );
  const bin = path.join(fixture.rendererRoot, "fixture-bin");
  fs.mkdirSync(host, { recursive: true });
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(host, "index.html"), "<html></html>");
  fs.writeFileSync(
    fixture.compiler,
    `#!/usr/bin/env node
const fs = require('node:fs'); const path = require('node:path');
fs.writeFileSync(process.env.RENDER_WASM_BUILD_INFO, JSON.stringify({features: ['default'], profile: {opt_level: '3', debug_assertions: false}}));
for (const file of ['render-wasm.js', 'render-wasm.wasm', 'shared.js']) fs.writeFileSync(path.join(process.env.FRONTEND_DEST, file), 'fixture');
`,
    { mode: 0o755 },
  );
  fs.writeFileSync(
    path.join(bin, "clojure"),
    `#!/usr/bin/env node
const fs = require('node:fs'); const path = require('node:path');
const id = process.env.RENDER_BENCH_BUILD_ID;
const identity = ${JSON.stringify(identity)}.replaceAll(${JSON.stringify(marker)}, id);
const catalog = ${JSON.stringify(catalog)}.replaceAll(${JSON.stringify(marker)}, id);
fs.writeFileSync(process.env.RENDER_BENCH_CASES_PATH, 'module.exports = {buildIdentity() {return ' + JSON.stringify(identity) + ';}, collectCases() {return ' + JSON.stringify(catalog) + ';}};');
const log = path.join(path.dirname(process.env.RENDER_BENCH_CASES_PATH), 'build.log');
const sabotage = ${breakLog ? "'fs.unlinkSync(' + JSON.stringify(log) + '); fs.mkdirSync(' + JSON.stringify(log) + ');'" : "''"};
fs.writeFileSync(path.join(process.env.RENDER_BENCH_BROWSER_DIR, 'browser.js'), 'const fs = require("node:fs"); module.exports = {buildIdentity() {' + sabotage + 'return ' + JSON.stringify(identity) + ';}};');
`,
    { mode: 0o755 },
  );
  fixture.env.PATH = `${bin}${path.delimiter}${process.env.PATH}`;
}

function runFixtureText({
  runId,
  caseId,
  value,
  environment = { node: "v24" },
  mode = "release",
  features = [],
  scene = "case",
  sceneSeed = 7,
  count = 10,
  fingerprint = "a".repeat(64),
  operation = "load",
  operationArgs = { distance: 10 },
}) {
  const k = transit.keyword;
  const map = (pairs) => transit.map(pairs);
  const metric = map([
    k("value"),
    value,
    k("unit"),
    k("ms"),
    k("semantics"),
    k("elapsed"),
    k("basis"),
    map([k("kind"), k("attempt-total")]),
    k("zero-policy"),
    k("positive"),
    k("invalid-reason"),
    null,
  ]);
  const preparation = map([
    k("preparation-id"),
    `${runId}-preparation`,
    k("case-id"),
    k(caseId),
    k("fingerprint"),
    map([k("algorithm"), k("sha-256"), k("digest"), fingerprint]),
    k("effective-graphics"),
    map([k("webgl2"), true]),
    k("diagnostics"),
    map([]),
  ]);
  const attempt = map([
    k("ordinal"),
    0,
    k("case-id"),
    k(caseId),
    k("preparation-id"),
    `${runId}-preparation`,
    k("warmup?"),
    false,
    k("outcome"),
    k("completed"),
    k("metrics"),
    map([k("elapsed"), metric]),
    k("trace"),
    map([]),
  ]);
  const record = map([
    k("type"),
    k("run-record"),
    k("run-id"),
    runId,
    k("started-at"),
    "2026-10-26T00:00:00.000Z",
    k("ended-at"),
    "2026-10-26T00:01:00.000Z",
    k("plan"),
    map([
      k("cases"),
      [
        map([
          k("id"),
          k(caseId),
          k("scene"),
          k(scene),
          k("scene-seed"),
          sceneSeed,
          k("params"),
          map([k("count"), count]),
          k("operation"),
          map([
            k("id"),
            k(operation),
            k("args"),
            map(
              Object.entries(operationArgs).flatMap(([key, entry]) => [
                k(key),
                entry,
              ]),
            ),
          ]),
        ]),
      ],
      k("warmups"),
      0,
      k("repetitions"),
      1,
      k("timeout-policy"),
      map([k("attempt-ms"), 30000]),
    ]),
    k("metadata"),
    map([
      k("git"),
      map([k("sha"), runId, k("dirty"), false]),
      k("build"),
      map([
        k("functional"),
        map([k("mode"), k(mode), k("features"), features]),
      ]),
      k("environment"),
      map(
        Object.entries(environment).flatMap(([key, entry]) => [k(key), entry]),
      ),
      k("scored?"),
      true,
      k("diagnostics"),
      map([]),
    ]),
    k("analysis"),
    map([
      k("draws"),
      2000,
      k("seed"),
      1885695600,
      k("confidence"),
      0.95,
      k("min-ci"),
      10,
      k("min-p95"),
      100,
      k("min-p99"),
      500,
    ]),
    k("preparations"),
    [preparation],
    k("attempts"),
    [attempt],
    k("remaining"),
    map([k(caseId), map([k("warmups"), 0, k("repetitions"), 0])]),
    k("termination"),
    map([k("reason"), k("completed")]),
  ]);
  return transit.writer("json").write(record);
}

function tree(directory) {
  return fs
    .readdirSync(directory, { withFileTypes: true })
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((entry) => {
      const file = path.join(directory, entry.name);
      return [
        entry.name,
        entry.isDirectory() ? tree(file) : fs.readFileSync(file),
      ];
    });
}

const failingCompiler = `#!/usr/bin/env node
const fs = require("node:fs");
const path = require("node:path");
const directory = path.dirname(process.env.FRONTEND_DEST);
fs.copyFileSync(
  path.join(directory, "build.transit"),
  path.join(directory, "before-compiler.transit")
);
fs.writeFileSync(
  path.join(process.env.FRONTEND_DEST, "partial.js"),
  "partial renderer output\\n"
);
process.stdout.write("compiler stdout before failure\\n");
process.stderr.write("compiler stderr before failure\\n");
process.exitCode = 23;
`;

test(
  "build prints its allocation and one failure block with retained compiler evidence",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    fs.writeFileSync(fixture.compiler, failingCompiler, { mode: 0o755 });

    const args = ["build", "--build-timeout-ms", "10000"];
    const result = await runCli(fixture, args);
    assert.equal(result.exitCode, 1);
    const [buildLine] = result.stdout.trim().split("\n");
    const id = buildLine.slice("Build ".length);
    assert.match(
      id,
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    assert.equal(
      result.stdout,
      `Build ${id}\nOutput ${path.join(fixture.builds, id)}\n`,
    );
    assert.deepEqual(fs.readdirSync(fixture.builds), [id]);

    const directory = path.join(fixture.builds, id);
    const logPath = path.join(directory, "build.log");
    const initial = readManifest(
      path.join(directory, "before-compiler.transit"),
    );
    const manifest = readManifest(path.join(directory, "build.transit"));
    const k = transit.keyword;
    assert.equal(initial.get(k("id")), id);
    assert.equal(initial.get(k("status")).toString(), ":building");
    assert.equal(manifest.get(k("id")), id);
    assert.equal(manifest.get(k("status")).toString(), ":failed");
    const failure = manifest.get(k("failure"));
    assert.equal(failure.get(k("phase")).toString(), ":renderer");
    assert.equal(
      failure.get(k("cause"))[0].get(k("type")).toString(),
      ":process-exit",
    );
    assert.equal(
      fs.readFileSync(path.join(directory, "renderer", "partial.js"), "utf8"),
      "partial renderer output\n",
    );

    const log = fs.readFileSync(logPath, "utf8");
    for (const output of [log, result.stderr]) {
      assert.ok(output.includes("compiler stdout before failure\n"));
      assert.ok(output.includes("compiler stderr before failure\n"));
    }
    assert.match(result.stderr, /renderer build preparation failed/);
    assert.match(
      result.stderr,
      /type\s+:benches.render-wasm.node.build\/preparation-failed/,
    );
    assert.match(result.stderr, new RegExp(`build\\s+${id}`));
    assert.ok(result.stderr.includes(logPath));
    assert.match(result.stderr, /phase\s+:renderer/);
    assert.equal(result.stderr.split("✖ ").length - 1, 1);
    assert.doesNotMatch(result.stderr, /^compiler (stdout|stderr)/m);
    assert.match(log, /✖ renderer build preparation failed/);
  },
);

test(
  "build failure diagnostics retain semantic and storage causes",
  testOptions,
  async (t) => {
    for (const [source, reason, broken] of [
      [
        `#!/usr/bin/env node
const fs = require('node:fs');
fs.writeFileSync(process.env.RENDER_WASM_BUILD_INFO, JSON.stringify({features: [], profile: {opt_level: '0', debug_assertions: false}}));
`,
        /Cargo did not produce an optimized renderer/,
      ],
      [
        failingCompiler +
          "\nfs.unlinkSync(require('node:path').join(require('node:path').dirname(process.env.FRONTEND_DEST), 'build.transit'));\n",
        /Command exited unsuccessfully/,
        "manifest",
      ],
      [
        `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const log = path.join(path.dirname(process.env.FRONTEND_DEST), 'build.log');
fs.unlinkSync(log); fs.mkdirSync(log);
process.stdout.write('compiler output\\n');
`,
        /cannot append benchmark log/,
        "log",
      ],
    ]) {
      const fixture = createFrontend(t);
      fs.writeFileSync(fixture.compiler, source, { mode: 0o755 });
      const result = await runCli(fixture, ["build"]);
      const [id] = fs.readdirSync(fixture.builds);
      assert.equal(result.exitCode, 1);
      assert.match(result.stderr, /✖ renderer build preparation failed/);
      assert.match(result.stderr, reason);
      assert.ok(result.stderr.includes(id));
      assert.ok(
        result.stderr.includes(path.join(fixture.builds, id, "build.log")),
      );
      if (broken !== "log") {
        const log = fs.readFileSync(
          path.join(fixture.builds, id, "build.log"),
          "utf8",
        );
        assert.match(log, /✖ renderer build preparation failed/);
        assert.match(log, reason);
      }
      if (broken === "manifest") {
        assert.match(result.stderr, /manifest-error\s+.*ENOENT/);
      } else {
        const manifest = readManifest(
          path.join(fixture.builds, id, "build.transit"),
        );
        assert.equal(
          manifest.get(transit.keyword("status")).toString(),
          ":failed",
        );
      }
    }
  },
);

test(
  "build completion retains a usable ready build or the original log failure",
  testOptions,
  async (t) => {
    for (const breakLog of [false, true]) {
      const fixture = createFrontend(t);
      installPreparedCompiler(fixture, breakLog);
      const result = await runCli(fixture, ["build"]);
      const [id] = fs.readdirSync(fixture.builds);
      const manifest = readManifest(
        path.join(fixture.builds, id, "build.transit"),
      );
      assert.equal(result.exitCode, breakLog ? 1 : 0, result.stderr);
      assert.equal(
        manifest.get(transit.keyword("status")).toString(),
        breakLog ? ":failed" : ":ready",
      );
      if (breakLog) {
        assert.match(result.stderr, /cannot append benchmark log/);
        assert.ok(
          result.stderr.includes(path.join(fixture.builds, id, "build.log")),
        );
      } else {
        assert.match(result.stderr, /ready/);
      }
    }
  },
);

test(
  "output flags control progress, raw output and console colors",
  testOptions,
  async (t) => {
    for (const [flags, phases, raw, colors] of [
      [[], true, false, false],
      [["--verbose"], true, true, false],
      [["--quiet"], false, false, false],
      [["--color"], true, false, true],
      [["--no-color"], true, false, false],
    ]) {
      const fixture = createFrontend(t);
      fs.writeFileSync(fixture.compiler, failingCompiler, { mode: 0o755 });
      const result = await runCli(fixture, ["build", ...flags]);
      assert.equal(result.exitCode, 1, result.stderr);
      assert.match(result.stdout, /^Build [^\n]+\nOutput \/[^\n]+\n$/);
      assert.equal(
        /^(?:\u001b\[[0-9;]*m)?Renderer compiling/m.test(result.stderr),
        phases,
      );
      assert.equal(
        result.stderr.includes("· compiler stdout before failure"),
        raw,
      );
      assert.equal(result.stderr.includes("\u001b["), colors);
      assert.match(result.stderr, /! Git provenance unavailable:/);
      const [id] = fs.readdirSync(fixture.builds);
      const log = fs.readFileSync(
        path.join(fixture.builds, id, "build.log"),
        "utf8",
      );
      assert.match(log, /^compiler stdout before failure$/m);
      assert.match(log, /^Renderer compiling$/m);
      assert.doesNotMatch(log, /\u001b\[/);
    }
  },
);

test(
  "ab retains its log and names the nested build tail on failure",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    installCargoMetadata(fixture, { default: [], a: [] });
    fs.writeFileSync(fixture.compiler, failingCompiler, { mode: 0o755 });
    const result = await runCli(fixture, [
      "ab",
      "--features",
      "a",
      "--filter",
      "rects/load",
    ]);
    assert.equal(result.exitCode, 1, result.stderr);
    const [idLine, outputLine, statusLine] = result.stdout.trim().split("\n");
    assert.match(idLine, /^Comparison [0-9a-f-]+$/);
    const directory = outputLine.slice("Output ".length);
    assert.equal(statusLine, "Status failed");
    const logPath = path.join(directory, "feature-comparison.log");
    assert.equal(result.stderr.split("✖ ").length - 1, 1);
    assert.ok(result.stderr.includes(logPath));
    assert.match(result.stderr, /tail-log\s+.*build\.log/);
    assert.match(
      fs.readFileSync(logPath, "utf8"),
      /compiler stderr before failure/,
    );
    await runCli(fixture, ["purge", "--only-comparisons"]);
    assert.equal(fs.existsSync(logPath), false);
  },
);

test(
  "ab prints status once and preserves the build failure when its log becomes unwritable",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    installCargoMetadata(fixture, { default: [], a: [] });
    const output = path.join(fixture.managed, "comparison-output");
    fixture.env.COMPARISON_LOG = path.join(output, "feature-comparison.log");
    fs.writeFileSync(
      fixture.compiler,
      failingCompiler +
        "\nfs.unlinkSync(process.env.COMPARISON_LOG); fs.mkdirSync(process.env.COMPARISON_LOG);\n",
      { mode: 0o755 },
    );
    const result = await runCli(fixture, [
      "ab",
      "--features",
      "a",
      "--filter",
      "rects/load",
      "--output-dir",
      output,
    ]);
    assert.equal(result.exitCode, 1);
    assert.equal(result.stdout.split("Status failed\n").length - 1, 1);
    assert.match(result.stderr, /✖ renderer build preparation failed/);
  },
);

test(
  "run and ab retain signal exit codes during preparation",
  testOptions,
  async (t) => {
    for (const command of ["run", "ab"]) {
      for (const [signal, code] of [
        ["SIGINT", 130],
        ["SIGTERM", 143],
      ]) {
        const fixture = createFrontend(t);
        installCargoMetadata(fixture, { default: [], a: [] });
        const marker = path.join(fixture.managed, "compiler-started");
        fs.writeFileSync(
          fixture.compiler,
          `#!/usr/bin/env node\nconst fs = require('node:fs'); fs.writeFileSync(${JSON.stringify(marker)}, 'ready'); setInterval(() => {}, 1000);\n`,
          { mode: 0o755 },
        );
        const args =
          command === "ab"
            ? ["ab", "--features", "a", "--filter", "rects/load"]
            : ["run"];
        const child = spawn(process.execPath, [fixture.runner, ...args], {
          cwd: os.tmpdir(),
          env: { ...process.env, ...fixture.env },
          stdio: ["ignore", "pipe", "pipe"],
        });
        t.after(() => {
          if (child.exitCode === null) child.kill("SIGKILL");
        });
        const exited = once(child, "exit");
        let stderr = "";
        child.stdout.resume();
        child.stderr.on("data", (chunk) => {
          stderr += chunk;
        });
        const deadline = Date.now() + 10000;
        while (
          !fs.existsSync(marker) &&
          child.exitCode === null &&
          Date.now() < deadline
        )
          await pause(10);
        assert.ok(fs.existsSync(marker), stderr);
        child.kill(signal);
        assert.deepEqual(await exited, [code, null], stderr);
      }
    }
  },
);

test(
  "run records an early retained-build failure in its allocated log",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const output = path.join(fixture.managed, "early-run");
    const result = await runCli(fixture, [
      "run",
      "--build",
      "missing",
      "--output-dir",
      output,
    ]);
    assert.equal(result.exitCode, 1);
    const logPath = path.join(output, "run.log");
    assert.ok(result.stderr.includes(logPath));
    assert.match(fs.readFileSync(logPath, "utf8"), /✖ /);
  },
);

test(
  "ab dry-run prints the Gray plan without creating comparison output",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    fixture.env.PENPOT_GPU = "0";
    installCargoMetadata(fixture, { default: [], a: [], b: ["a"] });
    const retained = tree(fixture.managed);

    const result = await runCli(fixture, [
      "ab",
      "--features",
      "b,a",
      "--filter",
      "rects/load",
      "--dry-run",
      "--require-gpu",
    ]);

    assert.equal(result.exitCode, 0);
    assert.equal(result.stderr, "");
    assert.match(result.stdout, /^Feature comparison plan/);
    for (const part of [
      "Features: a, b",
      "00: ",
      "01: b",
      "11: a, b",
      "10: a",
      "00 -> 10: a",
      "00 -> 01: b",
      "01 -> 11: a",
      "10 -> 11: b",
      "Cases: 1",
    ]) {
      assert.ok(result.stdout.includes(part), part);
    }
    assert.equal(fs.existsSync(fixture.comparisons), false);
    assert.deepEqual(tree(fixture.managed), retained);
  },
);

test(
  "ab validates requested features before creating comparison output",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    installCargoMetadata(fixture, { default: [], a: [] });
    const retained = tree(fixture.managed);

    const result = await runCli(fixture, [
      "ab",
      "--features",
      "missing",
      "--filter",
      "rects/load",
      "--dry-run",
    ]);

    assert.equal(result.exitCode, 1);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /unknown requested Cargo features/);
    assert.equal(fs.existsSync(fixture.comparisons), false);
    assert.deepEqual(tree(fixture.managed), retained);
  },
);

test("purge deletes only the selected artifacts", testOptions, async (t) => {
  const fixture = createFrontend(t);
  const retained = tree(fixture.managed);
  seedBuilds(fixture);
  seedRuns(fixture);
  seedComparisons(fixture);
  const runs = tree(fixture.runs);
  const comparisons = tree(fixture.comparisons);

  assert.deepEqual(await runCli(fixture, ["purge", "--only-builds"]), {
    exitCode: 0,
    stdout: "",
    stderr: "",
  });
  for (const id of fs.readdirSync(fixture.builds)) {
    assert.deepEqual(fs.readdirSync(path.join(fixture.builds, id)), [
      "keep.txt",
    ]);
  }
  assert.deepEqual(tree(fixture.runs), runs);
  assert.deepEqual(tree(fixture.comparisons), comparisons);
  assert.deepEqual(
    tree(fixture.managed).filter(
      ([name]) => !["runs", "builds", "feature-comparisons"].includes(name),
    ),
    retained,
  );

  seedBuilds(fixture);
  const builds = tree(fixture.builds);
  assert.deepEqual(await runCli(fixture, ["purge", "--only-runs"]), {
    exitCode: 0,
    stdout: "",
    stderr: "",
  });
  for (const id of fs.readdirSync(fixture.runs)) {
    assert.deepEqual(fs.readdirSync(path.join(fixture.runs, id)), ["keep.txt"]);
  }
  assert.deepEqual(tree(fixture.builds), builds);
  assert.deepEqual(tree(fixture.comparisons), comparisons);
  assert.deepEqual(
    tree(fixture.managed).filter(
      ([name]) => !["runs", "builds", "feature-comparisons"].includes(name),
    ),
    retained,
  );

  const buildsBeforeComparisonPurge = tree(fixture.builds);
  const runsBeforeComparisonPurge = tree(fixture.runs);
  assert.deepEqual(await runCli(fixture, ["purge", "--only-comparisons"]), {
    exitCode: 0,
    stdout: "",
    stderr: "",
  });
  const [comparisonId] = fs.readdirSync(fixture.comparisons);
  const comparison = path.join(fixture.comparisons, comparisonId);
  for (const file of [
    "feature-comparison.log",
    "feature-comparison.transit",
    "configs/00/run.transit",
    "configs/00/run.log",
    "configs/00/case-load.PNG",
    "comparisons/00__10.transit",
  ]) {
    assert.equal(fs.existsSync(path.join(comparison, file)), false, file);
  }
  for (const file of [
    "keep.txt",
    "configs/00/keep.txt",
    "configs/00/shot.PNG/keep.txt",
    "comparisons/keep.txt",
  ]) {
    assert.equal(fs.existsSync(path.join(comparison, file)), true, file);
  }
  assert.deepEqual(tree(fixture.builds), buildsBeforeComparisonPurge);
  assert.deepEqual(tree(fixture.runs), runsBeforeComparisonPurge);
  assert.deepEqual(
    tree(fixture.managed).filter(
      ([name]) => !["runs", "builds", "feature-comparisons"].includes(name),
    ),
    retained,
  );

  seedRuns(fixture);
  seedComparisons(fixture);
  assert.deepEqual(await runCli(fixture, ["purge"]), {
    exitCode: 0,
    stdout: "",
    stderr: "",
  });
  for (const root of [fixture.builds, fixture.runs]) {
    for (const id of fs.readdirSync(root)) {
      assert.deepEqual(fs.readdirSync(path.join(root, id)), ["keep.txt"]);
    }
  }
  for (const id of fs.readdirSync(fixture.comparisons)) {
    const directory = path.join(fixture.comparisons, id);
    if (id === "keep.txt") continue;
    for (const file of [
      "feature-comparison.transit",
      "configs/00/run.transit",
      "configs/00/run.log",
      "configs/00/case-load.PNG",
      "comparisons/00__10.transit",
    ]) {
      assert.equal(fs.existsSync(path.join(directory, file)), false, file);
    }
    assert.equal(fs.existsSync(path.join(directory, "keep.txt")), true);
  }
  assert.deepEqual(
    tree(fixture.managed).filter(
      ([name]) => !["runs", "builds", "feature-comparisons"].includes(name),
    ),
    retained,
  );
});

test(
  "run reserves nested output before the compiler starts",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const output = path.join(fixture.managed, "missing", "parents", "output");
    const marker = path.join(fixture.managed, "compiler-ran");
    fs.writeFileSync(
      fixture.compiler,
      `#!/usr/bin/env node
const fs = require("node:fs");
if (!fs.statSync(${JSON.stringify(path.join(output, "run.log"))}).isFile()) process.exit(99);
fs.writeFileSync(${JSON.stringify(marker)}, "reserved");
process.exit(23);
`,
      { mode: 0o755 },
    );

    const result = await runCli(fixture, ["run", "--output-dir", output]);

    assert.equal(result.exitCode, 1);
    assert.match(
      result.stdout,
      new RegExp(`^Run [0-9a-f-]+\\nOutput ${output}\\n$`),
    );
    assert.equal(fs.readFileSync(marker, "utf8"), "reserved");
    assert.equal(fs.existsSync(path.join(output, "run.transit")), false);
  },
);

test(
  "run rejects invalid output parents before invoking the compiler",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const parent = path.join(fixture.managed, "not-a-directory");
    const marker = path.join(fixture.managed, "compiler-ran");
    fs.writeFileSync(parent, "keep");
    fs.writeFileSync(
      fixture.compiler,
      `#!/usr/bin/env node
require("node:fs").writeFileSync(${JSON.stringify(marker)}, "ran");
process.exit(23);
`,
      { mode: 0o755 },
    );

    const result = await runCli(fixture, [
      "run",
      "--output-dir",
      path.join(parent, "output"),
    ]);

    assert.equal(result.exitCode, 1);
    assert.match(result.stderr, /ENOTDIR|cannot create run home/);
    assert.equal(fs.existsSync(marker), false);
    assert.equal(fs.readFileSync(parent, "utf8"), "keep");
  },
);

test(
  "run rejects --remove outputs inside builds including directory aliases",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const marker = path.join(fixture.managed, "compiler-ran");
    const alias = path.join(path.dirname(fixture.managed), "build-alias");
    fs.mkdirSync(fixture.builds);
    fs.symlinkSync(fixture.builds, alias, "dir");
    fs.writeFileSync(
      fixture.compiler,
      `#!/usr/bin/env node
require("node:fs").writeFileSync(${JSON.stringify(marker)}, "ran");
process.exit(23);
`,
      { mode: 0o755 },
    );

    for (const root of [fixture.builds, alias]) {
      const output = path.join(root, "missing", "output");
      const result = await runCli(fixture, [
        "run",
        "--remove",
        "--output-dir",
        output,
      ]);
      assert.equal(result.exitCode, 1);
      assert.match(result.stderr, /--remove.*inside builds/);
      assert.equal(fs.existsSync(output), false);
    }
    assert.equal(fs.existsSync(marker), false);
  },
);

test(
  "malformed purge categories leave all files intact",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    seedBuilds(fixture);
    seedRuns(fixture);
    const original = tree(fixture.managed);

    for (const args of [
      ["purge", "--only-builds", "--only-runs"],
      ["purge", "--only-builds", "--only-comparisons"],
      ["purge", "--only-runs", "--only-comparisons"],
      ["purge", "--only-ready-builds"],
      ["purge", "--only-builds", "ready"],
    ]) {
      const result = await runCli(fixture, args);
      assert.equal(result.exitCode, 1, args.join(" "));
      assert.equal(result.stdout, "");
      assert.match(
        result.stderr,
        /type\s+:benches.render-wasm.node.runner\/invalid-args/,
      );
      assert.deepEqual(tree(fixture.managed), original, args.join(" "));
    }
  },
);

test(
  "required GPU access rejects run and ab before allocating output or checking builds",
  testOptions,
  async (t) => {
    for (const command of [
      ["run"],
      ["ab", "--features", "a", "--filter", "rects/load"],
    ]) {
      const fixture = createFrontend(t);
      fixture.env.PENPOT_GPU = "";
      const original = tree(fixture.managed);
      const result = await runCli(fixture, [
        ...command,
        "--require-gpu",
        "--output-dir",
        path.join(fixture.managed, "gpu-run"),
      ]);

      assert.equal(result.exitCode, 1);
      assert.equal(result.stdout, "");
      assert.match(result.stderr, /PENPOT_GPU=1/);
      assert.match(result.stderr, /\.\/manage\.sh run-devenv --gpu/);
      assert.match(result.stderr, /pnpm run render-bench/);
      assert.deepEqual(tree(fixture.managed), original);
    }
  },
);

test(
  "run validates an explicit output dir before preparing a build",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const marker = path.join(fixture.managed, "compiler-ran");
    fs.writeFileSync(
      fixture.compiler,
      `#!/usr/bin/env node
const fs = require("node:fs");
fs.writeFileSync(${JSON.stringify(marker)}, "ran");
`,
      { mode: 0o755 },
    );
    const output = path.join(fixture.managed, "existing-output");
    fs.mkdirSync(output);
    const original = tree(fixture.managed);

    const result = await runCli(fixture, [
      "run",
      "--output-dir",
      output,
      "--warmups",
      "0",
      "--repetitions",
      "1",
    ]);

    assert.equal(result.exitCode, 1);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /output directory already exists/);
    assert.equal(fs.existsSync(marker), false);
    assert.deepEqual(tree(fixture.managed), original);
  },
);

test(
  "run rejects retained-build feature selection before any work",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const marker = path.join(fixture.managed, "compiler-ran");
    fs.writeFileSync(
      fixture.compiler,
      `#!/usr/bin/env node
const fs = require("node:fs");
fs.writeFileSync(${JSON.stringify(marker)}, "ran");
`,
      { mode: 0o755 },
    );
    const original = tree(fixture.managed);

    const result = await runCli(fixture, [
      "run",
      "--build",
      "retained-id",
      "--features",
      "functional",
    ]);

    assert.equal(result.exitCode, 1);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /cannot be combined/);
    assert.equal(fs.existsSync(marker), false);
    assert.deepEqual(tree(fixture.managed), original);
  },
);

test(
  "help and compare work without a frontend checkout or execution dependencies",
  testOptions,
  async (t) => {
    const temporary = fs.mkdtempSync(
      path.join(os.tmpdir(), "render-wasm-offline-"),
    );
    t.after(() => fs.rmSync(temporary, { recursive: true, force: true }));
    const runner = path.join(temporary, "runner.cjs");
    const guard = path.join(temporary, "guard.cjs");
    fs.copyFileSync(bootstrap, runner);
    fs.symlinkSync(
      path.join(frontendRoot, "node_modules"),
      path.join(temporary, "node_modules"),
      "dir",
    );
    fs.writeFileSync(
      guard,
      `
    const Module = require("node:module");
    const load = Module._load;
    Module._load = function (id, ...args) {
      if (["playwright", "http", "node:http", "child_process", "node:child_process"].includes(id)) {
        throw new Error("Offline command imported execution dependency: " + id);
      }
      return load.call(this, id, ...args);
    };
  `,
    );
    const baseline = path.join(temporary, "baseline.transit");
    const candidate = path.join(temporary, "candidate.transit");
    fs.writeFileSync(
      baseline,
      runFixtureText({ runId: "baseline", caseId: "case/load", value: 10 }),
    );
    fs.writeFileSync(
      candidate,
      runFixtureText({ runId: "candidate", caseId: "case/load", value: 12 }),
    );
    const fixture = { runner, nodeArgs: ["--require", guard] };

    const help = await runCli(fixture, ["--help"]);
    assert.equal(help.exitCode, 0, help.stderr);
    assert.match(help.stdout, /runner compare/);
    const abHelp = await runCli(fixture, ["ab", "--help"]);
    assert.equal(abHelp.exitCode, 0, abHelp.stderr);
    assert.match(abHelp.stdout, /runner ab --features/);
    assert.match(abHelp.stdout, /--only-comparisons/);
    for (const args of [["ab"], ["ab", "--dry-run"]]) {
      const invalid = await runCli(fixture, args);
      assert.equal(invalid.exitCode, 1, invalid.stderr);
      assert.match(invalid.stderr, /at least one requested feature/);
      assert.doesNotMatch(
        invalid.stderr,
        /execution dependency|Cannot find module/,
      );
    }
    const comparison = await runCli(fixture, ["compare", baseline, candidate]);
    assert.equal(comparison.exitCode, 0, comparison.stderr);
    assert.match(comparison.stdout, /median change/);
    for (const flag of [
      "--allow-build-differences",
      "--allow-scene-differences",
      "--allow-all-differences",
    ]) {
      const allowed = await runCli(fixture, [
        "compare",
        baseline,
        candidate,
        flag,
      ]);
      assert.equal(allowed.exitCode, 0, allowed.stderr);
      assert.match(allowed.stdout, /median change/);
    }
    assert.equal(fs.existsSync(path.join(temporary, "commands.cjs")), false);
  },
);

test(
  "help lists run and compare without touching managed storage",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const original = tree(fixture.managed);

    const result = await runCli(fixture, ["--help"]);

    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /runner run/);
    assert.match(result.stdout, /runner compare/);
    assert.match(result.stdout, /--cases/);
    assert.match(result.stdout, /--allow-build-differences/);
    assert.match(result.stdout, /--allow-scene-differences/);
    assert.match(result.stdout, /--allow-all-differences/);
    assert.match(result.stdout, /-q, --quiet/);
    assert.match(result.stdout, /-v, --verbose/);
    assert.match(result.stdout, /--\[no-\]color/);
    assert.doesNotMatch(result.stdout, /--force/);
    assert.deepEqual(tree(fixture.managed), original);
  },
);

test(
  "compare reads saved run files without starting resources",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const baseline = path.join(fixture.managed, "baseline.transit");
    const candidate = path.join(fixture.managed, "candidate.transit");
    fs.writeFileSync(
      baseline,
      runFixtureText({ runId: "baseline", caseId: "case/load", value: 10 }),
    );
    fs.writeFileSync(
      candidate,
      runFixtureText({ runId: "candidate", caseId: "case/load", value: 12 }),
    );
    const original = tree(fixture.managed);

    const result = await runCli(fixture, ["compare", baseline, candidate]);

    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /Baseline/);
    assert.match(result.stdout, /case\/load/);
    assert.match(result.stdout, /median change/);
    assert.deepEqual(tree(fixture.managed), original);
  },
);

test(
  "compare supports explicit pairs and all-differences from the CLI",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const baseline = path.join(fixture.managed, "baseline.transit");
    const candidate = path.join(fixture.managed, "candidate.transit");
    fs.writeFileSync(
      baseline,
      runFixtureText({ runId: "baseline", caseId: "a/pan", value: 10 }),
    );
    fs.writeFileSync(
      candidate,
      runFixtureText({
        runId: "candidate",
        caseId: "b/pan",
        value: 12,
        environment: { node: "v25" },
      }),
    );
    const original = tree(fixture.managed);

    const incompatible = await runCli(fixture, [
      "compare",
      baseline,
      candidate,
    ]);
    assert.equal(incompatible.exitCode, 1);
    assert.deepEqual(tree(fixture.managed), original);

    const selected = ["compare", baseline, candidate, "--cases", "a/pan,b/pan"];
    const strict = await runCli(fixture, selected);
    assert.equal(strict.exitCode, 1);
    assert.match(strict.stderr, /incompatible saved runs/);

    const allowed = await runCli(fixture, [
      ...selected,
      "--allow-all-differences",
    ]);
    assert.equal(allowed.exitCode, 0, allowed.stderr);
    assert.match(allowed.stdout, /:allow-all-differences true/);
    assert.match(allowed.stdout, /ACKNOWLEDGED DIFFERENCE/);
    assert.match(allowed.stdout, /:rule :all/);
    assert.match(allowed.stdout, /Pair a\/pan,b\/pan/);
    assert.deepEqual(tree(fixture.managed), original);

    fs.writeFileSync(
      candidate,
      runFixtureText({
        runId: "candidate",
        caseId: "a/pan",
        value: 12,
        environment: { node: "v25" },
      }),
    );
    const automatic = await runCli(fixture, [
      "compare",
      baseline,
      candidate,
      "--allow-all-differences",
    ]);
    assert.equal(automatic.exitCode, 0, automatic.stderr);
    assert.match(automatic.stdout, /:allow-all-differences true/);
    assert.match(automatic.stdout, /ACKNOWLEDGED DIFFERENCE/);
    assert.match(automatic.stdout, /Pair a\/pan,a\/pan/);
  },
);

test(
  "compare applies build and scene permissions separately and together",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const baseline = path.join(fixture.managed, "baseline.transit");
    const candidate = path.join(fixture.managed, "candidate.transit");
    fs.writeFileSync(
      baseline,
      runFixtureText({ runId: "baseline", caseId: "case/load", value: 10 }),
    );

    const build = "--allow-build-differences";
    const scene = "--allow-scene-differences";
    for (const scenario of [
      {
        changes: { mode: "debug", features: ["fixture"] },
        allowed: [build],
        denied: [[], [scene]],
        fields: ["functional-build", "effective-features"],
        rules: ["build"],
      },
      {
        changes: {
          scene: "other",
          sceneSeed: 8,
          count: 20,
          fingerprint: "b".repeat(64),
        },
        allowed: [scene],
        denied: [[], [build]],
        fields: ["scene", "fingerprint"],
        rules: ["scene"],
      },
      {
        changes: { mode: "debug", count: 20 },
        allowed: [build, scene],
        denied: [[], [build], [scene]],
        fields: ["functional-build", "scene"],
        rules: ["build", "scene"],
      },
    ]) {
      fs.writeFileSync(
        candidate,
        runFixtureText({
          runId: "candidate",
          caseId: "case/load",
          value: 12,
          ...scenario.changes,
        }),
      );
      const original = tree(fixture.managed);
      for (const flags of scenario.denied) {
        const rejected = await runCli(fixture, [
          "compare",
          baseline,
          candidate,
          ...flags,
        ]);
        assert.equal(rejected.exitCode, 1, rejected.stderr);
        assert.match(rejected.stderr, /incompatible saved runs/);
      }
      const allowed = await runCli(fixture, [
        "compare",
        baseline,
        candidate,
        ...scenario.allowed,
      ]);
      assert.equal(allowed.exitCode, 0, allowed.stderr);
      assert.match(allowed.stdout, /:allow-all-differences false/);
      assert.match(allowed.stdout, /ACKNOWLEDGED DIFFERENCE/);
      for (const flag of scenario.allowed) {
        assert.ok(allowed.stdout.includes(`:${flag.slice(2)} true`));
      }
      for (const field of scenario.fields) {
        assert.ok(allowed.stdout.includes(`:field :${field}`));
      }
      for (const rule of scenario.rules) {
        assert.ok(allowed.stdout.includes(`:rule :${rule}`));
      }
      assert.deepEqual(tree(fixture.managed), original);
    }
  },
);

test(
  "scoped compare permissions keep operation arguments and runtime environment strict",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    const baseline = path.join(fixture.managed, "baseline.transit");
    const candidate = path.join(fixture.managed, "candidate.transit");
    fs.writeFileSync(
      baseline,
      runFixtureText({ runId: "baseline", caseId: "case/load", value: 10 }),
    );
    for (const [field, changes] of [
      ["execution", { operation: "pan" }],
      ["execution", { operationArgs: { distance: 20 } }],
      ["environment", { environment: { node: "v25" } }],
    ]) {
      fs.writeFileSync(
        candidate,
        runFixtureText({
          runId: "candidate",
          caseId: "case/load",
          value: 12,
          ...changes,
        }),
      );
      const original = tree(fixture.managed);
      const rejected = await runCli(fixture, [
        "compare",
        baseline,
        candidate,
        "--allow-build-differences",
        "--allow-scene-differences",
      ]);
      assert.equal(rejected.exitCode, 1);
      assert.match(rejected.stderr, /incompatible saved runs/);
      assert.match(rejected.stderr, new RegExp(`difference\\s+:${field}`));

      const allowed = await runCli(fixture, [
        "compare",
        baseline,
        candidate,
        "--allow-all-differences",
      ]);
      assert.equal(allowed.exitCode, 0, allowed.stderr);
      assert.ok(allowed.stdout.includes(`:field :${field}`));
      assert.match(allowed.stdout, /:rule :all/);
      assert.deepEqual(tree(fixture.managed), original);
    }
  },
);

test("compare rejects --force without an alias", testOptions, async (t) => {
  const fixture = createFrontend(t);
  const original = tree(fixture.managed);
  for (const flags of [["--force"], ["--force", "--allow-all-differences"]]) {
    const rejected = await runCli(fixture, [
      "compare",
      "missing-baseline.transit",
      "missing-candidate.transit",
      ...flags,
    ]);
    assert.equal(rejected.exitCode, 1);
    assert.match(rejected.stderr, /Unknown option.*--force/);
    assert.doesNotMatch(rejected.stderr, /ENOENT/);
    assert.deepEqual(tree(fixture.managed), original);
  }
});
