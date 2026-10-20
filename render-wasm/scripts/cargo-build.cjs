// Usage: cargo build --message-format=json-render-diagnostics ... |
//        node scripts/cargo-build.cjs <build-info.json> <root Cargo.toml>
const fs = require("node:fs");
const path = require("node:path");
const readline = require("node:readline");

async function main() {
  const [infoFile, manifestFile] = process.argv.slice(2);
  if (!infoFile || !manifestFile) {
    throw new Error(
      "Usage: cargo-build.cjs <build-info.json> <root Cargo.toml>",
    );
  }
  const manifest = path.resolve(manifestFile);
  // A failed invocation must not leave facts from an earlier build.
  fs.rmSync(infoFile, { force: true });

  const artifacts = [];
  const buildScripts = new Map();
  let succeeded = false;
  const lines = readline.createInterface({
    input: process.stdin,
    crlfDelay: Infinity,
  });
  for await (const line of lines) {
    if (!line.trim()) continue;
    const message = JSON.parse(line);
    if (!message || typeof message !== "object" || Array.isArray(message)) {
      throw new Error("Invalid Cargo JSON record");
    }
    switch (message.reason) {
      case "compiler-message":
        if (typeof message.message?.rendered === "string") {
          process.stderr.write(message.message.rendered);
        }
        break;
      case "compiler-artifact":
        if (
          message.target?.name === "render_wasm" &&
          typeof message.manifest_path === "string" &&
          path.resolve(message.manifest_path) === manifest
        ) {
          artifacts.push(message);
        }
        break;
      case "build-script-executed": {
        const outDirs = buildScripts.get(message.package_id) || new Set();
        outDirs.add(message.out_dir);
        buildScripts.set(message.package_id, outDirs);
        break;
      }
      case "build-finished":
        succeeded = message.success === true;
        break;
    }
  }

  if (!succeeded) throw new Error("Cargo build did not finish successfully");
  if (artifacts.length !== 1) {
    throw new Error("Expected one root render_wasm compiler-artifact");
  }
  const artifact = artifacts[0];
  if (
    typeof artifact.package_id !== "string" ||
    !artifact.package_id ||
    !Array.isArray(artifact.features) ||
    !artifact.features.every((feature) => typeof feature === "string")
  ) {
    throw new Error("Missing root renderer package_id or actual features");
  }
  if (
    !artifact.profile ||
    typeof artifact.profile !== "object" ||
    Array.isArray(artifact.profile) ||
    typeof artifact.profile.opt_level !== "string" ||
    typeof artifact.profile.debug_assertions !== "boolean"
  ) {
    throw new Error("Missing or malformed root renderer Cargo profile");
  }
  const outDirs = [...(buildScripts.get(artifact.package_id) || [])];
  if (outDirs.length !== 1 || typeof outDirs[0] !== "string" || !outDirs[0]) {
    throw new Error(
      "Expected one matching root renderer build-script-executed out_dir",
    );
  }
  const jsFiles = (
    Array.isArray(artifact.filenames) ? artifact.filenames : []
  ).filter(
    (file) =>
      typeof file === "string" && path.basename(file) === "render_wasm.js",
  );
  if (jsFiles.length !== 1)
    throw new Error("Missing root render_wasm.js artifact");

  const outDir = path.resolve(outDirs[0]);
  const sharedFile = path.join(outDir, "render_wasm_shared.js");
  fs.accessSync(sharedFile, fs.constants.R_OK);
  const info = {
    package_id: artifact.package_id,
    features: artifact.features,
    profile: artifact.profile,
    out_dir: outDir,
    artifact_dir: path.dirname(path.resolve(jsFiles[0])),
    shared_file: sharedFile,
  };
  fs.mkdirSync(path.dirname(infoFile), { recursive: true });
  const temporary = `${infoFile}.tmp`;
  try {
    fs.writeFileSync(temporary, JSON.stringify(info, null, 2) + "\n");
    fs.renameSync(temporary, infoFile);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}

main().catch((error) => {
  process.stderr.write(`ERROR: ${error.message}\n`);
  process.exitCode = 1;
  process.stdin.destroy();
});
