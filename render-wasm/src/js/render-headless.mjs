// Headless render-wasm smoke test: loads the artifact in Node (no browser/
// WebGL), boots via init_headless, renders a red rect to PNG, validates it.
// Requires render-wasm built with -sENVIRONMENT=web,node.
//   node render-wasm/src/js/render-headless.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const ARTIFACT_DIR = resolve(here, "../../../frontend/resources/public/js");
const JS_PATH = resolve(ARTIFACT_DIR, "render-wasm.js");
const WASM_PATH = resolve(ARTIFACT_DIR, "render-wasm.wasm");

// 4 + max(gradient 156, image 36, solid 4); solid fill = tag u8 @0, ARGB u32 @4.
const FILL_U8_SIZE = 160;

async function main() {
  const wasmBytes = readFileSync(WASM_PATH);
  const factory = (await import(pathToFileURL(JS_PATH).href)).default;

  const Module = await factory({
    instantiateWasm(imports, success) {
      WebAssembly.instantiate(wasmBytes, imports).then(({ instance }) => success(instance));
      return {};
    },
    locateFile: (p) => resolve(ARTIFACT_DIR, p),
    printErr: (s) => console.error("[wasm:err]", s),
  });

  const call = (name, ...args) => {
    const fn = Module["_" + name];
    if (typeof fn !== "function") throw new Error(`export _${name} missing`);
    return fn(...args);
  };

  call("init_headless", 800, 600);

  // 200x120 rect; any fixed non-nil uuid, as long as use/render agree.
  const [a, b, c, d] = [1, 2, 3, 4];
  call("init_shapes_pool", 1);
  call("use_shape", a, b, c, d);
  call("set_shape_selrect", 0, 0, 200, 120);

  // Solid opaque-red fill: [num_fills u8][3 pad][160-byte record].
  const ptr = call("alloc_bytes", 4 + FILL_U8_SIZE);
  const buf = Module.HEAPU8.subarray(ptr, ptr + 4 + FILL_U8_SIZE);
  buf.fill(0);
  buf[0] = 1;
  const fill = new DataView(buf.buffer, buf.byteOffset + 4, FILL_U8_SIZE);
  fill.setUint8(0, 0x00);
  fill.setUint32(4, 0xffff0000, true);
  call("set_shape_fills");

  // Result layout: [len u32][w u32][h u32][png...].
  const resPtr = call("render_shape_raster", a, b, c, d, 1.0);
  const u32 = Module.HEAPU32;
  const base = resPtr >>> 2;
  const [len, width, height] = [u32[base], u32[base + 1], u32[base + 2]];
  const png = Module.HEAPU8.slice(resPtr + 12, resPtr + 12 + len);
  call("free_bytes");

  const out = "/tmp/headless-render.png";
  writeFileSync(out, png);

  const MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  const okMagic = MAGIC.every((v, i) => png[i] === v);
  const ihdrW = (png[16] << 24) | (png[17] << 16) | (png[18] << 8) | png[19];
  const ihdrH = (png[20] << 24) | (png[21] << 16) | (png[22] << 8) | png[23];
  console.log(`render_shape_raster -> ${len} bytes (${width}x${height}); PNG ${ihdrW}x${ihdrH} -> ${out}`);

  if (!okMagic || ihdrW !== 200 || ihdrH !== 120 || len < 100) {
    throw new Error("unexpected PNG output");
  }
  console.log("OK: headless render in Node works.");
}

main().catch((e) => {
  console.error("FAILED:", e);
  process.exit(1);
});
