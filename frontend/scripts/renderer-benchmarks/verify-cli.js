// Unscored end-to-end failure checks. Each child uses the real prepared build,
// server, browser, persistence and cleanup paths. No service is started here.
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { bounded, main } from "../renderer-benchmarks.js";

const filename = fileURLToPath(import.meta.url);

if (process.argv[2] === "--child") {
  const [, , , fault, output] = process.argv;
  const { chromium } = await import("playwright");
  const connect = chromium.connect.bind(chromium);
  let checkpoints = 0;
  chromium.connect = async (...args) => {
    const browser = await connect(...args);
    const newContext = browser.newContext.bind(browser);
    browser.newContext = async (...contextArgs) => {
      const context = await newContext(...contextArgs);
      await context.exposeBinding(
        "benchmarkFaultCheckpoint",
        async ({ page }) => {
          if (++checkpoints !== 2) return;
          if (fault === "interrupt") process.kill(process.pid, "SIGTERM");
          if (fault === "browser-disconnect")
            void browser.close().catch(() => {});
          if (fault === "page-crash") {
            const cdp = await context.newCDPSession(page);
            void cdp.send("Page.crash").catch(() => {});
          }
        },
      );
      await context.addInitScript(() => {
        let api;
        Object.defineProperty(globalThis, "rendererBenchmark", {
          get: () => api,
          set(value) {
            api = value;
            const attempt = value.runAttempt;
            value.runAttempt = async (...args) => {
              await globalThis.benchmarkFaultCheckpoint();
              return attempt(...args);
            };
          },
        });
      });
      return context;
    };
    return browser;
  };
  const args = [
    "--filter",
    "rects",
    "--warmups",
    "0",
    "--repetitions",
    "2",
    "--output",
    output,
  ];
  if (fault === "missing-server")
    args.push("--base-url", "http://127.0.0.1:1/");
  if (fault === "wrong-server")
    args.push("--base-url", "http://localhost:3000/not-this-workspace/");
  process.exitCode = await main(args);
} else {
  const directory = await fs.mkdtemp(
    path.join(os.tmpdir(), "renderer-cli-faults-"),
  );
  console.log(`Diagnostic results: ${directory}`);
  for (const fault of [
    "missing-browser",
    "missing-server",
    "wrong-server",
    "page-crash",
    "browser-disconnect",
    "interrupt",
  ]) {
    const output = path.join(directory, `${fault}.json`);
    const log = await fs.open(path.join(directory, `${fault}.log`), "w");
    const child = spawn(
      process.execPath,
      [filename, "--child", fault, output],
      {
        detached: true,
        stdio: ["ignore", log.fd, log.fd],
        env: {
          ...process.env,
          ...(fault === "missing-browser"
            ? {
                PLAYWRIGHT_BROWSERS_PATH: path.join(
                  directory,
                  "missing-browser",
                ),
              }
            : {}),
        },
      },
    );
    try {
      const exit = await bounded(
        new Promise((resolve, reject) => {
          child.once("error", reject);
          child.once("exit", (code, signal) => resolve({ code, signal }));
        }),
        180000,
      );
      assert.deepEqual(
        exit,
        { code: 1, signal: null },
        `${fault}: must exit with an execution failure`,
      );
      const result = JSON.parse(await fs.readFile(output, "utf8"));
      assert.ok(result.finishedAt);
      assert.equal(
        result.status,
        fault === "interrupt" ? "interrupted" : "failed",
      );
      assert.ok(
        result.cases.every((c) => c.attempts.length + c.unattempted === 2),
      );
      if (
        ["missing-browser", "missing-server", "wrong-server"].includes(fault)
      ) {
        assert.ok(result.cases.every((c) => c.status === "unattempted"));
        assert.match(
          result.error,
          fault === "missing-browser"
            ? /Chromium is missing/
            : fault === "missing-server"
              ? /Static server unavailable/
              : /current benchmark assets/,
        );
      } else {
        assert.equal(
          result.cases[0].attempts[0].status,
          "ok",
          "retain the successful attempt before injection",
        );
        assert.equal(result.cases[0].attempts[1].status, "failed");
        if (fault === "page-crash") {
          assert.ok(
            result.cases.slice(1).every((c) => c.status === "complete"),
            "independent cases recover in fresh contexts",
          );
        } else {
          assert.ok(
            result.cases.slice(1).every((c) => c.status === "unattempted"),
          );
        }
      }
      console.log(`PASS ${fault}: partial results and bounded exit (unscored)`);
    } finally {
      // Kill only this diagnostic child's process group if its watchdog expired.
      if (child.exitCode === null && child.signalCode === null)
        process.kill(-child.pid, "SIGKILL");
      await log.close();
    }
  }
}
