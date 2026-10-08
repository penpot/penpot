const assert = require("node:assert/strict");
const { spawn, spawnSync } = require("node:child_process");
const { once } = require("node:events");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");

const helper = path.join(__dirname, "cargo-build.cjs");
const finished = { reason: "build-finished", success: true };

function fixture(t) {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "render-wasm-cargo-build-"),
  );
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const manifest = path.join(root, "Cargo.toml");
  const artifactDir = path.join(
    root,
    "target with spaces",
    "wasm32-unknown-emscripten",
    "release",
  );
  const outDir = path.join(artifactDir, "build", "render-active", "out");
  const staleOutDir = path.join(
    artifactDir,
    "build",
    "000-render-stale",
    "out",
  );
  for (const dir of [outDir, staleOutDir]) {
    fs.mkdirSync(dir, { recursive: true });
  }
  fs.writeFileSync(manifest, '[package]\nname = "render"\n');
  fs.writeFileSync(
    path.join(artifactDir, "render_wasm.js"),
    "export default {};\n",
  );
  fs.writeFileSync(
    path.join(outDir, "render_wasm_shared.js"),
    "active enum table\n",
  );
  fs.writeFileSync(
    path.join(staleOutDir, "render_wasm_shared.js"),
    "stale enum table\n",
  );

  const artifact = {
    reason: "compiler-artifact",
    package_id: `path+file://${root}#render@0.1.0`,
    manifest_path: manifest,
    target: { name: "render_wasm", kind: ["bin"] },
    features: ["default", "profile", "profile-macros", "profile-raf"],
    profile: {
      opt_level: "3",
      debuginfo: 0,
      debug_assertions: false,
      overflow_checks: false,
      test: false,
    },
    filenames: [path.join(artifactDir, "render_wasm.js")],
    fresh: false,
  };
  const buildScript = {
    reason: "build-script-executed",
    package_id: artifact.package_id,
    out_dir: outDir,
  };
  const otherScript = {
    ...buildScript,
    package_id:
      "registry+https://github.com/rust-lang/crates.io-index#render@0.1.0",
    out_dir: staleOutDir,
  };
  const otherArtifact = {
    ...artifact,
    package_id: otherScript.package_id,
    manifest_path: path.join(root, "dependency", "Cargo.toml"),
    features: ["stats"],
    profile: { ...artifact.profile, opt_level: "0", debug_assertions: true },
  };
  return {
    root,
    manifest,
    artifactDir,
    outDir,
    staleOutDir,
    artifact,
    buildScript,
    otherScript,
    otherArtifact,
    info: path.join(root, "build-info.json"),
  };
}

function cargoOutput(records) {
  return records.map((record) => JSON.stringify(record)).join("\n") + "\n";
}

function run(f, records) {
  return spawnSync(process.execPath, [helper, f.info, f.manifest], {
    cwd: f.root,
    input: cargoOutput(records),
    encoding: "utf8",
  });
}

for (const fresh of [false, true]) {
  test(`selects the root package's active enum table and actual features (fresh=${fresh})`, (t) => {
    const f = fixture(t);
    const result = run(f, [
      f.otherScript,
      f.otherArtifact,
      { ...f.artifact, fresh },
      f.buildScript,
      {
        ...f.otherScript,
        out_dir: path.join(f.root, "another dependency out"),
      },
      finished,
    ]);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, "");
    const info = JSON.parse(fs.readFileSync(f.info, "utf8"));
    assert.deepEqual(info, {
      package_id: f.artifact.package_id,
      features: f.artifact.features,
      profile: f.artifact.profile,
      out_dir: f.outDir,
      artifact_dir: f.artifactDir,
      shared_file: path.join(f.outDir, "render_wasm_shared.js"),
    });
    assert.equal(
      fs.readFileSync(info.shared_file, "utf8"),
      "active enum table\n",
    );
  });
}

test("records an empty actual feature set", (t) => {
  const f = fixture(t);
  const result = run(f, [
    f.buildScript,
    { ...f.artifact, features: [] },
    finished,
  ]);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(fs.readFileSync(f.info, "utf8")).features, []);
});

for (const profile of [
  {
    opt_level: "0",
    debuginfo: 2,
    debug_assertions: true,
    overflow_checks: true,
    test: false,
  },
  {
    opt_level: "z",
    debuginfo: 0,
    debug_assertions: false,
    overflow_checks: false,
    test: false,
  },
]) {
  test(`records the actual Cargo profile without changing fields (opt_level=${profile.opt_level})`, (t) => {
    const f = fixture(t);
    const result = run(f, [
      f.buildScript,
      { ...f.artifact, profile },
      finished,
    ]);
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(
      JSON.parse(fs.readFileSync(f.info, "utf8")).profile,
      profile,
    );
  });
}

test(
  "streams rendered compiler messages to stderr before Cargo finishes",
  { timeout: 10000 },
  async (t) => {
    const f = fixture(t);
    const child = spawn(process.execPath, [helper, f.info, f.manifest], {
      cwd: f.root,
    });
    t.after(() => {
      if (child.exitCode === null) child.kill();
    });
    child.stdout.resume();
    const closed = once(child, "close");
    const diagnostic = "warning: renderer diagnostic\n";
    let stderr = "";
    const rendered = new Promise((resolve, reject) => {
      child.stderr.on("data", (chunk) => {
        stderr += chunk;
        if (stderr.includes(diagnostic)) resolve();
      });
      child.on("error", reject);
      child.on("close", () => {
        if (!stderr.includes(diagnostic))
          reject(new Error(stderr || "Missing diagnostic"));
      });
    });
    child.stdin.write(
      cargoOutput([
        { reason: "compiler-message", message: { rendered: diagnostic } },
      ]),
    );
    await rendered;
    assert.equal(child.exitCode, null);
    assert.equal(fs.existsSync(f.info), false);
    child.stdin.end(cargoOutput([f.buildScript, f.artifact, finished]));
    const [code] = await closed;
    assert.equal(code, 0, stderr);
    assert.equal(stderr, diagnostic);
  },
);

const rejected = [
  [
    "failed Cargo build",
    (f) => [f.buildScript, f.artifact, { ...finished, success: false }],
  ],
  ["missing build-finished record", (f) => [f.buildScript, f.artifact]],
  ["missing root artifact", (f) => [f.otherScript, f.otherArtifact, finished]],
  [
    "missing matching build script",
    (f) => [f.otherScript, f.artifact, finished],
  ],
  [
    "missing out_dir",
    (f) => [{ ...f.buildScript, out_dir: undefined }, f.artifact, finished],
  ],
  [
    "missing actual features",
    (f) => [f.buildScript, { ...f.artifact, features: undefined }, finished],
  ],
  [
    "invalid actual features",
    (f) => [f.buildScript, { ...f.artifact, features: [42] }, finished],
  ],
  [
    "missing profile",
    (f) => [f.buildScript, { ...f.artifact, profile: undefined }, finished],
  ],
  [
    "null profile",
    (f) => [f.buildScript, { ...f.artifact, profile: null }, finished],
  ],
  [
    "non-object profile",
    (f) => [f.buildScript, { ...f.artifact, profile: "release" }, finished],
  ],
  [
    "array profile",
    (f) => [f.buildScript, { ...f.artifact, profile: [] }, finished],
  ],
  [
    "missing profile opt_level",
    (f) => [
      f.buildScript,
      { ...f.artifact, profile: { debug_assertions: false } },
      finished,
    ],
  ],
  [
    "invalid profile opt_level",
    (f) => [
      f.buildScript,
      { ...f.artifact, profile: { opt_level: 3, debug_assertions: false } },
      finished,
    ],
  ],
  [
    "missing profile debug_assertions",
    (f) => [
      f.buildScript,
      { ...f.artifact, profile: { opt_level: "3" } },
      finished,
    ],
  ],
  [
    "invalid profile debug_assertions",
    (f) => [
      f.buildScript,
      { ...f.artifact, profile: { opt_level: "3", debug_assertions: "false" } },
      finished,
    ],
  ],
  [
    "missing JS artifact",
    (f) => [f.buildScript, { ...f.artifact, filenames: [] }, finished],
  ],
  [
    "multiple root artifacts",
    (f) => [
      f.buildScript,
      f.artifact,
      { ...f.artifact, features: [] },
      finished,
    ],
  ],
  [
    "ambiguous root out_dir",
    (f) => [
      f.buildScript,
      { ...f.buildScript, out_dir: f.staleOutDir },
      f.artifact,
      finished,
    ],
  ],
];

for (const [name, records] of rejected) {
  test(`rejects ${name} and removes stale build facts`, (t) => {
    const f = fixture(t);
    fs.writeFileSync(f.info, '{"features":["stale"]}\n');
    const result = run(f, records(f));
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /ERROR:/);
    assert.equal(fs.existsSync(f.info), false);
  });
}

test("rejects a missing active enum table even when a stale table exists", (t) => {
  const f = fixture(t);
  fs.unlinkSync(path.join(f.outDir, "render_wasm_shared.js"));
  const result = run(f, [f.otherScript, f.buildScript, f.artifact, finished]);
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /render_wasm_shared\.js/);
  assert.equal(fs.existsSync(f.info), false);
});

test("rejects malformed Cargo JSON and removes stale build facts", (t) => {
  const f = fixture(t);
  fs.writeFileSync(f.info, '{"features":["stale"]}\n');
  const result = spawnSync(process.execPath, [helper, f.info, f.manifest], {
    cwd: f.root,
    input: "not Cargo JSON\n",
    encoding: "utf8",
  });
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /ERROR:/);
  assert.equal(fs.existsSync(f.info), false);
});
