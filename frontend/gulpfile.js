const fs = require("fs");
const l = require("lodash");
const path = require("path");

const gulp = require("gulp");
const gulpConcat = require("gulp-concat");
const gulpGzip = require("gulp-gzip");
const gulpMustache = require("gulp-mustache");
const gulpPostcss = require("gulp-postcss");
const gulpRename = require("gulp-rename");
const gulpSass = require("gulp-sass")(require("sass"));
const svgSprite = require("gulp-svg-sprite");

const autoprefixer = require("autoprefixer")
const modules = require("postcss-modules");

const clean = require("postcss-clean");
const mkdirp = require("mkdirp");
const rimraf = require("rimraf");
const sass = require("sass");
const gettext = require("gettext-parser");
const marked = require("marked");

const mapStream = require("map-stream");
const paths = {};
paths.resources = "./resources/";
paths.output = "./resources/public/";
paths.dist = "./target/dist/";

/***********************************************
 * Marked Extensions
 ***********************************************/

const renderer = {
  link(href, title, text) {
    return `<a href="${href}" target="_blank">${text}</a>`;
  }
};

marked.use({renderer});

/***********************************************
 * Helpers
 ***********************************************/

// Templates

function readLocales() {
  const langs = ["ar", "ca", "de", "el", "en", "eu", "it", "es",
                 "fa", "fr", "he", "nb_NO", "pl", "pt_BR", "ro",
                 "ru", "tr", "zh_CN", "zh_Hant", "hr", "gl", "pt_PT",
                 // this happens when file does not matches correct
                 // iso code for the language.
                 ["ja_jp", "jpn_JP"]
                ];
  const result = {};

  for (let lang of langs) {
    let filename = `${lang}.po`;
    if (l.isArray(lang)) {
      filename = `${lang[1]}.po`;
      lang = lang[0]
    }

    const content = fs.readFileSync(`./translations/${filename}`, {encoding:"utf-8"});

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
    const path = __dirname + "/resources/public/js/manifest.json";
    const content = JSON.parse(fs.readFileSync(path, {encoding: "utf8"}));

    const index = {
      "config": "js/config.js?ts=" + Date.now(),
      "polyfills": "js/polyfills.js?ts=" + Date.now(),
    };

    for (let item of content) {
      index[item.name] = "js/" + item["output-name"];
    };

    return index;
  } catch (e) {
    console.error("Error on reading manifest, using default.");
    return {
      "config": "js/config.js",
      "polyfills": "js/polyfills.js",
      "main": "js/main.js",
      "shared": "js/shared.js",
      "worker": "js/worker.js"
    };
  }
}

function touch() {
  return mapStream(function(file, cb) {
    if (file.isNull()) {
      return cb(null, file);
    }

    // Update file modification and access time
    return fs.utimes(file.path, new Date(), new Date(), () => {
      cb(null, file)
    });
  });
}

function templatePipeline(options) {
  return function() {
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
    });

    return gulp.src(input)
      .pipe(tmpl)
      .pipe(gulpRename(name))
      .pipe(gulp.dest(output))
      .pipe(touch());
  };
}

/***********************************************
 * Generic
 ***********************************************/

gulpSass.compiler = sass;

gulp.task("scss:modules", function() {
  return gulp.src(["src/**/**.scss"])
    .pipe(gulpSass.sync().on('error', gulpSass.logError))
    .pipe(gulpPostcss([
      modules({
        generateScopedName: "[folder]_[name]_[local]_[hash:base64:5]",
      }),
      autoprefixer(),
    ]))
    .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("scss:main", function() {
  return gulp.src(paths.resources + "styles/main-default.scss")
    .pipe(gulpSass.sync().on('error', gulpSass.logError))
    .pipe(gulpPostcss([
      autoprefixer,
    ]))
    .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("scss:concat", function() {
  return gulp.src([paths.output + "css/main-default.css",
                  paths.output + "css/app/**/*.css"])
    .pipe(gulpConcat("main.css"))
    .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("scss", gulp.series("scss:main", "scss:modules", "scss:concat"));

gulp.task("svg:sprite:icons", function() {
  return gulp.src(paths.resources + "images/icons/*.svg")
    .pipe(gulpRename({prefix: "icon-"}))
    .pipe(svgSprite({mode:{symbol: {inline: true, sprite: "icons.svg"}}}))
    .pipe(gulp.dest(paths.output + "images/sprites/"));
});

gulp.task("svg:sprite:cursors", function() {
  return gulp.src(paths.resources + "images/cursors/*.svg")
    .pipe(gulpRename({prefix: "cursor-"}))
    .pipe(svgSprite({mode:{symbol: {inline: true, sprite: "cursors.svg"}}}))
    .pipe(gulp.dest(paths.output + "images/sprites/"));
});

gulp.task("template:main", templatePipeline({
  name: "index.html",
  input: paths.resources + "templates/index.mustache",
  output: paths.output
}));

gulp.task("template:render", templatePipeline({
  name: "render.html",
  input: paths.resources + "templates/render.mustache",
  output: paths.output
}));

gulp.task("templates", gulp.series("svg:sprite:icons", "svg:sprite:cursors", "template:main", "template:render"));

gulp.task("polyfills", function() {
  return gulp.src(paths.resources + "polyfills/*.js")
    .pipe(gulpConcat("polyfills.js"))
    .pipe(gulp.dest(paths.output + "js/"));
});

/***********************************************
 * Development
 ***********************************************/

gulp.task("clean", function(next) {
  rimraf(paths.output, next);
});

gulp.task("copy:assets:images", function() {
  return gulp.src(paths.resources + "images/**/*")
    .pipe(gulp.dest(paths.output + "images/"));
});

gulp.task("copy:assets:fonts", function() {
  return gulp.src(paths.resources + "fonts/**/*")
    .pipe(gulp.dest(paths.output + "fonts/"));
});

gulp.task("copy:assets", gulp.parallel("copy:assets:images", "copy:assets:fonts"));

gulp.task("dev:dirs", async function(next) {
  await mkdirp("./resources/public/css/");
  await mkdirp("./resources/public/js/");
  next();
});

gulp.task("watch:main", function() {
  gulp.watch("src/**/**.scss", gulp.series("scss"));
  gulp.watch(paths.resources + "styles/**/**.scss", gulp.series("scss"));
  gulp.watch(paths.resources + "images/**/*", gulp.series("copy:assets:images"));

  gulp.watch([paths.resources + "templates/*.mustache",
              "translations/*.po"],
             gulp.series("templates"));
});

gulp.task("build", gulp.parallel("polyfills", "scss", "templates", "copy:assets"));
gulp.task("watch", gulp.series("dev:dirs", "build", "watch:main"));

/***********************************************
 * Production
 ***********************************************/

gulp.task("dist:clean", function(next) {
  rimraf(paths.dist, next);
});

gulp.task("dist:copy", function() {
  return gulp.src(paths.output + "**/*")
    .pipe(gulp.dest(paths.dist));
});
