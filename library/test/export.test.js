import assert from "node:assert/strict";
import test from "node:test";
import * as fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { Uint8ArrayReader, ZipReader, TextWriter } from "@zip.js/zip.js";

import * as penpot from "#self";


const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Buckets that can appear inside a `.penpot` file. Mirrors `lib.export/valid-buckets`.
const ALLOWED_BUCKETS = new Set([
  "file-media-object",
  "team-font-variant",
  "file-object-thumbnail",
  "file-thumbnail",
  "profile",
  "file-data",
  "file-data-fragment",
  "file-change",
]);

async function readEntries(bytes) {
  const reader = new ZipReader(new Uint8ArrayReader(bytes));
  const entries = await reader.getEntries();
  return new Map(entries.map((entry) => [entry.filename, entry]));
}

async function readJson(entry) {
  return JSON.parse(await entry.getData(new TextWriter()));
}

test("export a file with media writes one coherent object per media", async () => {
  const context = penpot.createBuildContext();

  const fileId = context.addFile({ name: "file with media" });
  context.addPage({ name: "page" });

  const jpeg = fs.readFileSync(
    path.join(__dirname, "..", "playground", "sample.jpg")
  );
  context.addFileMedia(
    { name: "photo.jpg", width: 32, height: 16 },
    new Blob([jpeg], { type: "image/jpeg" })
  );
  context.addFileMedia(
    { name: "icon.png", width: 8, height: 8 },
    new Blob([new Uint8Array([137, 80, 78, 71])], { type: "image/png" })
  );

  context.closeFile();

  const bytes = await penpot.exportAsBytes(context);
  const entries = await readEntries(bytes);

  const manifest = await readJson(entries.get("manifest.json"));
  assert.equal(manifest.type, "penpot/export-files");
  assert.ok(
    manifest.files.some((file) => file.id === fileId),
    "the manifest should list the exported file"
  );

  const fileMedia = [];
  for (const [name, entry] of entries) {
    const match = /^files\/([^/]+)\/media\/([^/]+)\.json$/.exec(name);
    if (match) {
      fileMedia.push(await readJson(entry));
    }
  }
  assert.equal(fileMedia.length, 2, "one metadata entry per added media");

  const objectJsonEntries = [...entries.entries()].filter(([name]) =>
    /^objects\/[^/]+\.json$/.test(name)
  );

  // (a) one `objects/<id>.json` entry per media referenced by the file.
  const referencedMediaIds = new Set(fileMedia.map((item) => item.mediaId));
  const objectIds = new Set(
    objectJsonEntries.map(([name]) => name.slice("objects/".length, -".json".length))
  );
  assert.equal(objectIds.size, 2);
  assert.deepEqual(objectIds, referencedMediaIds);

  for (const [name, entry] of objectJsonEntries) {
    const object = await readJson(entry);
    const id = name.slice("objects/".length, -".json".length);

    assert.equal(object.id, id, "the object id should match its entry name");
    // (b) media objects always live in the `file-media-object` bucket.
    assert.equal(object.bucket, "file-media-object");
    // (c) no object may use a bucket outside the allowed file buckets.
    assert.ok(ALLOWED_BUCKETS.has(object.bucket), `unexpected bucket: ${object.bucket}`);
    // The builder does not compute a hash, and the importer accepts its absence.
    assert.equal(object.hash, undefined);
    assert.equal(typeof object.size, "number");
    assert.equal(typeof object.contentType, "string");
  }

  // The object blob (not only its metadata) is part of the bundle.
  const objectBlobs = new Set(
    [...entries.keys()]
      .filter((name) => /^objects\/[^/]+\.[a-z0-9]+$/.test(name) && !name.endsWith(".json"))
      .map((name) => name.slice("objects/".length).replace(/\.[^.]+$/, ""))
  );
  assert.deepEqual(objectBlobs, referencedMediaIds);
});
