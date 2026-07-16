import assert from "node:assert/strict";
import test from "node:test";
import * as fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { BlobReader, ZipReader, TextWriter } from "@zip.js/zip.js";

import * as penpot  from "#self";


const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

test("create empty context", () => {
  const context = penpot.createBuildContext();
  assert.ok(context);
});

test("create context with single file", () => {
  const context = penpot.createBuildContext();
  context.addFile({name: "sample"});

  const internalState = context.getInternalState();

  // console.log(internalState);

  assert.ok(internalState.files);
  assert.equal(typeof internalState.files, "object");
  assert.equal(typeof internalState.currentFileId, "string");

  const file = internalState.files[internalState.currentFileId];
  assert.ok(file);
});

test("create context with two file", () => {
  const context = penpot.createBuildContext();

  const fileId_1 = context.addFile({name: "sample 1"});
  const fileId_2 = context.addFile({name: "sample 2"});

  const internalState = context.getInternalState();

  // console.log(internalState.files[fileId_1])

  assert.ok(internalState.files[fileId_1]);
  assert.ok(internalState.files[fileId_2]);
  assert.equal(internalState.files[fileId_1].name, "sample 1");
  assert.equal(internalState.files[fileId_2].name, "sample 2");

  const file = internalState.files[fileId_2];

  assert.ok(file.data);
  assert.ok(file.data.pages);
  assert.ok(file.data.pagesIndex);
  assert.equal(file.data.pages.length, 0)
});

test("create context with two file and relation between", () => {
  const context = penpot.createBuildContext();

  const fileId_1 = context.addFile({name: "sample 1"});
  const fileId_2 = context.addFile({name: "sample 2"});

  context.addRelation(fileId_1, fileId_2);

  const internalState = context.getInternalState();

  assert.ok(internalState.files[fileId_1]);
  assert.ok(internalState.files[fileId_2]);
  assert.equal(internalState.files[fileId_1].name, "sample 1");
  assert.equal(internalState.files[fileId_2].name, "sample 2");

  assert.ok(internalState.relations[fileId_1]);
  assert.equal(internalState.relations[fileId_1], fileId_2);

  const file = internalState.files[fileId_2];

  assert.ok(file.data);
  assert.ok(file.data.pages);
  assert.ok(file.data.pagesIndex);
  assert.equal(file.data.pages.length, 0)
});


test("create context with file and page", () => {
  const context = penpot.createBuildContext();

  const fileId = context.addFile({name: "file 1"});
  const pageId = context.addPage({name: "page 1"});

  const internalState = context.getInternalState();

  const file = internalState.files[fileId];

  assert.ok(file, "file should exist");

  assert.ok(file.data);
  assert.ok(file.data.pages);

  assert.equal(file.data.pages.length, 1);

  const page = file.data.pagesIndex[pageId];

  assert.ok(page, "page should exist");
  assert.ok(page.objects, "page objects should exist");
  assert.equal(page.id, pageId);


  const rootShape = page.objects["00000000-0000-0000-0000-000000000000"];
  assert.ok(rootShape, "root shape should exist");
  assert.equal(rootShape.id, "00000000-0000-0000-0000-000000000000");
});

test("create context with color", () => {
  const context = penpot.createBuildContext();

  const fileId = context.addFile({name: "file 1"});
  const pageId = context.addPage({name: "page 1"});

  const colorId = context.genId();

  const params = {
    color: '#000000',
    gradient: undefined,
    id: colorId,
    image: undefined,
    name: 'Black-8',
    opacity: 0.800000011920929,
    path: 'Remote',
  };

  context.addLibraryColor(params);

  const internalState = context.getInternalState();

  const file = internalState.files[fileId];

  assert.ok(file, "file should exist");

  assert.ok(file.data);
  assert.ok(file.data.pages);

  const colors = file.data.colors

  assert.ok(colors, "colors should exist");

  const color = colors[colorId];

  assert.ok(color, "color objects should exist");
  assert.equal(color.color, params.color);
  assert.equal(color.id, colorId);
  assert.equal(color.path, params.path);
  assert.equal(color.opacity, params.opacity);
  assert.equal(color.name, params.name);
});


test("create context with tokens lib as json", () => {
  const context = penpot.createBuildContext();

  const fileId = context.addFile({name: "file 1"});
  const pageId = context.addPage({name: "page 1"});


  const tokensFilePath = path.join(__dirname, "_tokens-1.json");
  const tokens = fs.readFileSync(tokensFilePath, "utf8");

  context.addTokensLib(tokens);


  const internalState = context.getInternalState();
  const file = internalState.files[fileId];

  assert.ok(file, "file should exist");

  assert.ok(file.data);
  assert.ok(file.data.tokensLib)
});

test("create context with tokens lib as json 2", () => {
  const context = penpot.createBuildContext();

  const fileId = context.addFile({name: "file 1"});
  const pageId = context.addPage({name: "page 1"});


  const tokensFilePath = path.join(__dirname, "_tokens-2.json");
  const tokens = fs.readFileSync(tokensFilePath, "utf8");

  context.addTokensLib(tokens);

  const internalState = context.getInternalState();
  const file = internalState.files[fileId];

  assert.ok(file, "file should exist");

  assert.ok(file.data);
  assert.ok(file.data.tokensLib)
});

test("create context with tokens lib as obj", () => {
  const context = penpot.createBuildContext();

  const fileId = context.addFile({name: "file 1"});
  const pageId = context.addPage({name: "page 1"});


  const tokensFilePath = path.join(__dirname, "_tokens-1.json");
  const tokens = fs.readFileSync(tokensFilePath, "utf8");

  context.addTokensLib(JSON.parse(tokens))


  const internalState = context.getInternalState();
  const file = internalState.files[fileId];

  assert.ok(file, "file should exist");

  assert.ok(file.data);
  assert.ok(file.data.tokensLib)
});

test("export compact format writes the page in chunks", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "test file"});
  const pageId = context.addPage({name: "test page"});

  context.addRect({name: "rect1", x: 100, y: 200, width: 50, height: 30});

  const blob = await penpot.exportAsBlob(context, {version: 2});

  const zipReader = new ZipReader(new BlobReader(new Blob([blob])));
  const entries = await zipReader.getEntries();

  const manifestEntry = entries.find(e => e.filename === "manifest.json");
  assert.ok(manifestEntry, "manifest should exist");

  const manifest = JSON.parse(await manifestEntry.getData(new TextWriter()));
  assert.equal(manifest.version, 2);

  const pageEntry = entries.find(e =>
    e.filename === `files/${fileId}/pages/${pageId}.json`);
  assert.ok(pageEntry, "page entry should exist");

  const pageData = JSON.parse(await pageEntry.getData(new TextWriter()));
  assert.equal(pageData.objects, undefined,
               "the shapes do not travel in the page entry");

  const chunkPrefix = `files/${fileId}/pages/${pageId}/chunks/`;
  const chunkEntries = entries.filter(e => e.filename.startsWith(chunkPrefix));
  assert.ok(chunkEntries.length > 0, "the page should have its chunks");
  assert.equal(chunkEntries.length, pageData.chunks,
               "the page entry names its chunks and every one is present");

  for (const entry of chunkEntries) {
    const text = await entry.getData(new TextWriter());
    const chunk = text.split("\n").filter(Boolean).map(JSON.parse);
    assert.ok(chunk.length > 0,
              `chunk ${entry.filename} should not be empty`);

    // The package does not repeat the id, which is the key of the map; the
    // parent and the frame are data and stay written.
    for (const [shapeId, shape] of chunk) {
      assert.equal(shape.id, undefined,
                   `shape ${shapeId} carries no id, the key is the identity`);
      assert.ok("parentId" in shape,
                `shape ${shapeId} keeps its parent as data`);
      assert.ok("frameId" in shape,
                `shape ${shapeId} keeps its frame as data`);
    }
  }

  const shapeEntries = entries.filter(e =>
    e.filename.startsWith(`files/${fileId}/pages/${pageId}/`) &&
    !e.filename.startsWith(chunkPrefix));
  assert.equal(shapeEntries.length, 0, "should not have per-shape entries");

  await zipReader.close();
});

test("export compact format splits a page and a family across chunks", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "multi chunk"});
  const pageId = context.addPage({name: "page"});

  // A budget small enough that every line is a chunk of its own: the split is
  // decided per line and the writer cannot split a value, so this crosses the
  // boundary without fabricating a document of megabytes.
  const chunkSize = 64;
  const shapeIds = [];
  for (let i = 0; i < 4; i++) {
    shapeIds.push(context.addRect({
      name: `rectá ${i}`, x: i * 10, y: 0, width: 10, height: 10,
    }));
  }

  const colorIds = [];
  for (let i = 0; i < 4; i++) {
    colorIds.push(context.addLibraryColor({
      name: `color ${i}`, color: "#FF0000", opacity: 1, path: "",
    }));
  }

  const reports = [];
  const blob = await penpot.exportAsBlob(context, {
    version: 2,
    chunkSize,
    onProgress: (report) => reports.push({...report}),
  });

  const zipReader = new ZipReader(new BlobReader(new Blob([blob])));
  const entries = await zipReader.getEntries();
  const names = entries.map((e) => e.filename);
  const entryText = async (name) => {
    const entry = entries.find((e) => e.filename === name);
    assert.ok(entry, `${name} should exist`);
    return entry.getData(new TextWriter());
  };
  const readJson = async (name) => JSON.parse(await entryText(name));
  const readLines = async (name) =>
    (await entryText(name)).split("\n").filter(Boolean).map(JSON.parse);

  const chunksOf = async (headerPath, prefix) => {
    const header = await readJson(headerPath);
    const chunkNames = names.filter((n) => n.startsWith(prefix));
    assert.equal(chunkNames.length, header.chunks,
                 `${headerPath} names its chunks and every one is present`);
    assert.ok(header.chunks > 1, `${headerPath} is split across several chunks`);
    for (let i = 0; i < header.chunks; i++) {
      assert.ok(names.includes(`${prefix}${i}.json`),
                `chunk ${i} of ${headerPath} is contiguous`);
    }
    return (await Promise.all(
      chunkNames.map(async (n) => readLines(n)),
    )).flat();
  };

  const pageLines = await chunksOf(
    `files/${fileId}/pages/${pageId}.json`,
    `files/${fileId}/pages/${pageId}/chunks/`,
  );
  const pageIds = pageLines.map(([id]) => id);
  assert.equal(new Set(pageIds).size, pageIds.length,
               "every shape travels once across the chunks");
  for (const id of shapeIds) {
    assert.ok(pageIds.includes(id), `shape ${id} travels in a chunk`);
  }

  const colorLines = await chunksOf(
    `files/${fileId}/colors.json`,
    `files/${fileId}/colors/chunks/`,
  );
  assert.deepEqual(
    colorLines.map(([id]) => id).sort(),
    [...colorIds].sort(),
    "every color travels once across the chunks",
  );

  assert.deepEqual(reports.map((r) => r.path), [
    "manifest.json",
    `files/${fileId}.json`,
    `files/${fileId}/pages/${pageId}.json`,
    `files/${fileId}/colors.json`,
  ], "one unit per manifest, file, page and family, never one per chunk");
  assert.ok(reports.every((r) => r.total === 4));

  await zipReader.close();
});

test("export compact format counts the chunk budget in UTF-8 bytes", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "utf8 budget"});
  context.addPage({name: "page"});

  // The two only objects of the colors family, named with accented characters:
  // a line weighs two bytes per character, so a writer that counted characters
  // would pack them differently than one that counts bytes.
  const colorIds = [];
  for (let i = 0; i < 2; i++) {
    colorIds.push(context.addLibraryColor({
      name: "é".repeat(40) + i,
      color: "#FF0000",
      opacity: 1,
      path: "",
    }));
  }

  const encoder = new TextEncoder();
  const byteSize = (value) => encoder.encode(value).length;

  const openZip = async (blob) => {
    const zipReader = new ZipReader(new BlobReader(new Blob([blob])));
    const entries = await zipReader.getEntries();
    const text = async (name) => {
      const entry = entries.find((e) => e.filename === name);
      assert.ok(entry, `${name} should exist`);
      return entry.getData(new TextWriter());
    };
    return {names: entries.map((e) => e.filename), text, close: () => zipReader.close()};
  };

  const headerPath = `files/${fileId}/colors.json`;
  const chunkPath = (index) => `files/${fileId}/colors/chunks/${index}.json`;

  // A first export at the default budget tells the size of each line.
  const first = await openZip(await penpot.exportAsBlob(context, {version: 2}));
  const lines = (await first.text(chunkPath(0))).split("\n").filter(Boolean);
  await first.close();

  assert.equal(lines.length, 2, "both colors travel in one chunk by default");

  // The newline belongs to the line: the splitter counts it.
  const bytes = lines.map((line) => byteSize(line) + 1);
  const chars = lines.map((line) => line.length + 1);
  const budget = bytes[0] + bytes[1] - 1;

  assert.ok(chars[0] + chars[1] <= budget,
            "by character count the two lines would still fit in one chunk");

  const second = await openZip(
    await penpot.exportAsBlob(context, {version: 2, chunkSize: budget}),
  );

  const header = JSON.parse(await second.text(headerPath));
  assert.equal(header.chunks, 2, "the byte budget starts a second chunk");

  const chunks = [];
  for (let i = 0; i < header.chunks; i++) {
    const text = await second.text(chunkPath(i));
    assert.ok(byteSize(text) <= budget,
              `chunk ${i} stays within the budget`);
    chunks.push(text);
  }

  const ids = chunks
    .flatMap((text) => text.split("\n").filter(Boolean).map(JSON.parse))
    .map(([id]) => id);
  assert.deepEqual(ids.sort(), [...colorIds].sort(),
                   "every color travels once across the chunks");

  await second.close();
});

test("export compact format reports one unit per planned step", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "progress"});
  const pageId = context.addPage({name: "page"});
  context.addRect({name: "rect", x: 0, y: 0, width: 10, height: 10});

  const reports = [];
  await penpot.exportAsBlob(context, {
    version: 2,
    onProgress: (report) => reports.push({...report}),
  });

  assert.deepEqual(reports.map((r) => r.path), [
    "manifest.json",
    `files/${fileId}.json`,
    `files/${fileId}/pages/${pageId}.json`,
  ], "one unit per manifest, file and page");
  assert.deepEqual(reports.map((r) => r.item), [1, 2, 3]);
  assert.ok(reports.every((r) => r.total === 3),
            "the total counts the planned units, not the chunks");
});

test("export compact format refuses a chunk size outside the budget", () => {
  const context = penpot.createBuildContext();
  context.addFile({name: "invalid budget"});
  context.addPage({name: "page"});

  const defaultBudget = 5 * 1024 * 1024;

  for (const [label, chunkSize] of [
    ["zero", 0],
    ["a negative value", -1],
    ["a fraction", 1.5],
    ["NaN", NaN],
    ["Infinity", Infinity],
    ["above the default budget", defaultBudget + 1],
  ]) {
    const options = {version: 2, chunkSize};
    assert.throws(() => penpot.exportAsBlob(context, options),
                  /invalid chunk-size/, label);
    assert.throws(() => penpot.exportAsBytes(context, options),
                  /invalid chunk-size/, label);
    assert.throws(
      () => penpot.exportStream(context, new WritableStream({write() {}}), options),
      /invalid chunk-size/, label,
    );
  }
});

test("export compact format accepts the default and a small chunk size", async () => {
  const context = penpot.createBuildContext();
  context.addFile({name: "valid budget"});
  context.addPage({name: "page"});
  context.addRect({name: "rect", x: 0, y: 0, width: 10, height: 10});

  for (const chunkSize of [undefined, 64]) {
    const options = chunkSize === undefined ? {version: 2} : {version: 2, chunkSize};
    const blob = await penpot.exportAsBlob(context, options);
    assert.ok(blob, `a chunk size of ${chunkSize} exports`);
  }
});

test("export legacy format reports one unit per entry", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "progress"});
  const pageId = context.addPage({name: "page"});
  const shapeId = context.addRect({name: "rect", x: 0, y: 0, width: 10, height: 10});

  const reports = [];
  await penpot.exportAsBlob(context, {
    version: 1,
    onProgress: (report) => reports.push({...report}),
  });

  assert.equal(reports.length, 5, "manifest, file, page, root and shape");
  assert.equal(reports[4].path, `files/${fileId}/pages/${pageId}/${shapeId}.json`);
  assert.deepEqual(reports.map((r) => r.item), [1, 2, 3, 4, 5]);
});

test("export compact format packs every family", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "packed"});
  context.addPage({name: "page"});

  const colorId = context.addLibraryColor({
    name: "Red",
    color: "#FF0000",
    opacity: 1,
    path: "",
  });

  const typographyId = context.addLibraryTypography({
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

  context.addBoard({name: "board", x: 0, y: 0, width: 100, height: 100});
  const componentId = context.genId();
  context.addComponent({componentId, name: "component"});
  context.closeBoard();

  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    "base64",
  );
  const fileMediaId = context.addFileMedia(
    {name: "pixel.png", width: 1, height: 1},
    new Blob([png], {type: "image/png"}),
  );

  const blob = await penpot.exportAsBlob(context, {version: 2});
  const zipReader = new ZipReader(new BlobReader(new Blob([blob])));
  const entries = await zipReader.getEntries();
  const names = entries.map((e) => e.filename);
  const entryText = async (name) => {
    const entry = entries.find((e) => e.filename === name);
    assert.ok(entry, `${name} should exist`);
    return entry.getData(new TextWriter());
  };
  const readJson = async (name) => JSON.parse(await entryText(name));
  const readLines = async (name) =>
    (await entryText(name)).split("\n").filter(Boolean).map(JSON.parse);

  // Each family is a header plus its chunks, never a per-object entry.
  for (const [family, id, path] of [
    ["components", componentId, `files/${fileId}/components`],
    ["colors", colorId, `files/${fileId}/colors`],
    ["typographies", typographyId, `files/${fileId}/typographies`],
    ["media", fileMediaId, `files/${fileId}/media`],
  ]) {
    assert.ok(names.includes(`${path}.json`), `${family}.json should exist`);
    assert.ok(names.includes(`${path}/chunks/0.json`),
              `${family} chunks should exist`);
    assert.equal((await readJson(`${path}.json`)).chunks, 1,
                 `${family} names its chunks`);
    const lines = await readLines(`${path}/chunks/0.json`);
    assert.equal(lines.length, 1, `${family} carries one object`);
    assert.equal(lines[0][0], id, `${family} is keyed by the object id`);
    assert.ok(!names.includes(`${path}/${id}.json`),
              `${family} writes no per-object entry`);
  }

  // The storage objects travel packed at the top level.
  assert.ok(names.includes("objects.json"), "objects.json should exist");
  assert.equal((await readJson("objects.json")).chunks, 1);
  const objects = await readLines("objects/chunks/0.json");
  assert.equal(objects.length, 1, "one storage object");
  assert.ok(objects[0][1].id, "the storage object carries its id");
  assert.ok(!names.some((n) => n.startsWith("objects/") && n.endsWith(".json")
                                && !n.startsWith("objects/chunks/")),
            "no per-object metadata entry");

  await zipReader.close();
});

test("export legacy format produces per-shape entries", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "test file"});
  const pageId = context.addPage({name: "test page"});

  context.addRect({name: "rect1", x: 100, y: 200, width: 50, height: 30});

  const blob = await penpot.exportAsBlob(context, {version: 1});

  const zipReader = new ZipReader(new BlobReader(new Blob([blob])));
  const entries = await zipReader.getEntries();

  const manifestEntry = entries.find(e => e.filename === "manifest.json");
  assert.ok(manifestEntry, "manifest should exist");

  const manifest = JSON.parse(await manifestEntry.getData(new TextWriter()));
  assert.equal(manifest.version, 1);

  const pageEntry = entries.find(e =>
    e.filename === `files/${fileId}/pages/${pageId}.json`);
  assert.ok(pageEntry, "page entry should exist");

  const pageData = JSON.parse(await pageEntry.getData(new TextWriter()));
  assert.ok(!pageData.objects, "legacy page should not contain objects");

  const shapeEntries = entries.filter(e =>
    e.filename.startsWith(`files/${fileId}/pages/${pageId}/`) &&
    e.filename !== `files/${fileId}/pages/${pageId}.json`);
  assert.ok(shapeEntries.length > 0, "should have per-shape entries");

  await zipReader.close();
});

test("export default format produces per-shape entries", async () => {
  const context = penpot.createBuildContext();
  const fileId = context.addFile({name: "test file"});
  const pageId = context.addPage({name: "test page"});

  context.addRect({name: "rect1", x: 100, y: 200, width: 50, height: 30});

  const blob = await penpot.exportAsBlob(context);

  const zipReader = new ZipReader(new BlobReader(new Blob([blob])));
  const entries = await zipReader.getEntries();

  const manifest = JSON.parse(
    await entries.find(e => e.filename === "manifest.json")
                   .getData(new TextWriter()));
  assert.equal(manifest.version, 1);

  const shapeEntries = entries.filter(e =>
    e.filename.startsWith(`files/${fileId}/pages/${pageId}/`) &&
    e.filename !== `files/${fileId}/pages/${pageId}.json`);
  assert.ok(shapeEntries.length > 0,
            "default (legacy) should have per-shape entries");

  await zipReader.close();
});
