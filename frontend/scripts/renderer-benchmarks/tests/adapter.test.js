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
    _set_browser() {},
    _set_view_start() {},
    _set_view() {},
    _set_view_end() {},
    _render_from_cache() {},
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
  return { adapter, state, canvas, frames, timers, module };
}

test("disposing an adapter settles pending work and isolates late callbacks", async () => {
  const first = fixture();
  const second = fixture();
  const pending = first.adapter.renderFull();
  const lateFrame = [...first.frames.values()][0];
  first.canvas.dispatchEvent(
    Object.assign(new Event("wheel"), { deltaY: 1, offsetX: 10, offsetY: 20 }),
  );
  const lateTimer = [...first.timers.values()][0];
  first.adapter.dispose();
  first.adapter.dispose();
  lateFrame(16);
  lateTimer();
  assert.equal(await pending, false);
  assert.equal(first.frames.size + first.timers.size, 0);
  assert.deepEqual(first.state, {
    rendered: 0,
    cleaned: true,
    deleted: true,
    lost: true,
  });
  first.canvas.dispatchEvent(new Event("wheel"));
  assert.equal(first.timers.size, 0);
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
