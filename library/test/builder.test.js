import assert from "node:assert/strict";
import test from "node:test";
import * as fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

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
