const fs = require("fs");
const path = require("path");
const l = require("lodash");

const CleanCSS = require("clean-css");
const gulp = require("gulp");
const gulpif = require("gulp-if");
const gzip = require("gulp-gzip");

const mustache = require("gulp-mustache");
const rename = require("gulp-rename");
const svgSprite = require("gulp-svg-sprite");

const mkdirp = require("mkdirp");
const rimraf = require("rimraf");
const sass = require("sass");
const autoprefixer = require('autoprefixer')
const postcss = require('postcss')

const paths = {};
paths.resources = "./resources/";
paths.output = "./resources/public/";
paths.build = "./target/build/";
paths.dist = "./target/dist/";
paths.scss = "./resources/styles/**/*.scss";

/***********************************************
 * Helpers
 ***********************************************/

function isProduction() {
  return (process.env.NODE_ENV === 'production');
}

function scssPipeline(options) {
  const write = (_path, data) => {
    return new Promise((resolve, reject) => {
      fs.writeFile(_path, data, function(err) {
        if (err) { reject(err); }
        else { resolve(); }
      });
    });
  };

  const render = (input) => {
    return new Promise((resolve, reject) => {
      sass.render({file: input}, async function(err, result) {
        if (err) {
          console.log(err.formatted);
          reject(err);
        } else {
          resolve(result.css);
        }
      });
    });
  };

  const postprocess = (data, input, output) => {
    return postcss([autoprefixer])
      .process(data, {map: false, from: input, to: output})
  };

  return function(next) {
    const input = options.input;
    const output = options.output;

    return mkdirp(path.dirname(output))
      .then(() => render(input))
      .then((res) => postprocess(res, input, output))
      .then((res) => write(output, res))
      .catch((err) => null)
      .then(() => {
        next();
      });
  };
}

// Templates

function readSvgSprite() {
  const path = paths.build + "/icons-sprite/symbol/svg/sprite.symbol.svg";
  const content = fs.readFileSync(path, {encoding: "utf8"});
  return content;
}

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

function templatePipeline(options) {
  return function() {
    const input = options.input;
    const output = options.output;
    const ts = Math.floor(new Date());

    const locales = readLocales();
    const icons = readSvgSprite();

    const tmpl = mustache({
      ts: ts,
      ic: icons,
      tr: JSON.stringify(locales),
    });

    return gulp.src(input)
      .pipe(tmpl)
      .pipe(rename("index.html"))
      .pipe(gulp.dest(output));
  };
}

/***********************************************
 * Generic
 ***********************************************/

gulp.task("scss:main", scssPipeline({
  input: paths.resources + "styles/main.scss",
  output: paths.build + "css/main.css"
}));

gulp.task("scss", gulp.parallel("scss:main"));

gulp.task("svg:sprite", function() {
  return gulp.src(paths.resources + "images/icons/*.svg")
    .pipe(rename({prefix: 'icon-'}))
    .pipe(svgSprite({mode:{symbol: {inline: true}}}))
    .pipe(gulp.dest(paths.build + "icons-sprite/"));
});

gulp.task("template:main", templatePipeline({
  input: paths.resources + "templates/index.mustache",
  output: paths.build
}));

gulp.task("templates", gulp.series("svg:sprite", "template:main"));

/***********************************************
 * Development
 ***********************************************/

gulp.task("dev:clean", function(next) {
  rimraf(paths.output, function() {
    rimraf(paths.build, next);
  });
});

gulp.task("dev:copy:images", function() {
  return gulp.src(paths.resources + "images/**/*")
    .pipe(gulp.dest(paths.output + "images/"));
});

gulp.task("dev:copy:css", function() {
  return gulp.src(paths.build + "css/**/*")
    .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("dev:copy:icons-sprite", function() {
  return gulp.src(paths.build + "icons-sprite/**/*")
    .pipe(gulp.dest(paths.output + "icons-sprite/"));
});

gulp.task("dev:copy:templates", function() {
  return gulp.src(paths.build + "index.html")
    .pipe(gulp.dest(paths.output));
});

gulp.task("dev:copy:fonts", function() {
  return gulp.src(paths.resources + "fonts/**/*")
    .pipe(gulp.dest(paths.output + "fonts/"));
});

gulp.task("dev:copy", gulp.parallel("dev:copy:images",
                                    "dev:copy:css",
                                    "dev:copy:fonts",
                                    "dev:copy:icons-sprite",
                                    "dev:copy:templates"));

gulp.task("dev:dirs", function(next) {
  mkdirp("./resources/public/css/")
    .then(() => next())
});

gulp.task("watch:main", function() {
  gulp.watch(paths.scss, gulp.series("scss", "dev:copy:css"));

  gulp.watch([paths.resources + "templates/*.mustache",
              paths.resources + "locales.json",
              paths.resources + "images/**/*"],
             gulp.series("templates", "dev:copy:images", "dev:copy:icons-sprite"));
});

gulp.task("watch", gulp.series(
  "dev:dirs",
  gulp.parallel("scss", "templates"),
  "dev:copy",
  "watch:main"
));

/***********************************************
 * Production
 ***********************************************/

gulp.task("dist:clean", function(next) {
  rimraf(paths.dist, function() {
    rimraf(paths.build, next);
  });
});

gulp.task("dist:copy:templates", function() {
  return gulp.src(paths.build + "index.html")
    .pipe(gulp.dest(paths.dist));
});

gulp.task("dist:copy:images", function() {
  return gulp.src(paths.resources + "images/**/*")
    .pipe(gulp.dest(paths.dist + "images/"));
});

gulp.task("dist:copy:styles", function() {
  return gulp.src(paths.build + "css/**/*")
    .pipe(gulp.dest(paths.dist + "css/"));
});

gulp.task("dist:copy:icons-sprite", function() {
  return gulp.src(paths.build + "icons-sprite/**/*")
    .pipe(gulp.dest(paths.dist + "icons-sprite/"));
});

gulp.task("dist:copy:fonts", function() {
  return gulp.src(paths.resources + "/fonts/**/*")
    .pipe(gulp.dest(paths.dist + "fonts/"));
});

gulp.task("dist:copy", gulp.parallel("dist:copy:fonts",
                                     "dist:copy:icons-sprite",
                                     "dist:copy:styles",
                                     "dist:copy:templates",
                                     "dist:copy:images"));

gulp.task("dist:gzip", function() {
  return gulp.src(`${paths.dist}**/!(*.gz|*.br|*.jpg|*.png)`)
    .pipe(gzip({gzipOptions: {level: 9}}))
    .pipe(gulp.dest(paths.dist));
});

gulp.task("dist", gulp.series(
  "dist:clean",
  "scss",
  "templates",
  "dist:copy"
));
