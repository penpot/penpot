#!/usr/bin/env node
// Seed real Penpot files shaped like phishing / benign designs, to validate the
// extractor end to end.
//
// It does not build shapes from scratch (the backend validates shapes against
// the full schema). Instead it clones a frame subtree from a template file the
// caller already has, remaps the ids, and rewrites the text, the URLs and the
// frame name. Then it reads the projection back with get-semantic-file and
// checks the expected signals survived.
//
// Usage:
//   node seed.mjs --template <file-id>
//   node seed.mjs --template <file-id> --keep     # do not delete the files
//
// Config comes from .env, same as analyze.mjs. The template file must contain
// at least one plain frame (no component instances) with a text shape and an
// open-url link; the Plants-app demo works.

import process from "node:process";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import { resolveAuth, rpc } from "./analyze.mjs";

const ZERO = "00000000-0000-0000-0000-000000000000";

const CASES = [
  {
    name: "seed-phishing-paypal",
    frameName: "Sign in",
    text: [
      "Sign in to your PayPal account",
      "Your account has been limited",
      "Verify your account within 24 hours",
      "Email",
      "Password",
    ],
    url: "http://paypal-secure-login.xyz/verify",
    expect: { label: "phishing", contains: "paypal", url: "paypal-secure-login.xyz" },
  },
  {
    name: "seed-benign-login",
    frameName: "Sign in",
    text: [
      "Sign in to Acme Intranet",
      "Email",
      "Password",
      "Forgot your password?",
    ],
    url: "https://intranet.acme.example/login",
    expect: { label: "benign", contains: "acme", url: "intranet.acme.example" },
  },
];

// --- cloning ---------------------------------------------------------------

function childrenOf(objects, id) {
  return objects[id]?.shapes ?? [];
}

export function collectSubtree(objects, rootId) {
  const ids = [];
  const walk = (id) => {
    ids.push(id);
    for (const child of childrenOf(objects, id)) walk(child);
  };
  walk(rootId);
  return ids;
}

export function hasComponentRef(objects, id) {
  if (objects[id]?.componentId || objects[id]?.shapeRef) return true;
  return childrenOf(objects, id).some((child) => hasComponentRef(objects, child));
}

function hasTextAndLink(objects, id) {
  let text = false;
  let link = false;
  for (const shapeId of collectSubtree(objects, id)) {
    const shape = objects[shapeId];
    if (shape?.type === "text") text = true;
    if ((shape?.interactions ?? []).some((i) => i.actionType === "open-url")) link = true;
  }
  return text && link;
}

export function pickTemplateFrame(objects) {
  const frames = Object.values(objects).filter(
    (shape) => shape?.type === "frame" && shape.frameId === ZERO && shape.id !== ZERO);
  const candidate = frames.find(
    (frame) => hasTextAndLink(objects, frame.id) && !hasComponentRef(objects, frame.id));
  if (!candidate) {
    throw new Error("template file has no plain frame with a text shape and an open-url link");
  }
  return candidate;
}

export function cycling(lines) {
  let i = 0;
  return () => lines[i++ % lines.length];
}

export function rewriteParagraphs(content, nextText) {
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    if (node.type === "paragraph" && Array.isArray(node.children)) {
      const first = node.children.find((c) => c && typeof c === "object" && typeof c.text === "string");
      if (first) node.children = [{ ...first, text: nextText() }];
      return;
    }
    if (Array.isArray(node.children)) node.children.forEach(visit);
  };
  visit(content);
}

function cloneFrame(objects, frame, { frameName, text, url }) {
  const ids = collectSubtree(objects, frame.id);
  const idMap = new Map(ids.map((id) => [id, randomUUID()]));
  const nextText = cycling(text);

  const clones = ids.map((id) => {
    const clone = structuredClone(objects[id]);
    const isRoot = id === frame.id;

    clone.id = idMap.get(id);
    clone.parentId = isRoot ? ZERO : idMap.get(clone.parentId) ?? ZERO;
    clone.frameId = isRoot ? ZERO : idMap.get(clone.frameId) ?? idMap.get(frame.id);
    if (Array.isArray(clone.shapes)) clone.shapes = clone.shapes.map((child) => idMap.get(child) ?? child);

    // Optional refs that would dangle in the new file.
    delete clone.pageId;
    delete clone.componentId;
    delete clone.componentFile;
    delete clone.componentRoot;
    delete clone.mainInstance;
    delete clone.shapeRef;
    delete clone.touched;
    delete clone.remoteSynced;
    // position-data is derived layout state; keeping the template's copy makes
    // the renderer draw the old text even after the content changes.
    delete clone.positionData;

    if (clone.type === "text" && clone.content) rewriteParagraphs(clone.content, nextText);

    for (const interaction of clone.interactions ?? []) {
      if (interaction.actionType === "open-url") interaction.url = url;
      if (interaction.destination && idMap.has(interaction.destination)) {
        interaction.destination = idMap.get(interaction.destination);
      }
    }

    if (isRoot) clone.name = frameName;
    return clone;
  });

  const changes = clones.map((clone) => ({
    type: "add-obj",
    pageId: null, // filled by caller
    id: clone.id,
    parentId: clone.parentId,
    frameId: clone.frameId,
    obj: clone,
  }));

  return { changes, frameId: idMap.get(frame.id) };
}

// --- main ------------------------------------------------------------------

function parseArgs(argv) {
  const args = { template: process.env.SEED_TEMPLATE_FILE, keep: false };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--template") args.template = argv[++i];
    else if (argv[i] === "--keep") args.keep = true;
  }
  return args;
}

function check(projection, spec, frameId) {
  const frames = projection.pages.flatMap((page) => page.frames);
  const frame = frames.find((f) => f.name === spec.frameName);
  const problems = [];
  if (!frame) problems.push(`frame "${spec.frameName}" not found`);
  const text = (frame?.text ?? []).join("\n").toLowerCase();
  if (!text.includes(spec.expect.contains)) problems.push(`text does not contain "${spec.expect.contains}"`);
  const links = frames.flatMap((f) => f.links ?? []);
  if (!links.some((l) => l.url.includes(spec.expect.url))) problems.push(`no link to ${spec.expect.url}`);
  return { frameId, problems };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.template) {
    console.error("error: pass --template <file-id> or set SEED_TEMPLATE_FILE");
    process.exit(1);
  }

  const auth = await resolveAuth();
  const profile = await rpc("get-profile", {}, auth);
  const projectId = profile.defaultProjectId;
  if (!projectId) {
    console.error("error: profile has no default project id");
    process.exit(1);
  }

  const template = await rpc("get-file", { id: args.template }, auth);
  const page = (template.data.pages ?? [])[0];
  const objects = template.data.pagesIndex?.[page]?.objects ?? {};
  const templateFrame = pickTemplateFrame(objects);
  console.log(`template frame: ${templateFrame.name} (${templateFrame.id})`);

  const created = [];
  for (const spec of CASES) {
    const file = await rpc("create-file", { name: spec.name, projectId }, auth);
    const pageId = (file.data.pages ?? [])[0];
    const { changes, frameId } = cloneFrame(objects, templateFrame, spec);
    for (const change of changes) change.pageId = pageId;

    await rpc("update-file", {
      id: file.id,
      sessionId: randomUUID(),
      revn: 0,
      vern: 0,
      changes,
    }, auth);

    const projection = await rpc("get-semantic-file", { id: file.id }, auth);
    const { problems } = check(projection, spec, frameId);
    created.push({ spec, file, projection, problems });
    console.log(`\n${spec.name} (${file.id}) — ${problems.length ? "FAILED" : "ok"}`);
    if (problems.length) for (const p of problems) console.log(`  ! ${p}`);
    console.log(JSON.stringify(projection.pages[0]?.frames?.[0] ?? {}, null, 2));
  }

  console.log(`\ncreated ${created.length} files${args.keep ? " (kept)" : ""}`);
  if (!args.keep) {
    for (const { file } of created) {
      await rpc("delete-file", { id: file.id }, auth);
      console.log(`deleted ${file.id}`);
    }
  }

  if (created.some((c) => c.problems.length)) process.exit(1);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(`error: ${err.stack ?? String(err)}`);
    process.exit(1);
  });
}
