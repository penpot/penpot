#!/usr/bin/env node
import { Command } from "commander";
import dotenv from "dotenv";
import { readFileSync, existsSync } from "fs";
import path from "path";

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
// Output Formatting
// ============================================================================

function formatJson(data) {
  return JSON.stringify(data, null, 2);
}

function formatTable(data, type) {
  if (type === "list") {
    return formatListTable(data);
  } else if (type === "get") {
    return formatGetTable(data);
  }
  return formatJson(data);
}

function formatListTable(data, hasMore = false) {
  if (!data.items || data.items.length === 0) {
    return "No error reports found.";
  }

  const lines = [];
  lines.push(`Found ${data.items.length} error reports`);
  lines.push("");

  const headers = ["ID", "Created At", "Source", "Profile ID", "Kind", "Hint"];
  const rows = data.items.map(item => [
    item.id || "-",
    item.createdAt || "-",
    item.source || "-",
    item.profileId || "-",
    item.kind || "-",
    truncateHint(item.hint, 60)
  ]);

  const colWidths = headers.map((h, i) => {
    const maxData = Math.max(...rows.map(r => (r[i] || "").toString().length));
    return Math.max(h.length, maxData);
  });

  const headerLine = headers.map((h, i) => h.padEnd(colWidths[i])).join(" | ");
  const separator = colWidths.map(w => "-".repeat(w)).join("-+-");
  const dataLines = rows.map(row => 
    row.map((cell, i) => (cell || "-").toString().padEnd(colWidths[i])).join(" | ")
  );

  lines.push(headerLine);
  lines.push(separator);
  lines.push(...dataLines);

  if (hasMore && data.nextSince && data.nextId) {
    lines.push("");
    lines.push(`More results: use --since ${data.nextSince} --since-id ${data.nextId}`);
  }

  return lines.join("\n");
}

function truncateHint(text, maxLength) {
  if (!text) return "-";
  if (text.length <= maxLength) return text;
  return text.substring(0, maxLength - 3) + "...";
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
  lines.push(`--- Report ---`);
  
  if (data.report) {
    const indentedReport = data.report.split("\n").map(line => `  ${line}`).join("\n");
    lines.push(indentedReport);
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
  if (args.since) params.since = args.since;
  if (args.sinceId) params["since-id"] = args.sinceId;
  if (args.source !== undefined) params.source = args.source;
  if (args.profileId) params["profile-id"] = args.profileId;
  if (args.kind) params.kind = args.kind;
  if (args.tenant) params.tenant = args.tenant;
  if (args.version) params.version = args.version;
  if (args.hint) params.hint = args.hint;

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

  const output = {
    items: allItems
  };

  if (!args.all && lastResult) {
    output.nextSince = lastResult.nextSince;
    output.nextId = lastResult.nextId;
  }

  if (args.format === "table") {
    const hasMore = !args.all && lastResult.nextSince && lastResult.nextId;
    console.log(formatListTable(output, hasMore));
  } else {
    console.log(formatJson(output));
  }
}

async function cmdGet(config, args) {
  const params = args.id ? { id: args.id } : { id: args.errorId };
  const result = await rpcCall(config, "get-error-report", params);

  if (args.format === "table") {
    console.log(formatTable(result, "get"));
  } else {
    console.log(formatJson(result));
  }
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
  .option("--since <date>", "ISO timestamp (fetch errors before this date)")
  .option("--since-id <uuid>", "Fetch errors before this ID (cursor pagination)")
  .option("-s, --source <name>", "Filter by source (legacy-v1, legacy-v2, logging, audit-log, rlimit)")
  .option("-p, --profile-id <uuid>", "Filter by profile ID")
  .option("-k, --kind <kind>", "Filter by kind (string)")
  .option("-t, --tenant <tenant>", "Filter by tenant (string)")
  .option("--version <version>", "Filter by version")
  .option("--hint <text>", "Filter by hint (ILIKE match)")
  .option("-a, --all", "Fetch all pages automatically", false)
  .option("-f, --format <type>", "Output format (json|table)", "table")
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

program.parse();
