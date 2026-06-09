#!/usr/bin/env node
import nreplClient from "nrepl-client";
import { readFileSync, writeFileSync, existsSync, unlinkSync } from "fs";
import path from "path";
import os from "os";

const DEFAULT_TIMEOUT = 120000;

// ============================================================================
// Session persistence
// ============================================================================

function sessionFilePath(host, port) {
  return path.join(os.tmpdir(), `penpot-nrepl-session-${host}-${port}`);
}

function readSession(host, port) {
  const fp = sessionFilePath(host, port);
  try {
    return readFileSync(fp, "utf8").trim() || null;
  } catch {
    return null;
  }
}

function writeSession(host, port, id) {
  writeFileSync(sessionFilePath(host, port), id, "utf8");
}

function deleteSession(host, port) {
  const fp = sessionFilePath(host, port);
  if (existsSync(fp)) unlinkSync(fp);
}

// ============================================================================
// nREPL helpers (promisified)
// ============================================================================

function nreplSend(con, msg) {
  return new Promise((resolve, reject) => {
    con.send(msg, (err, messages) => {
      if (err) {
        const text = Array.isArray(err)
          ? err.map((e) => (e && e.message) || String(e)).join("; ")
          : String(err);
        reject(new Error(text));
      } else {
        resolve(messages || []);
      }
    });
  });
}

function nreplEval({ host, port, code, sessionId, timeout = DEFAULT_TIMEOUT }) {
  return new Promise((resolve, reject) => {
    const con = nreplClient.connect({ host, port });
    let finished = false;

    const finish = (err, result) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try { con.end(); } catch (_) {}
      if (err) reject(err);
      else resolve(result);
    };

    const timer = setTimeout(() => {
      finish(new Error(`nREPL eval timed out after ${timeout}ms`));
    }, timeout);

    con.on("error", (err) => {
      finish(err);
    });

    con.once("connect", async () => {
      try {
        let sid = sessionId;

        if (!sid) {
          const msgs = await nreplSend(con, { op: "clone" });
          const m = msgs.find((m) => m["new-session"]);
          if (!m) throw new Error("Clone response missing new-session");
          sid = m["new-session"];
        }

        const messages = await nreplSend(con, { op: "eval", code, session: sid });
        finish(null, { messages, sessionId: sid });
      } catch (err) {
        finish(err);
      }
    });
  });
}

// ============================================================================
// Output formatting
// ============================================================================

function formatEvalMessages(messages) {
  const lines = [];
  let hasContent = false;
  for (const msg of messages) {
    if (msg.out) {
      lines.push(msg.out);
      hasContent = true;
    }
    if (msg.err) {
      lines.push(`[ERROR] ${msg.err}`);
      hasContent = true;
    }
    if (msg.value) {
      const ns = msg.ns ? ` (ns: ${msg.ns})` : "";
      lines.push(`=> ${msg.value}${ns}`);
      hasContent = true;
    }
  }
  if (!hasContent) {
    const statuses = messages.map((m) => m.status).filter(Boolean).flat();
    return `Evaluation completed. Status: ${statuses.join(", ") || "done"}`;
  }
  return lines.join("\n");
}

// ============================================================================
// CLI argument parsing
// ============================================================================

function parseArgs(argv) {
  const args = {
    port: 6064,
    host: "127.0.0.1",
    timeout: DEFAULT_TIMEOUT,
    help: false,
    resetSession: false,
    lastError: false,
    code: null,
  };

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "-p" || a === "--port") {
      const val = argv[++i];
      if (val === undefined) { console.error("Error: --port requires a value."); process.exit(1); }
      args.port = parseInt(val, 10);
    } else if (a === "-H" || a === "--host") {
      const val = argv[++i];
      if (val === undefined) { console.error("Error: --host requires a value."); process.exit(1); }
      args.host = val;
    } else if (a === "-t" || a === "--timeout") {
      const val = argv[++i];
      if (val === undefined) { console.error("Error: --timeout requires a value."); process.exit(1); }
      args.timeout = parseInt(val, 10);
    } else if (a === "--reset-session") {
      args.resetSession = true;
    } else if (a === "-e" || a === "--last-error") {
      args.lastError = true;
    } else if (a === "-h" || a === "--help") {
      args.help = true;
    } else {
      if (args.code === null) {
        args.code = a;
      } else {
        args.code += " " + a;
      }
    }
  }

  return args;
}

function printHelp() {
  const bin = path.basename(process.argv[1]);
  console.log(`Usage: ${bin} [options] [<code>]

Evaluate Clojure code via a running nREPL server. Session state (defs, in-ns)
persists across invocations via a stored session ID.

Options:
  -p, --port PORT             nREPL port (default: 6064)
  -H, --host HOST             nREPL host (default: 127.0.0.1)
  -t, --timeout MILLISECONDS  Timeout in milliseconds (default: 120000)
  --reset-session             Discard stored session and start fresh
  -e, --last-error            Evaluate *e to retrieve the last exception
  -h, --help                  Show this help message

Examples:
  ${bin} '(def x 42)'
  ${bin} 'x'
  ${bin} --reset-session '(def x 0)'
  ${bin} --last-error
  ${bin} <<'EOF'
  (def x 10)
  (+ x 20)
  EOF`);
}

function readStdin() {
  return new Promise((resolve) => {
    if (process.stdin.isTTY) {
      resolve("");
      return;
    }
    let data = "";
    process.stdin.setEncoding("utf-8");
    process.stdin.on("data", (chunk) => { data += chunk; });
    process.stdin.on("end", () => { resolve(data.trim()); });
  });
}

// ============================================================================
// Main
// ============================================================================

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.help) {
    printHelp();
    return;
  }

  if (isNaN(args.port) || args.port < 1 || args.port > 65535) {
    console.error("Error: invalid port number.");
    process.exit(1);
  }

  if (args.resetSession) {
    deleteSession(args.host, args.port);
  }

  let code = args.lastError ? "*e" : args.code;
  if (!code) {
    code = await readStdin();
  }

  if (!code) {
    console.error("Error: No code provided. Pass code as an argument or pipe it via stdin.");
    process.exit(1);
  }

  const storedSession = readSession(args.host, args.port);

  const { messages, sessionId } = await nreplEval({
    host: args.host,
    port: args.port,
    code,
    sessionId: storedSession,
    timeout: args.timeout,
  });

  writeSession(args.host, args.port, sessionId);
  console.log(formatEvalMessages(messages));
}

main().catch((err) => {
  console.error(`Error: ${err.message || err}`);
  process.exit(1);
});
