// TypeScript interpreter engine.
//
//   node --expose-gc engine-ts.mjs <job.json>
//
// Job options:
//   interpreter  path to the interpreter entry (dist/lib/index.js or the
//                package installed next to the old @penpot/tokenscript)
//   schemas      path to the old package's schemas.js (exports SCHEMAS)
//
// Drives the interpreter the way penpot's old adapter did: one Config built
// from the bundled schemas, `processTokens` over a Map of
// name -> {$type, $value}, with a builder collecting the results.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";
import { runJob } from "./harness.mjs";

let ts;
let config;
let version = "?";

function packageVersion(entry) {
  let dir = dirname(entry);
  for (let i = 0; i < 5; i++) {
    try {
      const pkg = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
      if (pkg.name === "@tokens-studio/tokenscript-interpreter")
        return pkg.version;
    } catch {}
    dir = dirname(dir);
  }
  return "?";
}

function input(fixture) {
  const map = new Map();
  for (const [name, t] of Object.entries(fixture.tokens))
    map.set(name, { $type: t.type, $value: t.value });
  return map;
}

function collector() {
  const counts = { resolved: 0, errors: 0 };
  return {
    counts,
    builder: {
      onResolve() {
        counts.resolved++;
      },
      onError(_name, _error, _value, metadata) {
        if (!metadata?.isSubField) counts.errors++;
      },
      getResult() {
        return null;
      },
    },
  };
}

const engine = {
  version: () => version,

  async init(job) {
    ts = await import(pathToFileURL(job.interpreter).href);
    const { SCHEMAS } = await import(pathToFileURL(job.schemas).href);
    version = packageVersion(job.interpreter);
    config = new ts.Config().registerSchemas(SCHEMAS);
  },

  build(fixture) {
    const tokens = input(fixture);
    return () => {
      const { counts, builder } = collector();
      ts.processTokens(tokens, { config, builder });
      return counts;
    };
  },

  summarize(counts) {
    return counts;
  },

  update(fixture) {
    const { name, type, values } = fixture.update;
    const { resolver } = ts.processTokens(input(fixture), { config });
    // Alternate between two values so every call is a real change.
    return (i) =>
      resolver.updateToken({
        tokenPath: name,
        tokenData: { $type: type, $value: values[i % 2 === 0 ? 1 : 0] },
      });
  },
};

await runJob(engine, process.argv[2]);
