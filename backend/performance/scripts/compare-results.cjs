#!/usr/bin/env node
//
// compare-results.js
//
// Compares two k6 JSON output files and reports performance regressions.
// Used for relative comparison: base branch vs PR branch in the same CI run.
//
// Usage:
//   node scripts/compare-results.js <baseline.json> <current.json>
//   node scripts/compare-results.js <baseline.json> <current.json> --threshold 20
//
// Exit codes:
//   0 - No regressions detected
//   1 - Regression detected (p95 increased > threshold)
//   2 - Error (invalid input, missing file, etc.)

const fs = require("fs");
const path = require("path");

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const DEFAULT_THRESHOLD = 20; // Fail if p95 increases > 20%
const CRITICAL_COMMANDS = [
  "get-file",
  "update-file",
  "login-with-password",
  "create-demo-profile",
  "get-file-libraries",
  "get-file-object-thumbnails",
];

// ---------------------------------------------------------------------------
// Parse k6 JSON output
// ---------------------------------------------------------------------------

function parseK6Json(filePath) {
  const content = fs.readFileSync(filePath, "utf-8");
  const lines = content.trim().split("\n");

  // Collect all http_req_duration points with rpc_command tag
  const durations = {}; // { rpc_command: [value, ...] }

  for (const line of lines) {
    try {
      const entry = JSON.parse(line);

      if (
        entry.type === "Point" &&
        entry.metric === "http_req_duration" &&
        entry.data?.tags?.rpc_command
      ) {
        const cmd = entry.data.tags.rpc_command;
        const value = entry.data.value;

        if (!durations[cmd]) {
          durations[cmd] = [];
        }
        durations[cmd].push(value);
      }
    } catch (e) {
      // Skip malformed lines
    }
  }

  return durations;
}

// ---------------------------------------------------------------------------
// Calculate percentiles
// ---------------------------------------------------------------------------

function percentile(values, p) {
  if (values.length === 0) return 0;

  const sorted = values.slice().sort((a, b) => a - b);
  const index = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, index)];
}

function calculateStats(values) {
  if (values.length === 0) {
    return { count: 0, p50: 0, p95: 0, p99: 0, min: 0, max: 0, avg: 0 };
  }

  const sorted = values.slice().sort((a, b) => a - b);
  const sum = values.reduce((a, b) => a + b, 0);

  return {
    count: values.length,
    p50: percentile(values, 50),
    p95: percentile(values, 95),
    p99: percentile(values, 99),
    min: sorted[0],
    max: sorted[sorted.length - 1],
    avg: sum / values.length,
  };
}

// ---------------------------------------------------------------------------
// Compare two results
// ---------------------------------------------------------------------------

function compareResults(baseline, current, threshold) {
  const results = [];
  const allCommands = new Set([
    ...Object.keys(baseline),
    ...Object.keys(current),
  ]);

  for (const cmd of allCommands) {
    const baseStats = calculateStats(baseline[cmd] || []);
    const currStats = calculateStats(current[cmd] || []);

    // Calculate p95 change percentage
    let p95Change = 0;
    if (baseStats.p95 > 0) {
      p95Change = ((currStats.p95 - baseStats.p95) / baseStats.p95) * 100;
    } else if (currStats.p95 > 0) {
      p95Change = 100; // New command with latency
    }

    const isCritical = CRITICAL_COMMANDS.includes(cmd);
    const isRegression = p95Change > threshold;

    results.push({
      command: cmd,
      isCritical,
      baseline: baseStats,
      current: currStats,
      p95Change: Math.round(p95Change * 100) / 100,
      isRegression,
    });
  }

  // Sort: regressions first, then by p95 change descending
  results.sort((a, b) => {
    if (a.isRegression !== b.isRegression) return b.isRegression - a.isRegression;
    return b.p95Change - a.p95Change;
  });

  return results;
}

// ---------------------------------------------------------------------------
// Print report
// ---------------------------------------------------------------------------

function printReport(results, threshold) {
  console.log("\n=== Performance Regression Report ===\n");
  console.log(`Threshold: p95 increase > ${threshold}%\n`);

  // Print table header
  const header = [
    "Command".padEnd(30),
    "Baseline p95".padStart(12),
    "Current p95".padStart(12),
    "Change".padStart(10),
    "Status".padStart(10),
  ].join(" | ");

  console.log(header);
  console.log("-".repeat(header.length));

  // Print results
  for (const r of results) {
    const baseP95 = `${Math.round(r.baseline.p95)}ms`;
    const currP95 = `${Math.round(r.current.p95)}ms`;
    const change = `${r.p95Change > 0 ? "+" : ""}${r.p95Change}%`;
    const status = r.isRegression ? "FAIL" : "OK";
    const critical = r.isCritical ? " *" : "";

    const row = [
      (r.command + critical).padEnd(30),
      baseP95.padStart(12),
      currP95.padStart(12),
      change.padStart(10),
      status.padStart(10),
    ].join(" | ");

    console.log(row);
  }

  // Print legend
  console.log("\n* = Critical command (always checked)");

  // Print regressions summary
  const regressions = results.filter((r) => r.isRegression);
  if (regressions.length > 0) {
    console.log(`\n❌ REGRESSION DETECTED: ${regressions.length} command(s) exceeded threshold`);
    for (const r of regressions) {
      console.log(`   - ${r.command}: p95 ${Math.round(r.baseline.p95)}ms → ${Math.round(r.current.p95)}ms (+${r.p95Change}%)`);
    }
  } else {
    console.log("\n✅ No regressions detected");
  }

  return regressions.length;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function main() {
  const args = process.argv.slice(2);

  // Parse arguments
  let baselineFile = null;
  let currentFile = null;
  let threshold = DEFAULT_THRESHOLD;

  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--threshold" && args[i + 1]) {
      threshold = parseInt(args[i + 1], 10);
      i++;
    } else if (!baselineFile) {
      baselineFile = args[i];
    } else if (!currentFile) {
      currentFile = args[i];
    }
  }

  // Validate arguments
  if (!baselineFile || !currentFile) {
    console.error("Usage: node compare-results.js <baseline.json> <current.json> [--threshold N]");
    console.error("");
    console.error("Arguments:");
    console.error("  baseline.json   k6 JSON output from base branch");
    console.error("  current.json    k6 JSON output from PR branch");
    console.error("  --threshold N   Fail if p95 increases > N% (default: 20)");
    process.exit(2);
  }

  // Check files exist
  if (!fs.existsSync(baselineFile)) {
    console.error(`Error: Baseline file not found: ${baselineFile}`);
    process.exit(2);
  }
  if (!fs.existsSync(currentFile)) {
    console.error(`Error: Current file not found: ${currentFile}`);
    process.exit(2);
  }

  // Parse files
  console.log(`Parsing baseline: ${path.basename(baselineFile)}`);
  const baseline = parseK6Json(baselineFile);
  const baseCommands = Object.keys(baseline).length;
  console.log(`  Found ${baseCommands} RPC commands`);

  console.log(`Parsing current:  ${path.basename(currentFile)}`);
  const current = parseK6Json(currentFile);
  const currCommands = Object.keys(current).length;
  console.log(`  Found ${currCommands} RPC commands`);

  if (baseCommands === 0 && currCommands === 0) {
    console.error("Error: No RPC command data found in either file");
    process.exit(2);
  }

  // Compare and report
  const results = compareResults(baseline, current, threshold);
  const regressionCount = printReport(results, threshold);

  // Exit with appropriate code
  process.exit(regressionCount > 0 ? 1 : 0);
}

main();
