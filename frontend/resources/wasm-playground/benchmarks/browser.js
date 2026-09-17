import { scenarios } from "../scenes/index.js";
import { drain, interact, restore } from "./protocol.js";
import { WEBGL_OPTIONS, validateMetrics } from "./contract.js";
import { summarizeRun, formatSummary } from "./statistics.js";

let session;
const canvas = document.querySelector("canvas");

function check(owner = session) {
  if (!owner || owner.canceled) throw new Error("Benchmark canceled");
  if (owner !== session) throw new Error("Stale benchmark operation");
  if (owner.lost || owner.gl?.isContextLost())
    throw new Error("WebGL context lost");
  if (performance.now() > owner.deadline) throw new Error("Operation timeout");
}

function runtime() {
  const owner = session;
  return {
    module: owner.module,
    now: () => performance.now(),
    recordSlice: (slice) => owner.slices.push(slice),
    recordCached: (slice) => owner.cachedSlices.push(slice),
    check: () => {
      if (owner !== session) throw new Error("Stale benchmark operation");
      check();
    },
    frame: () =>
      new Promise((resolve, reject) => {
        owner.cancelPending = reject;
        owner.raf = requestAnimationFrame((timestamp) => {
          owner.cancelPending = null;
          owner.raf = null;
          resolve(timestamp);
        });
      }),
    sleep: (ms) =>
      new Promise((resolve, reject) => {
        owner.cancelPending = reject;
        owner.timer = setTimeout(() => {
          owner.cancelPending = null;
          resolve();
        }, ms);
      }),
  };
}

function webglInfo(gl) {
  const extension = gl.getExtension("WEBGL_debug_renderer_info");
  const vendor = gl.getParameter(extension?.UNMASKED_VENDOR_WEBGL ?? gl.VENDOR);
  const renderer = gl.getParameter(
    extension?.UNMASKED_RENDERER_WEBGL ?? gl.RENDERER,
  );
  return {
    vendor,
    renderer,
    version: gl.getParameter(gl.VERSION),
    unmasked: Boolean(extension),
    acceleration: /swiftshader|llvmpipe|software|softpipe/i.test(
      `${vendor} ${renderer}`,
    )
      ? "software"
      : "unknown",
    options: gl.getContextAttributes(),
    drawingBuffer: {
      width: gl.drawingBufferWidth,
      height: gl.drawingBufferHeight,
    },
  };
}

async function initialize(benchmarkCase, options) {
  const scenario = scenarios.find(
    (item) => item.id === benchmarkCase.scenarioId,
  );
  if (!scenario)
    throw new Error(`Unknown scenario: ${benchmarkCase.scenarioId}`);
  // Construction and module script import deliberately precede initialization timing.
  const scene = scenario.createScene(
    benchmarkCase.params,
    benchmarkCase.sceneSeed,
  );
  const owner = {
    slices: [],
    cachedSlices: [],
    canceled: false,
    lost: false,
    deadline: performance.now() + options.timeoutMs,
    options,
    benchmarkCase,
  };
  session = owner;
  const { default: createModule } = await import(options.moduleUrl);
  check(owner);
  const moduleStart = performance.now();
  owner.module = await createModule();
  const moduleInitMs = performance.now() - moduleStart;
  check(owner);
  const contextStart = performance.now();
  canvas.style.width = `${options.viewport.width}px`;
  canvas.style.height = `${options.viewport.height}px`;
  canvas.width = Math.floor(options.viewport.width * options.dpr);
  canvas.height = Math.floor(options.viewport.height * options.dpr);
  const gl = canvas.getContext("webgl2", WEBGL_OPTIONS);
  if (!gl) throw new Error("WebGL2 is unavailable");
  owner.gl = gl;
  // Native initialization queries the unmasked renderer identity.
  gl.getExtension("WEBGL_debug_renderer_info");
  owner.onLost = (event) => {
    event.preventDefault();
    owner.lost = true;
  };
  canvas.addEventListener("webglcontextlost", owner.onLost);
  owner.handle = owner.module.GL.registerContext(gl, { majorVersion: 2 });
  owner.module.GL.makeContextCurrent(owner.handle);
  owner.module._init(options.viewport.width, options.viewport.height);
  owner.initialized = true;
  owner.module._set_render_options(0, options.dpr);
  owner.module._set_browser(1); // Rust Browser::Chrome
  owner.module._resize_viewbox(options.viewport.width, options.viewport.height);
  const contextInitMs = performance.now() - contextStart;
  // Diagnostic queries are outside renderer and upload timing.
  const environment = {
    webgl: webglInfo(gl),
    canvas: {
      cssWidth: canvas.clientWidth,
      cssHeight: canvas.clientHeight,
      width: canvas.width,
      height: canvas.height,
    },
    devicePixelRatio: globalThis.devicePixelRatio,
  };
  owner.environment = environment;
  const uploadStart = performance.now();
  scenario.upload(owner.module, scene);
  owner.module._set_canvas_background(0xfffabada);
  const view = benchmarkCase.initialView;
  owner.module._set_view(view.scale, view.x, view.y);
  owner.module._set_view_end(); // Bulk upload needs its initial tile index.
  const uploadMs = performance.now() - uploadStart;
  const first = await drain(runtime(), { flags: 0 });
  return {
    environment,
    metrics: {
      moduleInitMs,
      contextInitMs,
      uploadMs,
      timeToViewportReadyMs: first.viewportReadyMs,
      timeToFullMs: first.fullMs,
    },
    slices: first.slices,
    cachedSlices: [],
  };
}

function finish(result) {
  const invalidMetrics = validateMetrics(result.metrics);
  const slices = result.slices ?? [];
  const cachedSlices = result.cachedSlices ?? [];
  const timestamps = cachedSlices.map((slice) => slice.timestamp);
  return {
    ...result,
    status: Object.keys(invalidMetrics).length ? "invalid" : "ok",
    invalidMetrics,
    frameCounts: {
      Partial: slices.filter((s) => s.frameType === 1).length,
      ViewportReady: slices.filter((s) => s.frameType === 3).length,
      Full: slices.filter((s) => s.frameType === 2).length,
    },
    rafGapsMs: timestamps.slice(1).map((time, i) => time - timestamps[i]),
    renderRafGapsMs: slices
      .slice(1)
      .map((slice, i) => slice.timestamp - slices[i].timestamp),
    zeroSlices: [...slices, ...cachedSlices].filter((s) => s.durationMs === 0)
      .length,
    wasmMemoryBytes: session.module.HEAPU8.buffer.byteLength,
  };
}

async function runAttempt(benchmarkCase, options) {
  try {
    let result;
    if (!session) {
      result = await initialize(benchmarkCase, options);
      if (benchmarkCase.group === "fresh-module-context") return finish(result);
    }
    session.deadline = performance.now() + options.timeoutMs;
    await restore(runtime(), benchmarkCase.initialView);
    session.slices = [];
    session.cachedSlices = [];
    session.deadline = performance.now() + options.timeoutMs;
    result = await interact(runtime(), benchmarkCase.interaction);
    return finish({
      ...result,
      environment: { ...session.environment, webgl: webglInfo(session.gl) },
    });
  } catch (error) {
    return {
      status: "failed",
      metrics: {},
      invalidMetrics: {},
      slices: session?.slices ?? [],
      cachedSlices: session?.cachedSlices ?? [],
      environment: session?.environment,
      error: String(error?.stack ?? error),
    };
  }
}

function dispose() {
  if (!session) return;
  const owner = session;
  owner.canceled = true;
  owner.cancelPending?.(new Error("Benchmark canceled"));
  cancelAnimationFrame(owner.raf);
  clearTimeout(owner.timer);
  canvas.removeEventListener("webglcontextlost", owner.onLost);
  try {
    if (owner.initialized && !owner.lost) {
      owner.module._free_gpu_resources?.();
      owner.module._clean_up();
    }
  } finally {
    if (owner.handle !== undefined) owner.module.GL.deleteContext(owner.handle);
    owner.gl?.getExtension("WEBGL_lose_context")?.loseContext();
    session = null;
  }
}

globalThis.rendererBenchmark = {
  runAttempt,
  dispose,
  showResult(result) {
    globalThis.rendererBenchmarkResult = result;
    const report = document.querySelector("pre");
    report.textContent = [
      result.scope,
      ...(result.warnings ?? []),
      formatSummary(result.summary ?? summarizeRun(result)),
      "Run settings:",
      JSON.stringify(result.metadata, null, 2),
    ].join("\n\n");
    report.hidden = false;
  },
};
globalThis.rendererBenchmarkReady = true;
