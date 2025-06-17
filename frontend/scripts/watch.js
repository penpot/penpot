import fs from "node:fs/promises";
import ph from "node:path";

import log from "fancy-log";
import * as h from "./_helpers.js";
import ppt from "pretty-time";

const worker = h.startWorker();
let sass = null;

async function compileSassAll() {
  const start = process.hrtime();
  log.info("init: compile styles");

  sass = await h.compileSassAll(worker);
  let output = await h.concatSass(sass);
  await fs.writeFile("./resources/public/css/main.css", output);

  const end = process.hrtime(start);
  log.info("done: compile styles", `(${ppt(end)})`);
}

async function compileSass(path) {
  const start = process.hrtime();
  log.info("changed:", path);

  try {
    const result = await h.compileSass(worker, path, { modules: true });
    sass.index[result.outputPath] = result.css;

    const output = h.concatSass(sass);

    await fs.writeFile("./resources/public/css/main.css", output);

    const end = process.hrtime(start);
    log.info("done:", `(${ppt(end)})`);
  } catch (cause) {
    console.error(cause);
    const end = process.hrtime(start);
    log.error("error:", `(${ppt(end)})`);
  }
}

await fs.mkdir("./resources/public/css/", { recursive: true });
await compileSassAll();
await h.copyAssets();
await h.copyWasmPlayground();
await h.compileSvgSprites();
await h.compileTemplates();
await h.compilePolyfills();

log.info("watch: scss src (~)");

h.watch("src", h.isSassFile, async function (path) {
  if (path.includes("common")) {
    await compileSassAll(path);
  } else {
    await compileSass(path);
  }
});

log.info("watch: scss: resources (~)");
h.watch("resources/styles", h.isSassFile, async function (path) {
  log.info("changed:", path);
  await compileSassAll();
});

log.info("watch: templates (~)");
h.watch("resources/templates", null, async function (path) {
  log.info("changed:", path);
  await h.compileTemplates();
});

log.info("watch: translations (~)");
h.watch("translations", null, async function (path) {
  log.info("changed:", path);
  await h.compileTemplates();
});

log.info("watch: assets (~)");
h.watch(
  ["resources/images", "resources/fonts", "resources/plugins-runtime"],
  null,
  async function (path) {
    log.info("changed:", path);
    await h.compileSvgSprites();
    await h.copyAssets();
    await h.compileTemplates();
  },
);

log.info("watch: wasm playground (~)");
h.watch(["resources/wasm-playground"], null, async function (path) {
  log.info("changed:", path);
  await h.copyWasmPlayground();
});

worker.terminate();
