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
const { execFile } = require("node:child_process");
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
    builds: path.join(managed, "builds"),
    runs: path.join(managed, "runs"),
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
      transit.map([
        k("type"),
        k(type),
        k("schema-version"),
        1,
        k("id"),
        id,
        k("status"),
        k(status),
      ]),
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
      manifestText(id, status, "renderer-run"),
    );
    fs.writeFileSync(path.join(directory, "run.log"), "run\n");
    fs.writeFileSync(path.join(directory, "case-load.PNG"), "screenshot\n");
    fs.writeFileSync(path.join(directory, "keep.txt"), "keep\n");
  }
}

function runFixtureText({
  runId,
  caseId,
  value,
  environment = { node: "v24" },
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
    map([
      k("encoding-version"),
      1,
      k("algorithm"),
      k("sha-256"),
      k("digest"),
      "a".repeat(64),
    ]),
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
    k("schema-version"),
    3,
    k("run-id"),
    runId,
    k("started-at"),
    "2026-10-26T00:00:00.000Z",
    k("ended-at"),
    "2026-10-26T00:01:00.000Z",
    k("plan"),
    map([
      k("cases"),
      [map([k("id"), k(caseId)])],
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
      map([k("functional"), map([k("mode"), k("release")])]),
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
      k("method-version"),
      1,
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
  "build retains failure evidence and prints only its UUID",
  testOptions,
  async (t) => {
    const fixture = createFrontend(t);
    fs.writeFileSync(fixture.compiler, failingCompiler, { mode: 0o755 });

    const args = ["build", "--build-timeout-ms", "10000"];
    const result = await runCli(fixture, args);
    assert.equal(result.exitCode, 1);
    const id = result.stdout.trim();
    assert.match(
      id,
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    assert.equal(result.stdout, `${id}\n`);
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
      assert.ok(output.includes("Preparation failed:"));
    }
    assert.match(result.stderr, /renderer build preparation failed/);
    assert.match(
      result.stderr,
      /:type :benches.render-wasm.node.build\/preparation-failed/,
    );
    assert.ok(result.stderr.includes(`:build-id "${id}"`));
    assert.ok(result.stderr.includes(`:log-path ${JSON.stringify(logPath)}`));
    assert.match(result.stderr, /:phase :renderer/);
  },
);

test("purge deletes only the selected artifacts", testOptions, async (t) => {
  const fixture = createFrontend(t);
  const retained = tree(fixture.managed);
  seedBuilds(fixture);
  seedRuns(fixture);
  const runs = tree(fixture.runs);

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
  assert.deepEqual(
    tree(fixture.managed).filter(
      ([name]) => !["runs", "builds"].includes(name),
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
  assert.deepEqual(
    tree(fixture.managed).filter(
      ([name]) => !["runs", "builds"].includes(name),
    ),
    retained,
  );

  seedRuns(fixture);
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
  assert.deepEqual(
    tree(fixture.managed).filter(
      ([name]) => !["runs", "builds"].includes(name),
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
    assert.equal(result.stdout, "");
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
      ["purge", "--only-ready-builds"],
      ["purge", "--only-builds", "ready"],
    ]) {
      const result = await runCli(fixture, args);
      assert.equal(result.exitCode, 1, args.join(" "));
      assert.equal(result.stdout, "");
      assert.match(
        result.stderr,
        /:type :benches.render-wasm.node.runner\/invalid-args/,
      );
      assert.deepEqual(tree(fixture.managed), original, args.join(" "));
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
    const comparison = await runCli(fixture, ["compare", baseline, candidate]);
    assert.equal(comparison.exitCode, 0, comparison.stderr);
    assert.match(comparison.stdout, /median change/);
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
  "compare supports explicit pairs and force from the CLI",
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

    const forced = await runCli(fixture, [
      "compare",
      baseline,
      candidate,
      "--cases",
      "a/pan,b/pan",
      "--force",
    ]);
    assert.equal(forced.exitCode, 0);
    assert.match(forced.stdout, /FORCED COMPARISON/);
    assert.match(forced.stdout, /Pair a\/pan,b\/pan/);
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
      "--force",
    ]);
    assert.equal(automatic.exitCode, 0);
    assert.match(automatic.stdout, /FORCED COMPARISON/);
    assert.match(automatic.stdout, /Pair a\/pan,a\/pan/);
  },
);
