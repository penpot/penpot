import test from "node:test";
import assert from "node:assert/strict";

test("page reports keep raw results and show the shared readable summary", async () => {
  const previous = globalThis.document;
  const report = { hidden: true, textContent: "" };
  globalThis.document = {
    querySelector: (selector) => (selector === "pre" ? report : {}),
  };
  try {
    await import("../../../resources/wasm-playground/benchmarks/browser.js");
    const result = {
      scope: "Renderer submission",
      metadata: {},
      warnings: [],
      cases: [
        {
          id: "rects/load",
          status: "complete",
          unattempted: 0,
          attempts: [
            { warmup: false, status: "ok", metrics: { timeToFullMs: 2 } },
          ],
        },
      ],
    };
    globalThis.rendererBenchmark.showResult(result);
    assert.equal(globalThis.rendererBenchmarkResult, result);
    assert.equal(report.hidden, false);
    assert.match(report.textContent, /median 2\.000/);
    assert.match(report.textContent, /1 valid, 0 invalid/);
    assert.match(report.textContent, /Renderer submission/);
  } finally {
    globalThis.document = previous;
    delete globalThis.rendererBenchmark;
    delete globalThis.rendererBenchmarkReady;
    delete globalThis.rendererBenchmarkResult;
  }
});
