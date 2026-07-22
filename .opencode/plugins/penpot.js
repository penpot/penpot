import { tool } from "@opencode-ai/plugin"
import path from "path"
import { spawn } from "child_process"

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
    const host = process.env.PENPOT_DB_HOST || "postgres"
    const user = process.env.PENPOT_DB_USER || "penpot"
    const db = args.test
      ? "penpot_test"
      : process.env.PENPOT_DB_NAME || "penpot"
    const password = process.env.PENPOT_DB_PASSWORD || "penpot"

    const psqlArgs = ["-h", host, "-U", user, "-d", db, "-c", args.sql]

    return new Promise((resolve) => {
      let stdout = ""
      let stderr = ""

      const proc = spawn("psql", psqlArgs, {
        cwd: context.worktree,
        env: { ...process.env, PGPASSWORD: password },
      })

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
            ? stdout.trim() || "Query executed successfully"
            : `Error (exit ${exitCode}): ${
                (stderr || stdout).trim() || "No error output"
              }`
        resolve(output)
      })
    })
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
    const script = path.join(context.worktree, "scripts/paren-repair")

    const files = args.files
      ? args.files
          .split(",")
          .map((file) => file.trim())
          .filter(Boolean)
      : []

    const paramInfo =
      files.length > 0
        ? `files=[${files.join(", ")}]`
        : args.code !== undefined
          ? `code=(${args.code.length} chars)`
          : "none"

    return new Promise((resolve) => {
      const childArgs =
        files.length > 0
          ? [script, ...files]
          : [script]

      const proc = spawn("bb", childArgs, {
        cwd: context.worktree,
      })

      let stdout = ""
      let stderr = ""

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
            ? stdout.trim() || "No changes needed"
            : `Error (exit ${exitCode}): ${
                (stderr || stdout).trim() || "No error output"
              }`

        resolve(output)
      })

      // Close stdin in all cases so the process cannot wait indefinitely.
      if (args.code !== undefined) {
        proc.stdin.end(args.code)
      } else {
        proc.stdin.end()
      }
    })
  },
})

export default async function plugin() {
  return {
    tool: {
      "paren-repair": parenRepairTool,
      "penpot-psql": penpotPsqlTool,
    },
  }
}











// import { tool } from "@opencode-ai/plugin"
// import path from "path"
// import { spawn } from "child_process"

// function formatFiles(files) {
//   if (files.length === 0) return "stdin"

//   // Keep the visible tool title reasonably short.
//   if (files.length <= 3) return files.join(", ")

//   return `${files.slice(0, 3).join(", ")} (+${files.length - 3} more)`
// }

// const parenRepairTool = tool({
//   description:
//     "Fix mismatched parentheses/braces in Clojure files, then reformat with cljfmt.",

//   args: {
//     files: tool.schema
//       .array(tool.schema.string())
//       .describe("Array of file paths to fix")
//       .optional(),

//     code: tool.schema
//       .string()
//       .describe("Code string to fix via stdin")
//       .optional(),
//   },

//   async execute(args, context) {
//     const script = path.join(context.worktree, "scripts/paren-repair")

//     const files = (args.files ?? []).map((file) => {
//       const absolute = path.isAbsolute(file)
//         ? file
//         : path.resolve(context.worktree, file)

//       return path.relative(context.worktree, absolute)
//     })

//     const targetSummary =
//       files.length > 0
//         ? formatFiles(files)
//         : args.code !== undefined
//           ? `stdin (${args.code.length} chars)`
//           : "no input"

//     // This updates the tool-call title immediately, while it is running.
//     await context.metadata({
//       title: `Paren repair: ${targetSummary}`,
//       metadata: {
//         files,
//         codeChars: args.code?.length,
//       },
//     })

//     const childArgs =
//       args.files && args.files.length > 0
//         ? [script, ...args.files]
//         : [script]

//     return new Promise((resolve) => {
//       const proc = spawn("bb", childArgs, {
//         cwd: context.worktree,
//       })

//       let stdout = ""
//       let stderr = ""

//       if (args.code !== undefined) {
//         proc.stdin.end(args.code)
//       }

//       proc.stdout.on("data", (data) => {
//         stdout += data.toString()
//       })

//       proc.stderr.on("data", (data) => {
//         stderr += data.toString()
//       })

//       proc.on("close", (exitCode) => {
//         const successful = exitCode === 0

//         const commandOutput = successful
//           ? stdout.trim() || "No changes needed"
//           : `Error (exit ${exitCode}): ${(stderr || stdout).trim()}`

//         const parameterOutput =
//           files.length > 0
//             ? `Files passed:\n${files.map((file) => `- ${file}`).join("\n")}`
//             : args.code !== undefined
//               ? `Input passed through stdin: ${args.code.length} characters`
//               : "No files or stdin input were passed"

//         resolve({
//           title: `Paren repair: ${targetSummary}`,
//           output: `${parameterOutput}\n\n${commandOutput}`,
//           metadata: {
//             files,
//             codeChars: args.code?.length,
//             exitCode,
//             successful,
//           },
//         })
//       })

//       proc.on("error", (error) => {
//         resolve({
//           title: `Paren repair failed: ${targetSummary}`,
//           output: [
//             files.length > 0
//               ? `Files passed:\n${files.map((file) => `- ${file}`).join("\n")}`
//               : `Input: ${targetSummary}`,
//             `Failed to start bb: ${error.message}`,
//           ].join("\n\n"),
//           metadata: {
//             files,
//             codeChars: args.code?.length,
//             successful: false,
//           },
//         })
//       })
//     })
//   },
// })

// export default async function plugin() {
//   return {
//     tool: {
//       "paren-repair": parenRepairTool,
//     },
//   }
// }
