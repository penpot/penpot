import assert from "node:assert/strict";
import test from "node:test";

import * as penpot  from "#self";

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
