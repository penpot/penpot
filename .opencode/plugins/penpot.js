// Penpot opencode plugin: custom tools for Penpot development.
//
// Dual V1 + V2 implementation from a single file:
// - OpenCode V1 (>= 1.18.29) calls the default export's `server()` and uses
//   the returned `tool` map (built with the `tool()` helper from
//   `@opencode-ai/plugin`).
// - OpenCode V2 reads the default export's `id` and `setup()` and ignores
//   `server()`. Tools are registered via `ctx.tool.transform()` with JSON
//   Schema inputs, and `execute` returns `{ content }`.
//   See https://opencode.ai/v2/docs/build/plugins/migrate-v1
//
// NOTE: the V2 side intentionally does NOT
// `import { Plugin } from "@opencode/plugin"`. At runtime `Plugin.define` is
// the identity function, so a plain `{ id, setup }` object is equivalent, and
// skipping the import keeps this plugin dependency-free
// (`.opencode/package.json` is gitignored, so a new dependency declared there
// would not travel with this file).

import { tool } from "@opencode-ai/plugin"
import path from "path"
import { spawn } from "child_process"

function runCommand(command, args, options = {}) {
  const {
    cwd,
    env,
    stdin,
    closeStdin = false,
    successMessage = "Command executed successfully",
  } = options

  return new Promise((resolve) => {
    let stdout = ""
    let stderr = ""

    const proc = spawn(command, args, { cwd, env })

    proc.stdout.on("data", (data) => {
      stdout += data.toString()
    })

    proc.stderr.on("data", (data) => {
      stderr += data.toString()
    })

    proc.on("error", (error) => {
      resolve(`Error: ${error.message}`)
    })

    proc.on("close", (exitCode) => {
      const output =
        exitCode === 0
          ? stdout.trim() || successMessage
          : `Error (exit ${exitCode}): ${
              (stderr || stdout).trim() || "No error output"
            }`
      resolve(output)
    })

    // Close stdin so the child cannot wait on it indefinitely. `psql -c`
    // never reads stdin, so only the paren-repair pipe mode needs this, but
    // closing it unconditionally is harmless there.
    if (stdin !== undefined) {
      proc.stdin.end(stdin)
    } else if (closeStdin) {
      proc.stdin.end()
    }
  })
}

function executePsql(sql, useTestDb, cwd) {
  const host = process.env.PENPOT_DB_HOST || "postgres"
  const user = process.env.PENPOT_DB_USER || "penpot"
  const db = useTestDb
    ? "penpot_test"
    : process.env.PENPOT_DB_NAME || "penpot"
  const password = process.env.PENPOT_DB_PASSWORD || "penpot"

  const psqlArgs = ["-h", host, "-U", user, "-d", db, "-c", sql]

  return runCommand("psql", psqlArgs, {
    cwd,
    env: { ...process.env, PGPASSWORD: password },
    successMessage: "Query executed successfully",
  })
}

function executeParenRepair({ files, code }, directory) {
  const script = path.join(directory, "scripts/paren-repair")

  const fileList = files
    ? files
        .split(",")
        .map((file) => file.trim())
        .filter(Boolean)
    : []

  const childArgs =
    fileList.length > 0 ? [script, ...fileList] : [script]

  return runCommand("bb", childArgs, {
    cwd: directory,
    stdin: code,
    closeStdin: true,
    successMessage: "No changes needed",
  })
}

// --- V1 tool definitions (OpenCode V1 calls `server()` below) ---

const penpotPsqlTool = tool({
  description:
    "Execute a SQL command against the Penpot database. Uses the defaults from scripts/psql.",

  args: {
    sql: tool.schema
      .string()
      .describe("SQL command to execute"),

    test: tool.schema
      .boolean()
      .describe("Use the penpot_test database")
      .optional(),
  },

  async execute(args, context) {
    return executePsql(args.sql, args.test === true, context.worktree)
  },
})

const parenRepairTool = tool({
  description:
    "Fix mismatched parentheses/braces in Clojure files (.clj, .cljs, .cljc) then reformat with cljfmt.",

  args: {
    // A string is used instead of an array so OpenCode displays it
    // in the generic tool invocation.
    files: tool.schema
      .string()
      .describe(
        "Comma-separated file paths to fix, for example: frontend/src/app/config.cljs, backend/src/core.clj",
      )
      .optional(),

    code: tool.schema
      .string()
      .describe("Code string to fix via stdin")
      .optional(),
  },

  async execute(args, context) {
    return executeParenRepair(args, context.worktree)
  },
})

async function server() {
  return {
    tool: {
      "paren-repair": parenRepairTool,
      "penpot-psql": penpotPsqlTool,
    },
  }
}

// --- V2 setup (OpenCode V2 calls `setup()` and ignores `server()`) ---

const penpotPsqlInputSchema = {
  type: "object",
  properties: {
    sql: {
      type: "string",
      description: "SQL command to execute",
    },
    test: {
      type: "boolean",
      description: "Use the penpot_test database",
    },
  },
  required: ["sql"],
  additionalProperties: false,
}

const parenRepairInputSchema = {
  type: "object",
  properties: {
    // A string is used instead of an array so OpenCode displays it
    // in the generic tool invocation.
    files: {
      type: "string",
      description:
        "Comma-separated file paths to fix, for example: frontend/src/app/config.cljs, backend/src/core.clj",
    },
    code: {
      type: "string",
      description: "Code string to fix via stdin",
    },
  },
  additionalProperties: false,
}

async function setup(ctx) {
  // Plugin instance location. This is not the location of every session the
  // tools may run for, but it is the closest V2 equivalent of the V1
  // per-execution `context.worktree` (the repo checkout the plugin loaded
  // from), which is what both tools need as cwd / script base.
  const directory =
    ctx.location.directory ?? ctx.location.project?.canonical

  // Keep this callback synchronous: transforms are replayable state edits.
  // The async work happens later, inside each tool's `execute`.
  await ctx.tool.transform((editor) => {
    editor.add({
      name: "penpot-psql",
      description:
        "Execute a SQL command against the Penpot database. Uses the defaults from scripts/psql.",
      input: penpotPsqlInputSchema,
      async execute(input) {
        const content = await executePsql(
          input.sql,
          input.test === true,
          directory,
        )
        return { content }
      },
    })

    editor.add({
      name: "paren-repair",
      description:
        "Fix mismatched parentheses/braces in Clojure files (.clj, .cljs, .cljc) then reformat with cljfmt.",
      input: parenRepairInputSchema,
      async execute(input) {
        const content = await executeParenRepair(input, directory)
        return { content }
      },
    })
  })
}

export default {
  id: "penpot",
  setup,
  server,
}
