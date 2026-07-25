#!/usr/bin/env node
import { Command } from "commander";
import dotenv from "dotenv";
import { readFileSync, existsSync, createWriteStream } from "fs";
import path from "path";
import { createInterface } from "readline";

// ============================================================================
// Configuration
// ============================================================================

function loadConfig(envPath) {
  const envFile = envPath || ".env";

  if (existsSync(envFile)) {
    dotenv.config({ path: envFile });
  } else if (envPath) {
    console.error(`Error: .env file not found at ${envPath}`);
    process.exit(1);
  }

  const config = {
    apiUrl: process.env.PENPOT_API_URI,
    accessToken: process.env.PENPOT_ACCESS_TOKEN
  };

  if (!config.apiUrl) {
    console.error("Error: PENPOT_API_URI not set");
    console.error("\nCreate a .env file with:");
    console.error("  PENPOT_API_URI=http://localhost:3450");
    console.error("  PENPOT_ACCESS_TOKEN=<your-token>");
    process.exit(1);
  }

  if (!config.accessToken) {
    console.error("Error: PENPOT_ACCESS_TOKEN not set");
    console.error("\nCreate a .env file with:");
    console.error("  PENPOT_API_URI=http://localhost:3450");
    console.error("  PENPOT_ACCESS_TOKEN=<your-token>");
    console.error("\nGrant permission to your token:");
    console.error("  UPDATE access_token SET perms = ARRAY['error-reports:read']::text[]");
    console.error("  WHERE id = '<token-uuid>';");
    process.exit(1);
  }

  return config;
}

// ============================================================================
// RPC Client
// ============================================================================

async function rpcCall(config, method, params = {}) {
  const url = `${config.apiUrl}/api/main/methods/${method}`;

  let response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Authorization": `Token ${config.accessToken}`
      },
      body: JSON.stringify(params)
    });
  } catch (err) {
    if (err.cause?.code === "ECONNREFUSED") {
      console.error("Error: Cannot connect to server (connection refused)");
      console.error("The Penpot backend server is not running or not reachable.");
      console.error("\nCheck that:");
      console.error("  1. The backend server is running");
      console.error("  2. PENPOT_API_URI in .env points to the correct URL");
      console.error(`  Current URI: ${config.apiUrl}`);
      process.exit(1);
    }
    if (err.cause?.code === "ENOTFOUND") {
      console.error("Error: Cannot resolve server hostname");
      console.error(`The hostname in PENPOT_API_URI is not valid: ${config.apiUrl}`);
      process.exit(1);
    }
    throw new Error(`Network error: ${err.message}`);
  }

  if (!response.ok) {
    let errorData;
    try {
      errorData = await response.json();
    } catch {
      errorData = { message: response.statusText };
    }

    if (response.status === 401) {
      console.error("Error: Authentication failed (401)");
      console.error("Your access token may be invalid or expired.");
      process.exit(1);
    }

    if (response.status === 403) {
      console.error("Error: Authorization failed (403)");
      console.error("Your access token lacks the required permission: error-reports:read");
      console.error("\nGrant permission:");
      console.error("  UPDATE access_token SET perms = ARRAY['error-reports:read']::text[]");
      console.error("  WHERE id = '<token-uuid>';");
      process.exit(1);
    }

    if (response.status === 502) {
      console.error("Error: Server is down (502 Bad Gateway)");
      console.error("The Penpot backend server is not responding.");
      console.error("\nCheck that:");
      console.error("  1. The backend server is running");
      console.error("  2. PENPOT_API_URI in .env points to the correct URL");
      console.error(`  Current URI: ${config.apiUrl}`);
      process.exit(1);
    }

    if (response.status === 503) {
      console.error("Error: Service unavailable (503)");
      console.error("The Penpot backend server is temporarily unavailable.");
      console.error("Please wait a moment and try again.");
      process.exit(1);
    }

    if (response.status === 504) {
      console.error("Error: Gateway timeout (504)");
      console.error("The Penpot backend server did not respond in time.");
      console.error("\nCheck that:");
      console.error("  1. The backend server is running and responsive");
      console.error("  2. PENPOT_API_URI in .env points to the correct URL");
      console.error(`  Current URI: ${config.apiUrl}`);
      process.exit(1);
    }

    const code = errorData.code || "unknown";
    const message = errorData.message || errorData.hint || "Unknown error";
    throw new Error(`RPC error [${code}]: ${message}`);
  }

  return response.json();
}

// ============================================================================
// Hint Normalization
// ============================================================================

function normalizeHint(hint) {
  if (!hint) return hint;
  let h = String(hint)
    .replace(/[“”]/g, '"')
    .replace(/[‘’]/g, "'")
    .replace(/\\"/g, '"')
    .replace(/\s+/g, ' ')
    .trim();

  h = h.replace(/https?:\/\/"[^"]*"/g, '<uri>');
  h = h.replace(/https?:\/\/\S+/g, '<uri>');

  h = h.replace(/\b(?:file[-_]?id|file_id|file-id)\b\s*[=:]\s*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/gi, (match, id) => match.replace(id, '<file-id>'));
  h = h.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '<uuid>');
  h = h.replace(/\(\d+\)/g, '(<id>)');
  h = h.replace(/\b\d+(?:\.\d+)?(?:ms|s|m|h|d)(?:\s*\d+(?:\.\d+)?(?:ms|s|m|h|d))*\b/g, '<elapsed>');
  h = h.replace(/\s+/g, ' ').trim();

  return h || '(empty)';
}

// ============================================================================
// Output Formatting
// ============================================================================

function formatJson(data) {
  return JSON.stringify(data, null, 2);
}

function truncateHint(text, maxLength) {
  if (!text) return "-";
  if (text.length <= maxLength) return text;
  return text.substring(0, maxLength - 3) + "...";
}

const TABLE_HEADERS = ["ID", "Created At", "Source", "Profile ID", "Kind", "Hint"];

function formatTableRow(item, normalizeHints) {
  const hint = normalizeHints ? normalizeHint(item.hint) : item.hint;
  return [
    item.id || "-",
    item.createdAt || "-",
    item.source || "-",
    item.profileId || "-",
    item.kind || "-",
    truncateHint(hint, 60)
  ];
}

function computeColWidths(headerLines, dataRows) {
  return headerLines.map((h, i) => {
    const maxData = Math.max(...dataRows.map(r => (r[i] || "").toString().length));
    return Math.max(h.length, maxData);
  });
}

function padRow(row, colWidths) {
  return row.map((cell, i) => (cell || "-").toString().padEnd(colWidths[i])).join(" | ");
}

function formatListTable(data, hasMore = false, normalizeHints = false) {
  if (!data.items || data.items.length === 0) {
    return "No error reports found.";
  }

  const lines = [];
  lines.push(`Found ${data.items.length} error reports`);
  lines.push("");

  const rows = data.items.map(item => formatTableRow(item, normalizeHints));
  const colWidths = computeColWidths(TABLE_HEADERS, rows);

  lines.push(padRow(TABLE_HEADERS, colWidths));
  lines.push(colWidths.map(w => "-".repeat(w)).join("-+-"));
  lines.push(...rows.map(row => padRow(row, colWidths)));

  if (hasMore && data.nextSince && data.nextId) {
    lines.push("");
    lines.push(`More results: use --since ${data.nextSince} --since-id ${data.nextId}`);
  }

  return lines.join("\n");
}

function printTableHeaderStr(normalizeHints) {
  const colWidths = TABLE_HEADERS.map(h => Math.max(h.length, h === "ID" ? 36 : 10));
  return padRow(TABLE_HEADERS, colWidths) + "\n" + colWidths.map(w => "-".repeat(w)).join("-+-") + "\n";
}

function printTableRowStr(item, normalizeHints) {
  const row = formatTableRow(item, normalizeHints);
  return row.map(cell => (cell || "-").toString()).join(" | ");
}

function formatGetTable(data) {
  const lines = [];
  lines.push(`ID:              ${data.id || "(none)"}`);
  lines.push(`Created At:      ${data.createdAt || "(none)"}`);
  lines.push(`Source:          ${data.source || "(none)"}`);
  lines.push(`Profile ID:      ${data.profileId || "(none)"}`);
  lines.push(`Kind:            ${data.kind || "(none)"}`);
  lines.push(`Version:         ${data.version || "(none)"}`);
  lines.push(`Hint:            ${data.hint || "(none)"}`);
  lines.push(`HREF:            ${data.href || "(none)"}`);

  if (data.context) {
    lines.push(`--- Context ---`);
    const indented = data.context.split("\n").map(line => `  ${line}`).join("\n");
    lines.push(indented);
  }

  if (data.params && data.params !== "{}") {
    lines.push(`--- Params ---`);
    const indented = data.params.split("\n").map(line => `  ${line}`).join("\n");
    lines.push(indented);
  }

  if (data.props && data.props !== "{}") {
    lines.push(`--- Props ---`);
    const indented = data.props.split("\n").map(line => `  ${line}`).join("\n");
    lines.push(indented);
  }

  const body = data.trace || data.report;
  lines.push(`--- Report ---`);
  if (body) {
    const indented = body.split("\n").map(line => `  ${line}`).join("\n");
    lines.push(indented);
  } else {
    lines.push("  (none)");
  }

  return lines.join("\n");
}

// ============================================================================
// Commands
// ============================================================================

async function cmdList(config, args) {
  const params = {};

  if (args.limit !== undefined) params.limit = args.limit;
  if (args.source !== undefined) params.source = args.source;
  if (args.profileId) params["profile-id"] = args.profileId;
  if (args.kind) params.kind = args.kind;
  if (args.tenant) params.tenant = args.tenant;
  if (args.version) params.version = args.version;
  if (args.hint) params.hint = args.hint;

  // --from maps to server's --since (oldest boundary)
  // --since / --since-id are explicit cursor overrides
  if (args.since) {
    params.since = args.since;
  } else if (args.from) {
    params.since = args.from;
  }
  if (args.sinceId) params["since-id"] = args.sinceId;

  // --to maps to server's --until (newest boundary)
  if (args.to) params.until = args.to;

  if (args.all && args.format === "json") {
    console.error("Error: --all cannot be combined with --format json because --all streams output.");
    console.error("Use --format ndjson for streaming, --format table for human-readable streaming, or omit --all for a single JSON page.");
    process.exit(1);
  }

  // Output target
  const out = args.output
    ? createWriteStream(args.output)
    : process.stdout;
  const write = (text) => new Promise((resolve) => out.write(text, resolve));

  const streaming = args.all || args.format === "ndjson";
  let itemCount = 0;

  if (streaming) {
    // Streaming mode: print items as they arrive
    if (args.format === "table") {
      await write(printTableHeaderStr(args.normalizeHints));
    }

    let sinceParam = params.since;
    let sinceIdParam = params["since-id"];
    let hasMore = true;

    while (hasMore) {
      const currentParams = { ...params };
      if (sinceParam) currentParams.since = sinceParam;
      if (sinceIdParam) currentParams["since-id"] = sinceIdParam;

      const result = await rpcCall(config, "get-error-reports", currentParams);

      for (const item of result.items) {
        itemCount++;
        const outItem = args.normalizeHints ? { ...item, hint: normalizeHint(item.hint) } : item;
        if (args.format === "table") {
          await write(printTableRowStr(outItem, false) + "\n");
        } else {
          await write(JSON.stringify(outItem) + "\n");
        }
      }

      if (result.nextSince && result.nextId) {
        sinceParam = result.nextSince;
        sinceIdParam = result.nextId;
      } else {
        hasMore = false;
      }
    }
  } else {
    // Single page: buffer and print
    let allItems = [];
    let lastResult = null;
    let hasMore = true;
    let sinceParam = params.since;
    let sinceIdParam = params["since-id"];

    while (hasMore) {
      const currentParams = { ...params };
      if (sinceParam) currentParams.since = sinceParam;
      if (sinceIdParam) currentParams["since-id"] = sinceIdParam;

      lastResult = await rpcCall(config, "get-error-reports", currentParams);
      allItems = [...allItems, ...lastResult.items];

      if (args.all && lastResult.nextSince && lastResult.nextId) {
        sinceParam = lastResult.nextSince;
        sinceIdParam = lastResult.nextId;
      } else {
        hasMore = false;
      }
    }

    if (args.normalizeHints) {
      for (const item of allItems) {
        item.hint = normalizeHint(item.hint);
      }
    }

    itemCount = allItems.length;
    const output = { items: allItems };

    if (lastResult) {
      output.nextSince = lastResult.nextSince;
      output.nextId = lastResult.nextId;
    }

    if (args.format === "table") {
      const hasMore = lastResult.nextSince && lastResult.nextId;
      await write(formatListTable(output, hasMore, false) + "\n");
    } else {
      await write(formatJson(output) + "\n");
    }
  }

  if (args.output) {
    out.end();
    process.stderr.write(`Wrote ${itemCount} items to ${args.output}\n`);
  }
}

async function cmdGet(config, args) {
  const params = args.id ? { id: args.id } : { id: args.errorId };
  const result = await rpcCall(config, "get-error-report", params);

  if (args.format === "table") {
    console.log(formatGetTable(result));
  } else {
    console.log(formatJson(result));
  }
}

async function cmdStats(config, args) {
  let items;

  if (args.input) {
    // Read from file (supports JSON, JSON array, and NDJSON)
    const raw = readFileSync(args.input, "utf-8");
    items = parseItems(raw);
  } else if (!process.stdin.isTTY) {
    // Read from stdin (supports JSON, JSON array, and NDJSON)
    const raw = await readStdin();
    items = parseItems(raw);
  } else {
    // Fetch from API
    const params = {};
    if (args.limit !== undefined) params.limit = args.limit;
    if (args.from) params.since = args.from;
    if (args.to) params["until"] = args.to;

    items = [];
    let sinceParam = params.since;
    let sinceIdParam = undefined;
    let hasMore = true;

    while (hasMore) {
      const currentParams = { ...params };
      if (sinceParam) currentParams.since = sinceParam;
      if (sinceIdParam) currentParams["since-id"] = sinceIdParam;

      const result = await rpcCall(config, "get-error-reports", currentParams);
      items = [...items, ...result.items];

      if (result.nextSince && result.nextId) {
        sinceParam = result.nextSince;
        sinceIdParam = result.nextId;
      } else {
        hasMore = false;
      }
    }
  }

  if (items.length === 0) {
    console.log("No error reports found.");
    return;
  }

  // Normalize all hints for grouping
  for (const item of items) {
    item._normHint = normalizeHint(item.hint) || "(empty)";
  }

  const auditLogItems = items.filter((item) => item.source === "audit-log");

  // Aggregations
  const byHint = {};
  const byVersion = {};
  const bySource = {};
  const byKind = {};
  const byHour = {};
  const profiles = new Set();

  for (const item of items) {
    count(byHint, item._normHint);
    count(byVersion, item.version || "(unknown)");
    count(bySource, item.source || "(unknown)");

    const hour = item.createdAt ? item.createdAt.substring(11, 13) : "??";
    count(byHour, hour);

    if (item.profileId) profiles.add(item.profileId);
  }

  for (const item of auditLogItems) {
    count(byKind, item.kind || "(none)");
  }

  const burstWindows = buildBurstWindows(items, 5 * 60 * 1000);
  const heatmap = buildHeatmap(items);

  if (args.format === "json") {
    const stats = {
      total: items.length,
      uniqueProfiles: profiles.size,
      byHint: sortDesc(byHint),
      byVersion: sortDesc(byVersion),
      bySource: sortDesc(bySource),
      byKind: sortDesc(byKind),
      byHour: sortDesc(byHour),
      kindScope: "audit-log"
    };

    if (args.burst) {
      stats.bursts = burstWindows.map((burst) => ({
        start: burst.start,
        end: burst.end,
        count: burst.count,
        averagePerWindow: Number(burst.averagePerWindow.toFixed(2)),
        ratio: Number(burst.ratio.toFixed(2)),
        ids: burst.ids
      }));
    }

    if (args.heatmap) {
      stats.heatmap = heatmap;
    }

    console.log(formatJson(stats));
  } else {
    console.log(`=== Error Stats ===`);
    console.log(`Total: ${items.length}`);
    console.log(`Unique profiles: ${profiles.size}`);
    console.log("");

    printTable("By Signature (normalized hint)", byHint, items.length);
    printTable("By Source", bySource, items.length);
    printTable("By Kind (audit-log only)", byKind, auditLogItems.length || 1);
    printTable("By Version", byVersion, items.length);
    printTable("By Hour (UTC)", byHour, items.length);

    if (args.burst) {
      printBurstTable(burstWindows);
    }

    if (args.heatmap) {
      printHeatmapTable(heatmap);
    }
  }
}

function readStdin() {
  return new Promise((resolve) => {
    let data = "";
    const rl = createInterface({ input: process.stdin });
    rl.on("line", (line) => { data += line + "\n"; });
    rl.on("close", () => resolve(data));
  });
}

function parseItems(raw) {
  try {
    const data = JSON.parse(raw);
    return Array.isArray(data) ? data : data.items || [];
  } catch {
    // NDJSON: one JSON object per line
    return raw.split("\n")
      .filter(line => line.trim())
      .map(line => JSON.parse(line));
  }
}

function buildBurstWindows(items, windowMs) {
  const validItems = items
    .filter((item) => item.createdAt && !Number.isNaN(Date.parse(item.createdAt)))
    .map((item) => ({ ...item, _createdAtMs: Date.parse(item.createdAt) }))
    .sort((a, b) => a._createdAtMs - b._createdAtMs);

  if (validItems.length === 0) return [];

  const startMs = validItems[0]._createdAtMs;
  const endMs = validItems[validItems.length - 1]._createdAtMs;
  const windowCount = Math.max(1, Math.ceil((endMs - startMs) / windowMs));
  const averagePerWindow = validItems.length / windowCount;
  const threshold = Math.max(1, averagePerWindow * 3);
  const byWindow = new Map();

  for (const item of validItems) {
    const idx = Math.max(0, Math.min(windowCount - 1, Math.floor((item._createdAtMs - startMs) / windowMs)));
    if (!byWindow.has(idx)) byWindow.set(idx, []);
    byWindow.get(idx).push(item);
  }

  return Array.from(byWindow.entries())
    .filter(([, windowItems]) => windowItems.length > threshold)
    .map(([idx, windowItems]) => {
      const start = new Date(startMs + idx * windowMs).toISOString();
      const end = new Date(startMs + (idx + 1) * windowMs).toISOString();
      return {
        start,
        end,
        count: windowItems.length,
        averagePerWindow,
        ratio: windowItems.length / averagePerWindow,
        ids: windowItems.map((item) => item.id).slice(0, 12)
      };
    })
    .sort((a, b) => b.count - a.count || a.start.localeCompare(b.start));
}

function buildHeatmap(items) {
  const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  const heatmap = {};
  for (const item of items) {
    if (!item.createdAt || Number.isNaN(Date.parse(item.createdAt))) continue;
    const date = new Date(item.createdAt);
    const day = days[(date.getUTCDay() + 6) % 7];
    const hour = String(date.getUTCHours()).padStart(2, "0");
    const key = `${day}-${hour}`;
    heatmap[key] = (heatmap[key] || 0) + 1;
  }
  return heatmap;
}

function printBurstTable(bursts) {
  console.log("5-minute bursts (>3x average):");
  if (!bursts.length) {
    console.log("  No bursts detected.");
    console.log("");
    return;
  }

  const rows = bursts.map((burst) => [
    `${burst.start} → ${burst.end}`,
    burst.count,
    burst.averagePerWindow.toFixed(2),
    `${burst.ratio.toFixed(2)}x`,
    burst.ids.join(", ")
  ]);
  const widths = computeColWidths(["Window", "Count", "Avg", "Ratio", "Error IDs"], rows);
  console.log(padRow(["Window", "Count", "Avg", "Ratio", "Error IDs"], widths));
  console.log(widths.map((w) => "-".repeat(w)).join("-+-"));
  rows.forEach((row) => console.log(padRow(row, widths)));
  console.log("");
}

function printHeatmapTable(heatmap) {
  const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  const hours = Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, "0"));
  const max = Math.max(...hours.flatMap((hour) => days.map((day) => heatmap[`${day}-${hour}`] || 0)), 1);

  console.log("Heatmap (day-of-week × hour UTC):");
  console.log(`Max bucket: ${max}`);
  console.log("");
  console.log("       " + hours.map((hour) => hour.slice(0, 1)).join(" "));
  for (const day of days) {
    const cells = hours.map((hour) => {
      const value = heatmap[`${day}-${hour}`] || 0;
      if (value === 0) return "·";
      if (value / max >= 0.75) return "4";
      if (value / max >= 0.5) return "3";
      if (value / max >= 0.25) return "2";
      return "1";
    });
    console.log(`${day.padEnd(6)} ${cells.join(" ")}`);
  }
  console.log("");
  console.log("Legend: ·=0 1=low 2=medium 3=high 4=peak");
  console.log("");
}

function count(map, key) {
  map[key] = (map[key] || 0) + 1;
}

function sortDesc(map) {
  return Object.entries(map)
    .sort((a, b) => b[1] - a[1])
    .map(([key, count]) => ({ key, count }));
}

function printTable(title, map, total) {
  const entries = Object.entries(map).sort((a, b) => b[1] - a[1]);
  const maxCount = entries[0]?.[1] || 1;
  const barWidth = 30;

  console.log(`${title}:`);
  for (const [key, n] of entries) {
    const pct = ((100 * n) / total).toFixed(1);
    const barLen = Math.max(1, Math.round((n / maxCount) * barWidth));
    const bar = "█".repeat(barLen);
    const label = key.length > 60 ? key.substring(0, 57) + "..." : key;
    console.log(`  ${String(n).padStart(5)} (${pct.padStart(5)}%)  ${bar}  ${label}`);
  }
  console.log("");
}

// ============================================================================
// CLI Setup with Commander
// ============================================================================

const program = new Command();

program
  .name("error-reports")
  .description("Query Penpot error reports via RPC API")
  .version("1.0.0");

program
  .command("list")
  .description("List error reports with pagination and filters")
  .option("-l, --limit <n>", "Max items per page (default: 50, max: 200)", (value) => parseInt(value, 10), 50)
  .option("--from <date>", "ISO timestamp — oldest boundary (fetches items after this)")
  .option("--to <date>", "ISO timestamp — newest boundary (fetches items before this)")
  .option("--since <date>", "ISO timestamp — explicit cursor for manual pagination")
  .option("--since-id <uuid>", "Fetch errors after this ID (cursor pagination)")
  .option("-s, --source <name>", "Filter by source (legacy-v1, legacy-v2, logging, audit-log, rlimit)")
  .option("-p, --profile-id <uuid>", "Filter by profile ID")
  .option("-k, --kind <kind>", "Filter by kind (string)")
  .option("-t, --tenant <tenant>", "Filter by tenant (string)")
  .option("--version <version>", "Filter by version")
  .option("--hint <text>", "Filter by hint (ILIKE match)")
  .option("-a, --all", "Fetch all pages automatically (streams output)", false)
  .option("-f, --format <type>", "Output format (json for one page, table, or ndjson for streaming)", "table")
  .option("--normalize-hints", "Normalize hints by stripping dynamic values", false)
  .option("-o, --output <file>", "Write output to file instead of stdout")
  .option("--env <path>", "Custom .env file path")
  .action(async (options) => {
    const config = loadConfig(options.env);
    await cmdList(config, options);
  });

program
  .command("get")
  .description("Get a single error report by ID")
  .requiredOption("--id <uuid>", "Error report ID")
  .option("--error-id <id>", "Error report error-id")
  .option("-f, --format <type>", "Output format (json|table)", "table")
  .option("--env <path>", "Custom .env file path")
  .action(async (options) => {
    const config = loadConfig(options.env);
    await cmdGet(config, options);
  });

program
  .command("stats")
  .description("Compute error report statistics")
  .option("--from <date>", "Start of interval (ISO timestamp)")
  .option("--to <date>", "End of interval (ISO timestamp)")
  .option("--limit <n>", "Items per page (default: 200)", (value) => parseInt(value, 10), 200)
  .option("--input <file>", "Read from local JSON/NDJSON file instead of API")
  .option("--burst", "Detect 5-minute bursts above 3x the average window rate", false)
  .option("--heatmap", "Show day-of-week × hour-of-day heatmap", false)
  .option("-f, --format <type>", "Output format (json|table)", "table")
  .option("--env <path>", "Custom .env file path")
  .action(async (options) => {
    const config = loadConfig(options.env);
    await cmdStats(config, options);
  });

program.parse();
