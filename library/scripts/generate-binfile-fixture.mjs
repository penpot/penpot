#!/usr/bin/env node

// Generates the version 2 package the backend import test reads, so the
// compatibility with @penpot/library is proven against a package the library
// really writes instead of one rebuilt by hand in the test.
//
// Run from `library/`, with the bundle built:
//
//   pnpm run build
//   node scripts/generate-binfile-fixture.mjs
//
// The output is committed. Regenerate it whenever the library export changes
// shape, and keep the shapes simple: the test asserts what the reader restores,
// not a specific layout.

import * as fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import * as penpot from "#self";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(
  __dirname,
  "../../backend/test/backend_tests/test_files/library-v2.penpot",
);

// A 1x1 transparent PNG, the smallest real image the importer can restore.
const pixel = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);

const context = penpot.createBuildContext();
context.addFile({ name: "library fixture" });
context.addPage({ name: "page 1" });
context.addRect({ name: "rect 1", x: 0, y: 0, width: 100, height: 50 });
context.addRect({ name: "rect 2", x: 120, y: 0, width: 100, height: 50 });

context.addLibraryColor({
  name: "Red",
  color: "#FF0000",
  opacity: 1,
  path: "",
});

context.addLibraryTypography({
  name: "Body",
  fontId: "sourcesanspro",
  fontFamily: "Source Sans Pro",
  fontVariantId: "regular",
  fontSize: "16",
  fontWeight: "400",
  fontStyle: "normal",
  lineHeight: "1.4",
  letterSpacing: "0",
  textTransform: "none",
});

context.addBoard({ name: "board", x: 0, y: 100, width: 100, height: 100 });
context.addComponent({ componentId: context.genId(), name: "component" });
context.closeBoard();

context.addFileMedia(
  { name: "pixel.png", width: 1, height: 1 },
  new Blob([pixel], { type: "image/png" }),
);

const blob = await penpot.exportAsBlob(context, { version: 2 });
fs.writeFileSync(target, Buffer.from(await blob.arrayBuffer()));

console.log(`wrote ${target}`);
