import test from "node:test";
import assert from "node:assert/strict";
import {
  drain,
  interact,
  restore,
} from "../../../resources/wasm-playground/benchmarks/protocol.js";

function runtime(results) {
  let time = 0;
  const flags = [];
  return {
    flags,
    now: () => time,
    frame: async () => (time += 16),
    sleep: async (ms) => {
      time += ms;
    },
    check: () => {},
    module: {
      _render: (_timestamp, flag) => {
        flags.push(flag);
        time += 2;
        return results.shift();
      },
      _set_view_start: () => {
        time += 1;
      },
      _set_view: () => {
        time += 1;
      },
      _render_from_cache: () => {
        time += 2;
      },
      _set_view_end: () => {
        time += 5;
      },
    },
  };
}

test("progressive rendering retains returned states and stops at Full", async () => {
  const rt = runtime([1, 3, 2]);
  const result = await drain(rt, { flags: 0 });
  assert.deepEqual(rt.flags, [0, 1, 3]);
  assert.equal(result.slices.length, 3);
  assert.ok(result.viewportReadyMs < result.fullMs);
});

test("immediate Full supplies both completion boundaries", async () => {
  const result = await drain(runtime([2]));
  assert.equal(result.viewportReadyMs, result.fullMs);
});

test("Partial can finish directly at Full without ViewportReady", async () => {
  const rt = runtime([1, 2]);
  const result = await drain(rt);
  assert.deepEqual(rt.flags, [0, 1]);
  assert.equal(result.viewportReadyMs, result.fullMs);
});

test("preparation drains to Full and applies SyncTiles only initially", async () => {
  const rt = runtime([1, 3, 2]);
  const result = await restore(rt, { scale: 1, x: 0, y: 0 });
  assert.deepEqual(rt.flags, [4, 1, 3]);
  assert.equal(result.slices.at(-1).frameType, 2);
});

test("None and unknown frame types fail explicitly", async () => {
  for (const frame of [0, 7, NaN]) {
    await assert.rejects(drain(runtime([frame])), /frame type/);
  }
});

test("finalization includes set_view_end but separates the settling delay", async () => {
  const rt = runtime([3, 2]);
  const result = await interact(rt, {
    frames: [{ scale: 1, x: 3, y: 4 }],
    settleMs: 100,
  });
  assert.equal(result.metrics.setViewEndMs, 5);
  assert.equal(result.metrics.settlingActualMs, 100);
  assert.ok(result.metrics.timeToViewportReadyMs >= 7);
  assert.ok(
    result.metrics.lastInputToFullMs >= 100 + result.metrics.timeToFullMs,
  );
  assert.deepEqual(rt.flags, [4, 3]);
});

test("cancellation stops further progressive work", async () => {
  const rt = runtime([1, 2]);
  rt.check = () => {
    if (rt.flags.length) throw new Error("Canceled");
  };
  await assert.rejects(drain(rt), /Canceled/);
  assert.equal(rt.flags.length, 1);
});
