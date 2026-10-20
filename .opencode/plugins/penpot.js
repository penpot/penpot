// Penpot OpenCode V2 plugin: custom tools for Penpot development.
//
// Tools are registered with `ctx.tool.transform()` and JSON Schema inputs.
// Keep this plugin dependency-free so the auto-discovered local plugin loads
// without project npm dependencies.

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
  // Use the plugin instance location as the working directory for both tools.
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
}
