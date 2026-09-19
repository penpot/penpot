import test from "node:test";
import assert from "node:assert/strict";
import { createRendererAdapter } from "../../../resources/wasm-playground/js/lib.js";

function fixture() {
  let next = 0;
  const frames = new Map();
  const timers = new Map();
  const scheduler = {
    requestAnimationFrame(callback) {
      frames.set(++next, callback);
      return next;
    },
    cancelAnimationFrame(id) {
      frames.delete(id);
    },
    setTimeout(callback) {
      timers.set(++next, callback);
      return next;
    },
    clearTimeout(id) {
      timers.delete(id);
    },
  };
  const state = { rendered: 0, cleaned: false, deleted: false, lost: false };
  const calls = { views: [], cached: 0, start: 0, end: 0 };
  const gl = {
    getExtension: (name) =>
      name === "WEBGL_lose_context"
        ? {
            loseContext() {
              state.lost = true;
            },
          }
        : null,
    isContextLost: () => state.lost,
  };
  const canvas = new EventTarget();
  Object.assign(canvas, { width: 1920, height: 1080, getContext: () => gl });
  const module = {
    GL: {
      registerContext: () => 1,
      makeContextCurrent() {},
      deleteContext() {
        state.deleted = true;
      },
    },
    _init() {},
    _set_render_options() {},
    _resize_viewbox() {},
    _set_browser() {},
    _set_view_start() {
      calls.start++;
    },
    _set_view(...args) {
      calls.views.push(args);
    },
    _set_view_end() {
      calls.end++;
    },
    _render_from_cache() {
      calls.cached++;
    },
    _render() {
      state.rendered++;
      return 2;
    },
    _clean_up() {
      state.cleaned = true;
    },
  };
  const adapter = createRendererAdapter(module, scheduler);
  adapter.assignCanvas(canvas);
  adapter.setupInteraction(canvas);
  return { adapter, state, calls, canvas, frames, timers, module };
}

for (const dpr of [1, 1.25, 2]) {
  test(`adapter initializes the full drawing buffer at DPR ${dpr}`, () => {
    const size = { width: 0, height: 0 };
    let rendererDpr = 1;
    const module = {
      GL: {
        registerContext: () => 1,
        makeContextCurrent() {},
      },
      _init(width, height) {
        Object.assign(size, { width, height });
      },
      _set_render_options(_flags, value) {
        rendererDpr = value;
      },
      _resize_viewbox(width, height) {
        // The renderer applies DPR when resizing, not when setting options.
        Object.assign(size, {
          width: width * rendererDpr,
          height: height * rendererDpr,
        });
      },
    };
    const canvas = {
      width: 1920 * dpr,
      height: 1080 * dpr,
      getContext: () => ({ getExtension() {} }),
    };

    createRendererAdapter(module).assignCanvas(canvas, dpr);

    assert.deepEqual(size, { width: canvas.width, height: canvas.height });
  });
}

test("wheel zoom flushes once per animation frame", () => {
  const f = fixture();
  const wheel = (deltaY, offsetX, offsetY) =>
    f.canvas.dispatchEvent(
      Object.assign(new Event("wheel"), { deltaY, offsetX, offsetY }),
    );
  const runFrame = () => {
    const [id] = f.frames.keys();
    const callback = f.frames.get(id);
    f.frames.delete(id);
    callback(16);
  };

  wheel(-1, 10, 20);
  wheel(-1, 30, 40);
  wheel(-1, 50, 60);

  assert.equal(f.frames.size, 1);
  assert.equal(f.calls.start, 1);
  assert.equal(f.calls.views.length, 0);
  assert.equal(f.calls.cached, 0);

  runFrame();

  assert.equal(f.calls.views.length, 1);
  assert.equal(f.calls.cached, 1);
  const [scale, x, y] = f.calls.views[0];
  assert.ok(Math.abs(scale - 1.1 ** 3) < 1e-9, `unexpected scale ${scale}`);
  assert.ok(
    Math.abs(x - (50 / 1.1 ** 3 - 50)) < 1e-9,
    `unexpected x anchor ${x}`,
  );
  assert.ok(
    Math.abs(y - (60 / 1.1 ** 3 - 60)) < 1e-9,
    `unexpected y anchor ${y}`,
  );
  assert.equal(f.timers.size, 1);

  // The flush releases the frame slot and resets the accumulated factor.
  wheel(-1, 10, 20);
  assert.equal(f.frames.size, 1);
  assert.equal(f.calls.cached, 1);

  runFrame();

  assert.equal(f.calls.views.length, 2);
  assert.equal(f.calls.cached, 2);
  assert.ok(
    Math.abs(f.calls.views[1][0] - 1.1 ** 4) < 1e-9,
    `unexpected scale ${f.calls.views[1][0]}`,
  );
  f.adapter.dispose();
});

test("wheel zoom clamps the accumulated factor to the scale bounds", () => {
  const f = fixture();
  const wheel = (deltaY) =>
    f.canvas.dispatchEvent(
      Object.assign(new Event("wheel"), { deltaY, offsetX: 10, offsetY: 20 }),
    );
  const runFrame = () => {
    const [id] = f.frames.keys();
    const callback = f.frames.get(id);
    f.frames.delete(id);
    callback(16);
  };

  for (let index = 0; index < 100; index++) wheel(-1);
  runFrame();
  assert.equal(f.calls.views[0][0], 100);

  for (let index = 0; index < 200; index++) wheel(1);
  runFrame();
  assert.equal(f.calls.views[1][0], 0.01);
  f.adapter.dispose();
});

test("pointer pan still renders on every move", () => {
  const f = fixture();
  f.canvas.dispatchEvent(
    Object.assign(new Event("pointerdown"), {
      clientX: 0,
      clientY: 0,
      pointerId: 1,
    }),
  );
  f.canvas.dispatchEvent(
    Object.assign(new Event("pointermove"), { clientX: 10, clientY: 20 }),
  );
  f.canvas.dispatchEvent(
    Object.assign(new Event("pointermove"), { clientX: 30, clientY: 40 }),
  );

  assert.equal(f.calls.views.length, 2);
  assert.equal(f.calls.cached, 2);
  assert.equal(f.frames.size, 0);
  f.adapter.dispose();
});

test("disposing an adapter settles pending work and isolates late callbacks", async () => {
  const first = fixture();
  const second = fixture();
  const pending = first.adapter.renderFull();
  const lateRender = [...first.frames.values()][0];
  first.canvas.dispatchEvent(
    Object.assign(new Event("wheel"), { deltaY: 1, offsetX: 10, offsetY: 20 }),
  );
  assert.equal(first.frames.size, 1);
  const lateZoom = [...first.frames.values()][0];
  first.adapter.dispose();
  first.adapter.dispose();
  lateRender(16);
  lateZoom(16);
  assert.equal(await pending, false);
  assert.equal(first.frames.size + first.timers.size, 0);
  assert.deepEqual(first.state, {
    rendered: 0,
    cleaned: true,
    deleted: true,
    lost: true,
  });
  assert.equal(first.calls.cached, 0);
  first.canvas.dispatchEvent(new Event("wheel"));
  assert.equal(first.frames.size + first.timers.size, 0);
  const completed = second.adapter.renderFull();
  [...second.frames.values()][0](32);
  assert.equal(await completed, true);
  assert.equal(second.state.cleaned, false);
  second.adapter.dispose();
});

test("adapter deletes its context even when native cleanup throws", () => {
  const f = fixture();
  f.module._clean_up = () => {
    throw new Error("cleanup failed");
  };
  assert.throws(() => f.adapter.dispose(), /cleanup failed/);
  assert.equal(f.state.deleted, true);
  assert.equal(f.state.lost, true);
});
