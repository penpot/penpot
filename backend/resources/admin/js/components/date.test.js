// Contract test for the shared date formatter: run with plain
// node, no harness:
//
//   node --experimental-detect-module --test \
//     backend/resources/admin/js/components/date.test.js
//
// (The flag lets node read the panel's extensionless-ESM `.js`
// files; the panel itself has no build and no test runner.)

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { formatDate } from "./date.js";

describe("formatDate", () => {
  it("paints local time as YYYY/MM/DD HH:MM:SS AM/PM", () => {
    // 2026-10-04 15:04:05 local time, built without a zone suffix
    // so the expectation holds in any timezone.
    const out = formatDate("2026-10-04T15:04:05");
    assert.match(out, /^\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}:\d{2} (AM|PM)$/);
    assert.equal(out, "2026/10/04 03:04:05 PM");
  });

  it("renders midnight and noon on the 12-hour clock", () => {
    assert.match(formatDate("2026-01-02T00:00:00"), /12:00:00 AM$/);
    assert.match(formatDate("2026-01-02T12:00:00"), /12:00:00 PM$/);
  });

  it("echoes unparseable input back instead of crashing", () => {
    assert.equal(formatDate("not-a-date"), "not-a-date");
    assert.equal(formatDate(undefined), "");
    // null coerces to the epoch, same as the old toLocaleString
    // version: valid date in, formatted date out.
    assert.match(formatDate(null), /^\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}:\d{2} (AM|PM)$/);
  });
});
