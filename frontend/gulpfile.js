import fs from "fs";
import l from "lodash";
import path from "path";


import log from 'fancy-log';

import gulp from "gulp";
import gulpConcat from "gulp-concat";
import gulpGzip from "gulp-gzip";
import gulpMustache from "gulp-mustache";
import gulpPostcss from "gulp-postcss";
import gulpRename from "gulp-rename";

import * as sass from "sass";
import gsass from "gulp-sass";
const gulpSass = gsass(sass);

import svgSprite from "gulp-svg-sprite";
import rename from "gulp-rename";

import autoprefixer from "autoprefixer";
import modules from "postcss-modules";

import clean from "postcss-clean";
import { mkdirp } from "mkdirp";
import { rimraf } from "rimraf";

import gettext from "gettext-parser";
import * as marked from "marked";

import mapStream from "map-stream";

/***********************************************
 * Marked Extensions
 ***********************************************/

// Name of Penpot's top level package
const ROOT_NAME = "app";

const renderer = {
  link(href, title, text) {
    return `<a href="${href}" target="_blank">${text}</a>`;
  },
};

marked.use({ renderer });

/***********************************************
 * Helpers
 ***********************************************/

// Templates

function readLocales() {
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
    // ["fi", "fin_FI"],
    ["uk", "ukr_UA"],
    "ha"
  ];
  const result = {};

  for (let lang of langs) {
    let filename = `${lang}.po`;
    if (l.isArray(lang)) {
      filename = `${lang[1]}.po`;
      lang = lang[0];
    }

    const content = fs.readFileSync(`./translations/${filename}`, { encoding: "utf-8" });

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
      // if (key === "modals.delete-font.title") {
      //   console.dir(trdata[key], {depth:10});
      //   console.dir(result[key], {depth:10});
      // }
    }
  }

  return JSON.stringify(result);
}

function readManifest() {
  try {
    const manifestPath = path.resolve("./resources/public/js/manifest.json");
    const content = JSON.parse(fs.readFileSync(manifestPath, { encoding: "utf8" }));

    const index = {
      config: "js/config.js?ts=" + Date.now(),
      polyfills: "js/polyfills.js?ts=" + Date.now(),
    };

    for (let item of content) {
      index[item.name] = "js/" + item["output-name"];
    }

    return index;
  } catch (e) {
    console.error("Error on reading manifest, using default.", e);
    return {
      config: "js/config.js",
      polyfills: "js/polyfills.js",
      main: "js/main.js",
      shared: "js/shared.js",
      worker: "js/worker.js",
      rasterizer: "js/rasterizer.js",
    };
  }
}

function touch() {
  return mapStream(function (file, cb) {
    // console.log("touch", file.path);
    if (file.isNull()) {
      return cb(null, file);
    } else {
      // Update file modification and access time
      return fs.utimes(file.path, new Date(), new Date(), () => {
        // console.log("touched", file.path);
        cb(null, file);
      });
    }
  });
}

let cacheStore = {}

function cache(name){
  if (!cacheStore[name]) {
    cacheStore[name] = {};
  }

  return mapStream(function(file, callback) {
    if (file.isNull()) {
      return callback(null, file);
    } else if (file.isStream()) {
      return callback(null, file);
    } else {
      const mtime = file.stat.mtime.getTime();
      const existingMtime = cacheStore[name][file.path];

      // hit - ignore it
      if (existingMtime !== undefined && existingMtime === mtime) {
        callback();
      } else {
        log.info(`Change detected on '${file.path}'`);

        // miss - add it and pass it through
        cacheStore[name][file.path] = mtime;
        callback(null, file);
      }
    }
  });
};

function templatePipeline(options) {
  return function () {
    const input = options.input;
    const output = options.output;
    const name = options.name;

    const ts = Math.floor(new Date());
    const th = process.env.APP_THEME || "default";
    const themes = ["default"];

    const locales = readLocales();
    const manifest = readManifest();

    const tmpl = gulpMustache({
      ts: ts,
      // th: th,
      manifest: manifest,
      translations: JSON.stringify(locales),
      themes: JSON.stringify(themes),
      isDebug: process.env.NODE_ENV !== "production",
    });

    return gulp.src(input).pipe(tmpl).pipe(gulpRename(name)).pipe(gulp.dest(output)).pipe(touch());
  };
}

/***********************************************
 * Generic
 ***********************************************/

gulpSass.compiler = sass;


gulp.task("scss:clean", async function (next) {
  try {
    await rimraf("./resources/public/.tmp");
    await rimraf("./resources/public/css");
  } finally {
    next();
  }
});

gulp.task("scss:modules", function () {
  return gulp
    .src(["src/**/**.scss"])
    .pipe(cache("sass"))
    .pipe(
      gulpSass
        .sync({ includePaths: ["./resources/styles/common/", "./resources/styles/"] })
        .on("error", gulpSass.logError),
    )
    .pipe(cache("css"))
    .pipe(
      gulpPostcss([
        modules({
          getJSON: function (cssFileName, json, outputFileName) {
            // We do nothing because we don't want the generated JSON files
          },
          // Calculates the whole css-module selector name.
          // Should be the same as the one in the file `/src/app/main/style.clj`
          generateScopedName: function (selector, filename, css) {
            const dir = path.dirname(filename);
            const name = path.basename(filename, ".css");
            const parts = dir.split("/");
            const rootIdx = parts.findIndex((s) => s === ROOT_NAME);
            return parts.slice(rootIdx + 1).join("_") + "_" + name + "__" + selector;
          },
        }),
        autoprefixer(),
      ]),
    )
    .pipe(gulp.dest("./resources/public/css/"));
});

gulp.task("scss:main", function () {
  const sources = ["./resources/styles/main-default.scss",
                   "./resources/styles/debug.scss"];

  return gulp
    .src(sources)
    .pipe(
      gulpSass.sync({
        includePaths: ["./node_modules/animate.css"],
      }),
    )
    .pipe(gulpPostcss([autoprefixer]))
    .pipe(gulp.dest("./resources/public/css/"));
});

gulp.task("scss:concat", function () {
  return gulp
    .src(["./resources/public/css/main-default.css",
          "./resources/public/css/app/**/*.css"])
    .pipe(gulpConcat("main.tmp.css"), { rebaseUrls: false })
    .pipe(gulp.dest("./resources/public/.tmp/"));
});

gulp.task("scss:touch:main", function () {
  return gulp
    .src(["./resources/public/.tmp/main.tmp.css"])
    .pipe(rename("main.css"))
    .pipe(gulp.dest("./resources/public/css/"))
    .pipe(touch());
});

gulp.task("scss:touch:modules", function() {
  return gulp.src("src/**/**.scss").pipe(touch());
});

gulp.task("scss", gulp.series("scss:main",
                              "scss:modules",
                              "scss:concat",
                              "scss:touch:main"));

gulp.task("svg:sprite:icons", function () {
  return gulp
    .src("./resources/images/icons/*.svg")
    .pipe(gulpRename({ prefix: "icon-" }))
    .pipe(svgSprite({ mode: { symbol: { inline: true, sprite: "icons.svg" } } }))
    .pipe(gulp.dest("./resources/public/images/sprites/"));
});

gulp.task("svg:sprite:cursors", function () {
  return gulp
    .src("./resources/images/cursors/*.svg")
    .pipe(gulpRename({ prefix: "cursor-" }))
    .pipe(svgSprite({ mode: { symbol: { inline: true, sprite: "cursors.svg" } } }))
    .pipe(gulp.dest("./resources/public/images/sprites/"));
});

gulp.task(
  "template:main",
  templatePipeline({
    name: "index.html",
    input: "./resources/templates/index.mustache",
    output: "./resources/public/",
  }),
);

gulp.task(
  "template:storybook",
  templatePipeline({
    name: "preview-body.html",
    input: "./resources/templates/preview-body.mustache",
    output: "./.storybook/",
  }),
);

gulp.task(
  "template:render",
  templatePipeline({
    name: "render.html",
    input: "./resources/templates/render.mustache",
    output: "./resources/public/",
  }),
);

gulp.task(
  "template:rasterizer",
  templatePipeline({
    name: "rasterizer.html",
    input: "./resources/templates/rasterizer.mustache",
    output: "./resources/public/",
  }),
);

gulp.task(
  "templates",
  gulp.series(
    "svg:sprite:icons",
    "svg:sprite:cursors",
    "template:main",
    "template:storybook",
    "template:render",
    "template:rasterizer",
  ),
);

gulp.task("polyfills", function () {
  return gulp
    .src("./resources/polyfills/*.js")
    .pipe(gulpConcat("polyfills.js"))
    .pipe(gulp.dest("./resources/public/js/"));
});

gulp.task("copy:assets:images", function () {
  return gulp.src("./resources/images/**/*").pipe(gulp.dest("./resources/public/images/"));
});

gulp.task("copy:assets:fonts", function () {
  return gulp.src("./resources/fonts/**/*").pipe(gulp.dest("./resources/public/fonts/"));
});

gulp.task("copy:assets", gulp.parallel("copy:assets:images", "copy:assets:fonts"));

gulp.task("dev:dirs", async function (next) {
  await mkdirp("./resources/public/css/");
  await mkdirp("./resources/public/js/");
  next();
});

gulp.task("watch:main", function () {
  gulp.watch("./src/**/**.scss", {delay:1000}, gulp.series("scss"));
  gulp.watch("./resources/styles/**/**.scss", {delay: 100}, gulp.series("scss:touch:modules"));
  gulp.watch("./resources/images/**/*", gulp.series("copy:assets:images"));
  gulp.watch(["./resources/templates/*.mustache", "translations/*.po"], gulp.series("templates"));
});

gulp.task("clean:output", function (next) {
  rimraf("./resources/public/").finally(next);
});

gulp.task("clean:dist", function (next) {
  rimraf("./target/dist/").finally(next);
});

gulp.task("build:styles", gulp.parallel("scss"));

gulp.task("build:assets",
          gulp.parallel("polyfills",
                        "templates",
                        "copy:assets"));

gulp.task("watch",
          gulp.series("dev:dirs",
                      "scss:clean",
                      "build:styles",
                      "build:assets",
                      "watch:main"));

gulp.task("build:copy", function () {
  return gulp.src("./resources/public/**/*").pipe(gulp.dest("./target/dist/"));
});
