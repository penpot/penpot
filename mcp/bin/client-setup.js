#!/usr/bin/env node

// Client-setup entry point of the published @penpot/mcp package.
// Configures an MCP client to talk to the local Penpot MCP server by delegating
// to the add-mcp CLI. This launcher is deliberately independent of the server
// build/bootstrap: it only needs npx to fetch add-mcp on demand, so it does not
// require a source checkout, a prior install, or a running server.

const { spawnSync } = require("child_process");

/**
 * Runs the add-mcp CLI against the local Penpot MCP server URL.
 *
 * The server port is read from PENPOT_MCP_SERVER_PORT (default 4401), matching
 * the server's own default. add-mcp is fetched via npx using @latest on
 * purpose: MCP client config formats change frequently, so we always want the
 * newest add-mcp rather than a pinned version.
 *
 * forwardedArgs are passed straight through to add-mcp so callers can pick
 * agents, set headers, override the name or URL, etc. Two defaults are applied
 * conditionally so caller args always win: "penpot" as the server name (dropped
 * when the caller passes -n/--name) and the local server URL as the positional
 * target (dropped when the caller passes their own URL). Unlike a repeated
 * option, the URL is a positional, so add-mcp cannot "last-wins" two of them --
 * we must omit ours rather than append it. A caller URL is detected as an arg
 * that starts with a scheme (e.g. http://, https://); matching only at the start
 * avoids false positives from URLs embedded in --header/--env values.
 *
 * @param {string[]} forwardedArgs - additional arguments passed through to add-mcp
 */
function runClientSetup(forwardedArgs) {
  const port = process.env.PENPOT_MCP_SERVER_PORT || "4401";
  const defaultUrl = `http://localhost:${port}/mcp`;

  const hasName = forwardedArgs.some(
    (arg) => arg === "-n" || arg === "--name" || arg.startsWith("--name="),
  );
  const nameArgs = hasName ? [] : ["-n", "penpot"];

  const isUrl = (arg) => /^[a-zA-Z][\w+.-]*:\/\//.test(arg);
  const hasUrl = forwardedArgs.some(isUrl);
  const urlArgs = hasUrl ? [] : [defaultUrl];

  const args = ["-y", "add-mcp@latest", "-g", ...nameArgs, ...forwardedArgs, ...urlArgs];

  const result = spawnSync("npx", args, {
    stdio: "inherit",
    shell: process.platform === "win32",
  });

  if (result.error) {
    throw result.error;
  }
  if (typeof result.status === "number" && result.status !== 0) {
    const error = new Error(`add-mcp exited with code ${result.status}`);
    error.status = result.status;
    throw error;
  }
}

module.exports = { runClientSetup };

// run directly, e.g. via `pnpm run client-setup` or `node bin/client-setup.js`
if (require.main === module) {
  try {
    runClientSetup(process.argv.slice(2));
  } catch (error) {
    process.exit(error.status ?? 1);
  }
}
