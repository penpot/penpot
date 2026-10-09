#!/usr/bin/env node
// Regenerates common/src/app/common/tokenscript/bundle.cljc from a schema
// bundle produced by the tokenscript schemas CLI:
//
//   npx @tokens-studio/tokenscript-schemas bundle preset:css preset:cssColors --output /tmp/schemas.js
//   node scripts/tokenscript-bundle.mjs /tmp/schemas.js src/app/common/tokenscript/bundle.cljc
//
// The bundle is a JS module exporting `SCHEMAS`; this script evaluates it
// without the interpreter dependency and prints the schemas as EDN.

import { readFileSync, writeFileSync } from "node:fs";

const [, , input, output] = process.argv;
if (!input) {
  console.error("usage: tokenscript-bundle.mjs <schemas.js> [bundle.cljc]");
  process.exit(1);
}

const source = readFileSync(input, "utf-8")
  .replace(/^import .*$/gm, "")
  .replace(/export const SCHEMAS/, "const SCHEMAS")
  .replace(/export function makeConfig[\s\S]*$/, "");

const schemas = new Function(`${source}; return SCHEMAS;`)();

const version = (readFileSync(input, "utf-8").match(/Version: (\S+)/) || [])[1] || "unknown";

function key(k) {
  return /^[A-Za-z_$][\w.$-]*$/.test(k) && !k.includes("::") ? `:${k}` : JSON.stringify(k);
}

function edn(value, indent) {
  const pad = " ".repeat(indent);
  if (value === null || value === undefined) return "nil";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[]";
    return `[${value.map((v) => edn(v, indent + 1)).join(`\n${pad} `)}]`;
  }
  const entries = Object.entries(value);
  if (entries.length === 0) return "{}";
  return `{${entries
    .map(([k, v]) => `${key(k)} ${edn(v, indent + 1)}`)
    .join(`\n${pad} `)}}`;
}

const header = `;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.bundle
  "Tokenscript schemas shipped with Penpot: the color spaces, color
  functions and CSS named colors of the \`preset:css\` and \`preset:cssColors\`
  presets of ${version}.

  GENERATED FILE. Regenerate with \`common/scripts/tokenscript-bundle.mjs\`
  from the schema bundle; do not edit by hand.")

(def schemas
  `;

const content = `${header}${edn(schemas, 2)})\n`;
if (output) {
  writeFileSync(output, content);
} else {
  process.stdout.write(content);
}
