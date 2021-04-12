const fs = require("fs");
const l = require("lodash");
const path = require("path");

const gulp = require("gulp");
const gulpConcat = require("gulp-concat");
const gulpGzip = require("gulp-gzip");
const gulpMustache = require("gulp-mustache");
const gulpPostcss = require("gulp-postcss");
const gulpRename = require("gulp-rename");
const gulpSass = require("gulp-sass");
const svgSprite = require("gulp-svg-sprite");

const merge = require('gulp-merge-json');

const autoprefixer = require("autoprefixer")
const clean = require("postcss-clean");
const mkdirp = require("mkdirp");
const rimraf = require("rimraf");
const sass = require("sass");

const mapStream = require("map-stream");
const paths = {};
paths.resources = "./resources/";
paths.output = "./resources/public/";
paths.dist = "./target/dist/";


/***********************************************
 * Helpers
 ***********************************************/

// Templates

function readLocales() {
  const path = __dirname + "/resources/locales.json";
  const content = JSON.parse(fs.readFileSync(path, {encoding: "utf8"}));

  let result = {};
  for (let key of Object.keys(content)) {
    const item = content[key];
    if (l.isString(item)) {
      result[key] = {"en": item};
    } else if (l.isPlainObject(item) && l.isPlainObject(item.translations)) {
      result[key] = item.translations;
    }
  }

  return JSON.stringify(result);
}

function readManifest() {
  try {
    const path = __dirname + "/resources/public/js/manifest.json";
    const content = JSON.parse(fs.readFileSync(path, {encoding: "utf8"}));

    const index = {
      "config": "/js/config.js?ts=" + Date.now(),
      "polyfills": "js/polyfills.js?ts=" + Date.now(),
    };

    for (let item of content) {
      index[item.name] = "/js/" + item["output-name"];
    };

    return index;
  } catch (e) {
    console.error("Error on reading manifest, using default.");
    return {
      "config": "/js/config.js",
      "polyfills": "js/polyfills.js",
      "main": "/js/main.js",
      "shared": "/js/shared.js",
      "worker": "/js/worker.js"
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

    const ts = Math.floor(new Date());
    const th = process.env.APP_THEME || "default";
    const themes = ["default"];

    const locales = readLocales();
    const manifest = readManifest();

    const tmpl = gulpMustache({
      ts: ts,
      th: th,
      manifest: manifest,
      translations: JSON.stringify(locales),
      themes: JSON.stringify(themes),
    });

    return gulp.src(input)
      .pipe(tmpl)
      .pipe(gulpRename("index.html"))
      .pipe(gulp.dest(output))
      .pipe(touch());
  };
}

/***********************************************
 * Generic
 ***********************************************/

gulpSass.compiler = sass;

gulp.task("scss", function() {
  return gulp.src(paths.resources + "styles/main-default.scss")
    .pipe(gulpSass().on('error', gulpSass.logError))
    .pipe(gulpPostcss([
      autoprefixer,
      // clean({format: "keep-breaks", level: 1})
    ]))
    .pipe(gulp.dest(paths.output + "css/"));
});

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
  input: paths.resources + "templates/index.mustache",
  output: paths.output
}));

gulp.task("templates", gulp.series("svg:sprite:icons", "svg:sprite:cursors", "template:main"));

gulp.task("polyfills", function() {
  return gulp.src(paths.resources + "polyfills/*.js")
    .pipe(gulpConcat("polyfills.js"))
    .pipe(gulp.dest(paths.output + "js/"));
});

gulp.task("split", function(done) {
  const data = __dirname + "/resources/locales.json";
  const content = JSON.parse(fs.readFileSync(data, {encoding: "utf8"}));
  const languages = ["en", "ca", "de", "es", "fr", "ru", "tr", "zh_cn"];
  let result = {};
  languages.forEach(lang => {
      for (let key of Object.keys(content)) {
          const item = content[key];

          if (l.isPlainObject(item) && l.isPlainObject(item.translations)) {
              let tr = item.translations[lang]

              if (l.isEmpty(tr) && !l.isArray(tr)) {
                if ((process.argv.slice(2)[1]) === "--forTranslation") {
                  tr = "MISSING TRANSLATION"
                } else {
                  tr = ""
                }
              }  

              if ((process.argv.slice(2)[2]) === "--withEn") {
                result[key] = { "translations": {
                                      "en": item.translations["en"],
                                      [lang]: tr 
                                  },
                                  "used-in": item['used-in'],
                                  "unused": item.unused
                              };
              } else {
                result[key] = { "translations": {
                                      [lang]: tr 
                                  },
                                  "used-in": item['used-in'],
                                  "unused": item.unused
                              };
              }
              
          }
      }
      fs.writeFileSync(__dirname + "/resources/translation/" + lang + ".json", JSON.stringify(result, null, 2));
  });
  done();
});

gulp.task("merge", function(done) {
  gulp.src(__dirname + "/resources/translation/*.json")
  .pipe(merge({
  fileName: 'locales.json',
      jsonSpace: '  ',
      jsonReplacer: (key, value) => {
          if (value === 'MISSING TRANSLATION') {
            return "";
          }

          return value;
        }
  }))
  .pipe(gulp.dest(__dirname + "/resources/"));
  done();
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
  gulp.watch(paths.resources + "styles/**/**.scss", gulp.series("scss"));
  gulp.watch(paths.resources + "images/**/*", gulp.series("copy:assets:images"));

  gulp.watch([paths.resources + "templates/*.mustache",
              paths.resources + "locales.json"],
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
