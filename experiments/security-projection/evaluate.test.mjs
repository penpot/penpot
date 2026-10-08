import test from "node:test";
import assert from "node:assert/strict";

import { median, summarize } from "./evaluate.mjs";

test("median returns the middle value, not the mean", () => {
  assert.equal(median([10, 30, 20]), 20);
  assert.equal(median([]), null);
});

test("summarize counts TP/FP/FN/TN at the threshold", () => {
  const rows = [
    { label: "phishing", prob: 0.9 },  // TP
    { label: "phishing", prob: 0.4 },  // FN
    { label: "benign", prob: 0.6 },    // FP
    { label: "benign", prob: 0.1 },    // TN
  ];
  const m = summarize(rows, 0.5);
  assert.deepEqual(
    { tp: m.tp, fp: m.fp, fn: m.fn, tn: m.tn },
    { tp: 1, fp: 1, fn: 1, tn: 1 }
  );
  assert.equal(m.precision, 0.5);
  assert.equal(m.recall, 0.5);
  assert.equal(m.f1, 0.5);
});

test("summarize is perfect when every case is on the right side", () => {
  const m = summarize(
    [
      { label: "phishing", prob: 0.98 },
      { label: "benign", prob: 0.03 },
    ],
    0.5
  );
  assert.equal(m.precision, 1);
  assert.equal(m.recall, 1);
  assert.equal(m.f1, 1);
});

test("summarize reports null precision when nothing is flagged", () => {
  const m = summarize(
    [
      { label: "phishing", prob: 0.2 },
      { label: "benign", prob: 0.1 },
    ],
    0.5
  );
  assert.equal(m.precision, null);
  assert.equal(m.recall, 0);
});

test("summarize moves the confusion matrix with the threshold", () => {
  const rows = [
    { label: "phishing", prob: 0.6 },
    { label: "benign", prob: 0.6 },
  ];
  assert.equal(summarize(rows, 0.5).fp, 1);
  assert.equal(summarize(rows, 0.7).fp, 0);
  assert.equal(summarize(rows, 0.7).fn, 1);
});
