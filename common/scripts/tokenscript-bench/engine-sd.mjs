// StyleDictionary engine, set up like frontend/src/app/main/data/style_dictionary.cljs.
//
//   node --expose-gc engine-sd.mjs <job.json>
//
// Job options:
//   nodeModules  frontend/node_modules (style-dictionary, @tokens-studio/sd-transforms)
//
// Each build creates a StyleDictionary instance for the token tree, runs
// `buildAllPlatforms` and reads the platform tokens, as penpot does. The
// default volume is StyleDictionary's in-memory filesystem, so nothing is
// written to disk.

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { runJob } from "./harness.mjs";

let StyleDictionary;
let version = "?";

// Resolves the ESM entry of a package installed in another node_modules.
async function importFrom(nodeModules, pkg) {
  const dir = join(nodeModules, pkg);
  const json = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
  let entry = json.exports?.["."] ?? json.exports ?? json.module ?? json.main;
  while (entry && typeof entry === "object")
    entry = entry.import ?? entry.default ?? entry.node;
  return {
    mod: await import(pathToFileURL(join(dir, entry)).href),
    version: json.version,
  };
}

const config = {
  platforms: {
    json: {
      transformGroup: "penpot",
      files: [{ format: "custom/json", destination: "penpot" }],
    },
  },
  preprocessors: ["tokens-studio"],
  log: {
    verbosity: "silent",
    warnings: "silent",
    errors: { brokenReferences: "console" },
  },
};

// penpot's ctob/tokens-tree: nested by name segments, leaves are the tokens.
function tree(fixture) {
  const root = {};
  for (const [name, t] of Object.entries(fixture.tokens)) {
    const path = name.split(".");
    let node = root;
    for (const seg of path.slice(0, -1)) node = node[seg] ??= {};
    node[path.at(-1)] = { name, type: t.type, value: t.value };
  }
  return root;
}

// A value still holding a `{reference}` in any string leaf.
const unresolved = (v) => {
  if (typeof v === "string") return /\{[^{}]+\}/.test(v);
  if (v && typeof v === "object") return Object.values(v).some(unresolved);
  return false;
};

const engine = {
  version: () => version,

  async init(job) {
    const sd = await importFrom(job.nodeModules, "style-dictionary");
    const sdt = await importFrom(
      job.nodeModules,
      "@tokens-studio/sd-transforms",
    );
    version = `style-dictionary ${sd.version}, sd-transforms ${sdt.version}`;
    StyleDictionary = sd.mod.default;
    sdt.mod.register(StyleDictionary);
    StyleDictionary.registerTransformGroup({
      name: "penpot",
      transforms: sdt.mod
        .getTransforms()
        .concat(["ts/color/css/hexrgba", "ts/color/modifiers", "color/css"]),
    });
    StyleDictionary.registerFormat({
      name: "custom/json",
      format: (res) => res.dictionary.tokens,
    });
    // brokenReferences "console" logs every unresolved reference; keep the output readable.
    console.error = () => {};
  },

  build(fixture) {
    const tokens = tree(fixture);
    return async () => {
      const sd = new StyleDictionary({ ...config, tokens });
      await sd.buildAllPlatforms("json");
      const dictionary = await sd.getPlatformTokens("json");
      return dictionary.allTokens;
    };
  },

  summarize(allTokens, fixture) {
    const errors = allTokens.filter((t) => unresolved(t.value)).length;
    const missing = Object.keys(fixture.tokens).length - allTokens.length;
    return {
      resolved: allTokens.length - errors,
      errors: errors + Math.max(0, missing),
    };
  },
};

await runJob(engine, process.argv[2]);
