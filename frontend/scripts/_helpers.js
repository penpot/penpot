import proc from "node:child_process";
import fs from "node:fs/promises";
import ph from "node:path";
import os from "node:os";
import url from "node:url";

import * as marked from "marked";
import SVGSpriter from "svg-sprite";
import Watcher from "watcher";
import gettext from "gettext-parser";
import l from "lodash";
import log from "fancy-log";
import mustache from "mustache";
import pLimit from "p-limit";
import ppt from "pretty-time";
import wpool from "workerpool";

function getCoreCount() {
  return os.cpus().length;
}

export const dirname = url.fileURLToPath(new URL(".", import.meta.url));

export function startWorker() {
  return wpool.pool(dirname + "/_worker.js", {
    maxWorkers: getCoreCount(),
  });
}

export const isDebug = process.env.NODE_ENV !== "production";

async function findFiles(basePath, predicate, options = {}) {
  predicate =
    predicate ??
    function () {
      return true;
    };

  let files = await fs.readdir(basePath, {
    recursive: options.recursive ?? false,
  });
  files = files.map((path) => ph.join(basePath, path));

  return files;
}

function syncDirs(originPath, destPath) {
  const command = `rsync -ar --delete ${originPath} ${destPath}`;

  return new Promise((resolve, reject) => {
    proc.exec(command, (cause, stdout) => {
      if (cause) {
        reject(cause);
      } else {
        resolve();
      }
    });
  });
}

export function isSassFile(path) {
  return path.endsWith(".scss");
}

export function isSvgFile(path) {
  return path.endsWith(".svg");
}

export function isJsFile(path) {
  return path.endsWith(".js");
}

export async function compileSass(worker, path, options) {
  path = ph.resolve(path);

  log.info("compile:", path);
  return worker.exec("compileSass", [path, options]);
}

export async function compileSassDebug(worker) {
  const result = await compileSass(worker, "resources/styles/debug.scss", {});
  return `${result.css}\n`;
}

export async function compileSassStorybook(worker) {
  const limitFn = pLimit(4);
  const sourceDir = ph.join("src", "app", "main", "ui", "ds");

  const dsFiles = (await fs.readdir(sourceDir, { recursive: true }))
    .filter(isSassFile)
    .map((filename) => ph.join(sourceDir, filename));
  const procs = [compileSass(worker, "resources/styles/main-default.scss", {})];

  for (let path of dsFiles) {
    const proc = limitFn(() => compileSass(worker, path, { modules: true }));
    procs.push(proc);
  }

  const result = await Promise.all(procs);
  return result.reduce(
    (acc, item) => {
      acc.index[item.outputPath] = item.css;
      acc.items.push(item.outputPath);
      return acc;
    },
    { index: {}, items: [] },
  );
}

export async function compileSassAll(worker) {
  const limitFn = pLimit(4);
  const sourceDir = "src";

  const isDesignSystemFile = (path) => {
    return path.startsWith("app/main/ui/ds/");
  };

  const isOldComponentSystemFile = (path) => {
    return path.startsWith("app/main/ui/components/");
  };

  let files = (await fs.readdir(sourceDir, { recursive: true })).filter(
    isSassFile,
  );

  const appFiles = files
    .filter((path) => !isDesignSystemFile(path))
    .filter((path) => !isOldComponentSystemFile(path))
    .map((path) => ph.join(sourceDir, path));

  const dsFiles = files
    .filter(isDesignSystemFile)
    .map((path) => ph.join(sourceDir, path));

  const oldComponentsFiles = files
    .filter(isOldComponentSystemFile)
    .map((path) => ph.join(sourceDir, path));

  const procs = [compileSass(worker, "resources/styles/main-default.scss", {})];

  for (let path of [...oldComponentsFiles, ...dsFiles, ...appFiles]) {
    const proc = limitFn(() => compileSass(worker, path, { modules: true }));
    procs.push(proc);
  }

  const result = await Promise.all(procs);

  return result.reduce(
    (acc, item) => {
      acc.index[item.outputPath] = item.css;
      acc.items.push(item.outputPath);
      return acc;
    },
    { index: {}, items: [] },
  );
}

export function concatSass(data) {
  const output = [];

  for (let path of data.items) {
    output.push(data.index[path]);
  }

  return output.join("\n");
}

export async function watch(baseDir, predicate, callback) {
  predicate = predicate ?? (() => true);

  const watcher = new Watcher(baseDir, {
    persistent: true,
    recursive: true,
  });

  watcher.on("change", (path) => {
    if (predicate(path)) {
      callback(path);
    }
  });
}

async function readManifestFile(path) {
  const manifestPath = "resources/public/js/manifest.json";
  let content = await fs.readFile(manifestPath, { encoding: "utf8" });
  return JSON.parse(content);
}

async function readShadowManifest() {
  const ts = Date.now();
  try {
    const content1 = await readManifestFile(
      "resources/public/js/manifest.json",
    );
    const content2 = await readManifestFile(
      "resources/public/js/worker/manifest.json",
    );

    const index = {
      ts: ts,
      config: "js/config.js?ts=" + ts,
      polyfills: "js/polyfills.js?ts=" + ts,
    };

    for (let item of content1) {
      index[item.name] = "js/" + item["output-name"];
    }

    for (let item of content2) {
      index["worker_" + item.name] = "js/worker/" + item["output-name"];
    }

    return index;
  } catch (cause) {
    return {
      ts: ts,
      config: "js/config.js?ts=" + ts,
      polyfills: "js/polyfills.js?ts=" + ts,
      main: "js/main.js?ts=" + ts,
      shared: "js/shared.js?ts=" + ts,
      worker_main: "js/worker/main.js?ts=" + ts,
      rasterizer: "js/rasterizer.js?ts=" + ts,
    };
  }
}

async function renderTemplate(path, context = {}, partials = {}) {
  const content = await fs.readFile(path, { encoding: "utf-8" });

  const ts = Math.floor(new Date());

  context = Object.assign({}, context, {
    ts: ts,
    isDebug,
  });

  return mustache.render(content, context, partials);
}

const markedOptions = {
  renderer: {
    link(token) {
      if (token.href === "mailto") {
        return `<a href="mailto:${token.text}">${token.text}</a>`;
      } else {
        let target = "_blank";

        if (token.text.endsWith("|target:self")) {
          const index = token.text.indexOf("|target:self");
          token.text = token.text.substring(0, index);
          target = "_self";
        }

        const href = token.href;
        const text = token.text;
        return `<a href="${href}" target="${target}">${text}</a>`;
      }
    }
  }
}

marked.use(markedOptions);

async function readTranslations() {
  const langs = [
    "ar",
    "ca",
    "de",
    "el",
    "en",
    "eu",
    "it",
    "es",
    "fa",
    "fr",
    "he",
    "sr",
    "nb_NO",
    "pl",
    "pt_BR",
    "ro",
    "id",
    "ru",
    "tr",
    "zh_CN",
    "zh_Hant",
    "hr",
    "gl",
    "pt_PT",
    "cs",
    "fo",
    "ko",
    "lv",
    "nl",
    // this happens when file does not matches correct
    // iso code for the language.
    ["ja_jp", "jpn_JP"],
    ["uk", "ukr_UA"],
    "ha",
  ];
  const result = {};

  for (let lang of langs) {
    let filename = `${lang}.po`;
    if (l.isArray(lang)) {
      filename = `${lang[1]}.po`;
      lang = lang[0];
    }

    const content = await fs.readFile(`./translations/${filename}`, {
      encoding: "utf-8",
    });

    lang = lang.toLowerCase();

    const data = gettext.po.parse(content, "utf-8");
    const trdata = data.translations[""];

    for (let key of Object.keys(trdata)) {
      if (key === "") continue;
      const comments = trdata[key].comments || {};

      if (l.isNil(result[key])) {
        result[key] = {};
      }

      const isMarkdown = l.includes(comments.flag, "markdown");

      const msgs = trdata[key].msgstr;
      if (msgs.length === 1) {
        let message = msgs[0];
        if (isMarkdown) {
          message = marked.parseInline(message);
        }

        result[key][lang] = message;
      } else {
        result[key][lang] = msgs.map((item) => {
          if (isMarkdown) {
            return marked.parseInline(item);
          } else {
            return item;
          }
        });
      }
    }
  }

  return result;
}

function filterTranslations(translations, langs = [], keyFilter) {
  const filteredEntries = Object.entries(translations)
    .filter(([translationKey, _]) => keyFilter(translationKey))
    .map(([translationKey, value]) => {
      const langEntries = Object.entries(value).filter(([lang, _]) =>
        langs.includes(lang),
      );
      return [translationKey, Object.fromEntries(langEntries)];
    });

  return Object.fromEntries(filteredEntries);
}

async function generateSvgSprite(files, prefix) {
  const spriter = new SVGSpriter({
    mode: {
      symbol: { inline: true },
    },
  });

  for (let path of files) {
    const name = `${prefix}${ph.basename(path)}`;
    const content = await fs.readFile(path, { encoding: "utf-8" });
    spriter.add(name, name, content);
  }

  const { result } = await spriter.compileAsync();
  const resource = result.symbol.sprite;
  return resource.contents;
}

async function generateSvgSprites() {
  await fs.mkdir("resources/public/images/sprites/symbol/", {
    recursive: true,
  });

  const icons = await findFiles("resources/images/icons/", isSvgFile);
  const iconsSprite = await generateSvgSprite(icons, "icon-");
  await fs.writeFile(
    "resources/public/images/sprites/symbol/icons.svg",
    iconsSprite,
  );

  const cursors = await findFiles("resources/images/cursors/", isSvgFile);
  const cursorsSprite = await generateSvgSprite(cursors, "cursor-");
  await fs.writeFile(
    "resources/public/images/sprites/symbol/cursors.svg",
    cursorsSprite,
  );

  const assets = await findFiles("resources/images/assets/", isSvgFile);
  const assetsSprite = await generateSvgSprite(assets, "asset-");
  await fs.writeFile(
    "resources/public/images/sprites/assets.svg",
    assetsSprite,
  );
}

async function generateTemplates() {
  const isDebug = process.env.NODE_ENV !== "production";
  await fs.mkdir("./resources/public/", { recursive: true });

  let translations = await readTranslations();
  const storybookTranslations = JSON.stringify(
    filterTranslations(translations, ["en"], (key) =>
      key.startsWith("labels."),
    ),
  );
  translations = JSON.stringify(translations);

  const manifest = await readShadowManifest();
  let content;

  const iconsSprite = await fs.readFile(
    "resources/public/images/sprites/symbol/icons.svg",
    "utf8",
  );
  const cursorsSprite = await fs.readFile(
    "resources/public/images/sprites/symbol/cursors.svg",
    "utf8",
  );
  const assetsSprite = await fs.readFile(
    "resources/public/images/sprites/assets.svg",
    "utf-8",
  );
  const partials = {
    "../public/images/sprites/symbol/icons.svg": iconsSprite,
    "../public/images/sprites/symbol/cursors.svg": cursorsSprite,
    "../public/images/sprites/assets.svg": assetsSprite,
  };

  const pluginRuntimeUri =
    process.env.PENPOT_PLUGIN_DEV === "true"
      ? "http://localhost:4200/index.js?ts=" + manifest.ts
      : "plugins-runtime/index.js?ts=" + manifest.ts;

  content = await renderTemplate(
    "resources/templates/index.mustache",
    {
      manifest: manifest,
      translations: JSON.stringify(translations),
      pluginRuntimeUri,
      isDebug,
    },
    partials,
  );

  await fs.writeFile("./resources/public/index.html", content);

  content = await renderTemplate(
    "resources/templates/challenge.mustache",
    {},
    partials,
  );
  await fs.writeFile("./resources/public/challenge.html", content);

  content = await renderTemplate(
    "resources/templates/preview-body.mustache",
    {
      manifest: manifest,
    },
    partials,
  );
  await fs.writeFile("./.storybook/preview-body.html", content);

  content = await renderTemplate(
    "resources/templates/preview-head.mustache",
    {
      manifest: manifest,
      translations: JSON.stringify(storybookTranslations),
    },
    partials,
  );
  await fs.writeFile("./.storybook/preview-head.html", content);

  content = await renderTemplate("resources/templates/render.mustache", {
    manifest: manifest,
    translations: JSON.stringify(translations),
  });

  await fs.writeFile("./resources/public/render.html", content);

  content = await renderTemplate("resources/templates/rasterizer.mustache", {
    manifest: manifest,
    translations: JSON.stringify(translations),
  });

  await fs.writeFile("./resources/public/rasterizer.html", content);
}

export async function compileStorybookStyles() {
  const worker = startWorker();
  const start = process.hrtime();

  log.info("init: compile storybook styles");
  let result = await compileSassStorybook(worker);
  result = concatSass(result);

  await fs.mkdir("./resources/public/css", { recursive: true });
  await fs.writeFile("./resources/public/css/ds.css", result);

  const end = process.hrtime(start);
  log.info("done: compile storybook styles", `(${ppt(end)})`);
  worker.terminate();
}

export async function compileStyles() {
  const worker = startWorker();
  const start = process.hrtime();

  log.info("init: compile styles");

  let result = await compileSassAll(worker);
  result = concatSass(result);

  await fs.mkdir("./resources/public/css", { recursive: true });
  await fs.writeFile("./resources/public/css/main.css", result);

  if (isDebug) {
    let debugCSS = await compileSassDebug(worker);
    await fs.writeFile("./resources/public/css/debug.css", debugCSS);
  }

  const end = process.hrtime(start);
  log.info("done: compile styles", `(${ppt(end)})`);
  worker.terminate();
}

export async function compileSvgSprites() {
  const start = process.hrtime();
  log.info("init: compile svgsprite");
  await generateSvgSprites();
  const end = process.hrtime(start);
  log.info("done: compile svgsprite", `(${ppt(end)})`);
}

export async function compileTemplates() {
  const start = process.hrtime();
  log.info("init: compile templates");
  await generateTemplates();
  const end = process.hrtime(start);
  log.info("done: compile templates", `(${ppt(end)})`);
}

export async function compilePolyfills() {
  const start = process.hrtime();
  log.info("init: compile polyfills");

  const files = await findFiles("resources/polyfills/", isJsFile);
  let result = [];
  for (let path of files) {
    const content = await fs.readFile(path, { encoding: "utf-8" });
    result.push(content);
  }

  await fs.mkdir("./resources/public/js", { recursive: true });
  fs.writeFile("resources/public/js/polyfills.js", result.join("\n"));

  const end = process.hrtime(start);
  log.info("done: compile polyfills", `(${ppt(end)})`);
}

export async function copyAssets() {
  const start = process.hrtime();
  log.info("init: copy assets");

  await syncDirs("resources/images/", "resources/public/images/");
  await syncDirs("resources/fonts/", "resources/public/fonts/");
  await syncDirs(
    "resources/plugins-runtime/",
    "resources/public/plugins-runtime/",
  );

  const end = process.hrtime(start);
  log.info("done: copy assets", `(${ppt(end)})`);
}

export async function copyWasmPlayground() {
  const start = process.hrtime();
  log.info("init: copy wasm playground");

  await syncDirs(
    "resources/wasm-playground/",
    "resources/public/wasm-playground/",
  );

  const end = process.hrtime(start);
  log.info("done: copy wasm playground", `(${ppt(end)})`);
}
