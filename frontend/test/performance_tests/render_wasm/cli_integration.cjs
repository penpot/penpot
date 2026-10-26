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
    const output = await execute(process.execPath, [fixture.runner, ...args], {
      cwd: os.tmpdir(),
      encoding: "utf8",
      timeout: 15000,
      killSignal: "SIGKILL",
      maxBuffer: 1024 * 1024,
    });
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
  }
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
  assert.equal(fs.existsSync(fixture.builds), false);
  assert.deepEqual(tree(fixture.runs), runs);
  assert.deepEqual(
    tree(fixture.managed).filter(([name]) => name !== "runs"),
    retained,
  );

  seedBuilds(fixture);
  const builds = tree(fixture.builds);
  assert.deepEqual(await runCli(fixture, ["purge", "--only-runs"]), {
    exitCode: 0,
    stdout: "",
    stderr: "",
  });
  assert.equal(fs.existsSync(fixture.runs), false);
  assert.deepEqual(tree(fixture.builds), builds);
  assert.deepEqual(
    tree(fixture.managed).filter(([name]) => name !== "builds"),
    retained,
  );

  seedRuns(fixture);
  assert.deepEqual(await runCli(fixture, ["purge"]), {
    exitCode: 0,
    stdout: "",
    stderr: "",
  });
  assert.equal(fs.existsSync(fixture.builds), false);
  assert.equal(fs.existsSync(fixture.runs), false);
  assert.deepEqual(tree(fixture.managed), retained);
});

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
