import {
  BACKGROUND,
  serializeChildren,
  solidFill,
  uploadBytes,
  validateCount,
} from "../scenes/shared.js";

export const contextOptions = Object.freeze({
  antialias: false,
  depth: true,
  alpha: true,
  stencil: true,
  preserveDrawingBuffer: true,
});

// All callbacks close over this instance; disposal cannot affect another module.
export function createRendererAdapter(module, scheduler = globalThis) {
  let disposed = false;
  let handle;
  let context;
  let frame;
  let timer;
  let cancelRender;
  let detach = () => {};
  const stopRender = () => {
    if (frame !== undefined) scheduler.cancelAnimationFrame(frame);
    frame = undefined;
    cancelRender?.();
    cancelRender = undefined;
  };

  function assignCanvas(canvas, dpr = 1) {
    if (disposed) throw new Error("Renderer has been disposed");
    context = canvas.getContext("webgl2", contextOptions);
    if (!context) throw new Error("WebGL2 is unavailable");
    handle = module.GL.registerContext(context, { majorVersion: 2 });
    module.GL.makeContextCurrent(handle);
    context.getExtension("WEBGL_debug_renderer_info");
    module._init(
      Math.round(canvas.width / dpr),
      Math.round(canvas.height / dpr),
    );
    module._set_render_options(0, dpr);
    // RawBrowser::Chrome = 1; use the same default browser as the benchmark.
    module._set_browser?.(1);
  }

  function renderFull(flags = 4) {
    if (disposed) return Promise.resolve(false);
    stopRender();
    return new Promise((resolve, reject) => {
      cancelRender = () => resolve(false);
      const step = (timestamp) => {
        frame = undefined;
        if (disposed) return resolve(false);
        try {
          const result = module._render(Math.trunc(timestamp), flags);
          if (result === 2) {
            cancelRender = undefined;
            resolve(true);
          } else if (result === 1 || result === 3) {
            flags = result;
            frame = scheduler.requestAnimationFrame(step);
          } else {
            throw new Error(`Unexpected renderer frame type: ${result}`);
          }
        } catch (error) {
          cancelRender = undefined;
          reject(error);
        }
      };
      frame = scheduler.requestAnimationFrame(step);
    });
  }

  function setupInteraction(canvas, initialView = { scale: 1, x: 0, y: 0 }) {
    detach();
    let view = { ...initialView };
    let panning = false;
    let active = false;
    let previous;
    const listeners = [];
    const listen = (type, callback, options) => {
      canvas.addEventListener(type, callback, options);
      listeners.push(() => canvas.removeEventListener(type, callback, options));
    };
    const finish = () => {
      timer = undefined;
      if (disposed || !active) return;
      active = false;
      module._set_view_end();
      renderFull().catch((error) => console.error(error));
    };
    const begin = () => {
      scheduler.clearTimeout(timer);
      stopRender();
      if (!active) module._set_view_start();
      active = true;
    };
    const update = () => {
      module._set_view(view.scale, view.x, view.y);
      module._render_from_cache();
      scheduler.clearTimeout(timer);
      timer = scheduler.setTimeout(finish, 100);
    };
    // Mirrors schedule-zoom! in app.main.ui.workspace.viewport.actions:
    // accumulate wheel zoom and flush at most one _set_view + _render_from_cache
    // per animation frame. When the playground migrates to ClojureScript, call
    // that function directly instead of duplicating this scheduling.
    const zoom = { factor: 1, pt: null, frame: undefined };
    const flushZoom = () => {
      zoom.frame = undefined;
      if (disposed) return;
      stopRender();
      const factor = zoom.factor;
      const [px, py] = zoom.pt;
      zoom.factor = 1;
      zoom.pt = null;
      const nextScale = Math.max(0.01, Math.min(100, view.scale * factor));
      view.x += px / nextScale - px / view.scale;
      view.y += py / nextScale - py / view.scale;
      view.scale = nextScale;
      update();
    };
    listen(
      "wheel",
      (event) => {
        event.preventDefault();
        begin();
        zoom.factor *= event.deltaY < 0 ? 1.1 : 0.9;
        zoom.pt = [event.offsetX, event.offsetY];
        if (zoom.frame === undefined) {
          zoom.frame = scheduler.requestAnimationFrame(flushZoom);
        }
      },
      { passive: false },
    );
    listen("pointerdown", (event) => {
      panning = true;
      previous = [event.clientX, event.clientY];
      canvas.setPointerCapture?.(event.pointerId);
    });
    listen("pointermove", (event) => {
      if (!panning) return;
      begin();
      view.x += (event.clientX - previous[0]) / view.scale;
      view.y += (event.clientY - previous[1]) / view.scale;
      previous = [event.clientX, event.clientY];
      update();
    });
    const endPan = () => {
      panning = false;
      scheduler.clearTimeout(timer);
      finish();
    };
    listen("pointerup", endPan);
    listen("pointercancel", endPan);
    detach = () => {
      for (const remove of listeners) remove();
      scheduler.clearTimeout(timer);
      if (zoom.frame !== undefined) scheduler.cancelAnimationFrame(zoom.frame);
      zoom.frame = undefined;
      panning = false;
      active = false;
    };
    return detach;
  }

  return {
    module,
    assignCanvas,
    renderFull,
    setupInteraction,
    useShape: (id) => module._use_shape(...uuidParts(id)),
    setShapeChildren: (ids) =>
      uploadBytes(
        module,
        serializeChildren(ids.map(uuidParts)),
        "_set_children",
      ),
    addShapeSolidFill: (color) =>
      uploadBytes(module, solidFill(color), "_add_shape_fill"),
    dispose() {
      if (disposed) return;
      disposed = true;
      detach();
      scheduler.clearTimeout(timer);
      stopRender();
      try {
        if (context && !context.isContextLost()) module._clean_up();
      } finally {
        if (handle !== undefined) module.GL.deleteContext(handle);
        context?.getExtension("WEBGL_lose_context")?.loseContext();
      }
    },
  };
}

function uuidParts(id) {
  if (Array.isArray(id)) return id;
  const hex = id.replaceAll("-", "");
  return Array.from({ length: 4 }, (_, index) =>
    Number.parseInt(hex.slice(index * 8, (index + 1) * 8), 16),
  );
}

export async function mountScenario(
  scenario,
  initModule,
  canvas,
  search = location.search,
) {
  const query = new URLSearchParams(search);
  const sourceCase = scenario.cases[0];
  const params = { ...sourceCase.params };
  const requestedCount = query.get(
    scenario.id === "texts" ? "texts" : "shapes",
  );
  if (requestedCount !== null) params.count = validateCount(requestedCount);
  const seed = Number(query.get("seed") ?? 42);
  // All random construction and serialization happen before the module exists.
  const scene = scenario.createScene(params, seed);
  const dpr = globalThis.devicePixelRatio || 1;
  canvas.width = Math.round(globalThis.innerWidth * dpr);
  canvas.height = Math.round(globalThis.innerHeight * dpr);
  const module = await initModule();
  const adapter = createRendererAdapter(module);
  try {
    adapter.assignCanvas(canvas, dpr);
    scenario.upload(module, scene);
    module._set_canvas_background(BACKGROUND);
    const { scale, x, y } = sourceCase.initialView;
    module._set_view(scale, x, y);
    module._set_view_end();
    await adapter.renderFull();
    adapter.setupInteraction(canvas, sourceCase.initialView);
  } catch (error) {
    adapter.dispose();
    throw error;
  }
  const dispose = () => {
    globalThis.removeEventListener("pagehide", dispose);
    adapter.dispose();
  };
  globalThis.addEventListener("pagehide", dispose, { once: true });
  return { ...adapter, dispose };
}

// Compatibility for the older, explicitly noncanonical comparison.html page.
// Its sequential synchronous setup can retain these names; new callers own adapters.
let legacyAdapter;
export function init(module) {
  legacyAdapter = createRendererAdapter(module);
}
export const assignCanvas = (...args) => legacyAdapter.assignCanvas(...args);
export const useShape = (...args) => legacyAdapter.useShape(...args);
export const setShapeChildren = (...args) =>
  legacyAdapter.setShapeChildren(...args);
export const addShapeSolidFill = (...args) =>
  legacyAdapter.addShapeSolidFill(...args);
export const setupInteraction = (...args) =>
  legacyAdapter.setupInteraction(...args);
export const getRandomInt = (min, max) =>
  Math.floor(Math.random() * (max - min)) + min;
export const getRandomFloat = (min, max) => Math.random() * (max - min) + min;
export const getRandomColor = () =>
  `#${getRandomInt(0, 0x1000000).toString(16).padStart(6, "0")}`;
export const hexToU32ARGB = (hex, opacity = 1) =>
  ((Math.floor(opacity * 255) << 24) | Number.parseInt(hex.slice(1), 16)) >>> 0;
