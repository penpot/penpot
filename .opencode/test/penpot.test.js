import assert from "node:assert/strict"
import test from "node:test"

import plugin from "../plugins/penpot.js"

test("exports only the OpenCode V2 plugin contract", () => {
  assert.equal(plugin.id, "penpot")
  assert.equal("server" in plugin, false)
})

test("registers the Penpot tools during setup", async () => {
  const tools = []
  const context = {
    location: { directory: "/tmp/opencode/penpot-plugin-test" },
    tool: {
      async transform(apply) {
        apply({
          add(tool) {
            tools.push(tool)
          },
        })
      },
    },
  }

  await plugin.setup(context)

  assert.deepEqual(
    tools.map((tool) => tool.name).sort(),
    ["paren-repair", "penpot-psql"],
  )
})
