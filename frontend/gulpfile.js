const gulp = require("gulp");
const scss = require("gulp-sass");
const autoprefixer = require('gulp-autoprefixer');
const rimraf = require("rimraf");
const mustache = require("gulp-mustache");
const rename = require("gulp-rename");
const gulpif = require("gulp-if");
const gzip = require("gulp-gzip");
const cleancss = require("gulp-clean-css");
const fs = require("fs");
const l = require("lodash");

const paths = {};
paths.app = "./resources/";
paths.output = "./resources/public/";
paths.dist = "./dist/";
paths.target = "./target/";
paths.scss = paths.app + "styles/**/*.scss";

/***********************************************
 * Helper Tasks
 ***********************************************/

gulp.task("clean", function(next) {
  rimraf(paths.output + "css/", function() {
    rimraf(paths.output + "js/", function() {
      next()
    });
  });
});

gulp.task("dist:clean", function(next) {
  rimraf(paths.dist, next);
});

function makeAutoprefixer() {
  return autoprefixer('last 2 version',
                      'safari 5',
                      'ios 6',
                      'android 4');
}


function isProduction() {
  return (process.env.NODE_ENV === 'production');
}

/***********************************************
 * Development
 ***********************************************/

// Styles

function scssPipeline(options) {
  return function() {
    const input = options.input;
    const output = options.output;

    return gulp.src(input)
      .pipe(scss({
        style: "expanded",
        errLogToConsole: false
      }).on("error", (err) => {
        console.log(err.messageFormatted);
      }))
      .pipe(makeAutoprefixer())
      .pipe(gulpif(isProduction, cleancss()))
      .pipe(gulp.dest(output));
  };
}

gulp.task("scss:main", scssPipeline({
  input: paths.app + "styles/main.scss",
  output: paths.output + "css/"
}));

gulp.task("scss:view", scssPipeline({
  input: paths.app + "styles/view.scss",
  output: paths.output + "css/"
}));

gulp.task("scss", gulp.parallel("scss:main", "scss:view"));

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

// Templates

function templatePipeline(options) {
  return function() {
    const input = options.input;
    const output = options.output;
    const ts = Math.floor(new Date());

    const locales = readLocales();

    const tmpl = mustache({
      ts: ts,
      tr: JSON.stringify(locales),
    });

    return gulp.src(input)
      .pipe(tmpl)
      .pipe(rename("index.html"))
      .pipe(gulp.dest(output));
  };
}

gulp.task("template:main", templatePipeline({
  input: paths.app + "templates/index.mustache",
  output: paths.output
}));

gulp.task("template:view", templatePipeline({
  input: paths.app + "templates/view.mustache",
  output: paths.output + "view/"
}));

gulp.task("templates", gulp.parallel("template:view", "template:main"));

// Entry Point

gulp.task("watch:main", function() {
  gulp.watch(paths.scss, gulp.task("scss"));
  gulp.watch([paths.app + "templates/*.mustache",
              paths.app + "locales.json"],
             gulp.task("templates"));
});

gulp.task("watch", gulp.series(
  gulp.parallel("scss", "templates"),
  gulp.task("watch:main")
));

/***********************************************
 * Production
 ***********************************************/

gulp.task("dist:clean", function(next) {
  rimraf(paths.dist, next);
});

// Templates

gulp.task("dist:template:main", templatePipeline({
  input: paths.app + "index.mustache",
  output: paths.dist,
}));

gulp.task("dist:template:view", templatePipeline({
  input: paths.app + "view.mustache",
  output: paths.dist + "view/",
}));

gulp.task("dist:templates", gulp.parallel("dist:template:view", "dist:template:main"));

// Styles

gulp.task("dist:scss:main", scssPipeline({
  input: paths.app + "styles/main.scss",
  output: paths.dist + "css/"
}));

gulp.task("dist:scss:view", scssPipeline({
  input: paths.app + "styles/view.scss",
  output: paths.dist + "css/"
}));

gulp.task("dist:scss", gulp.parallel("dist:scss:main", "dist:scss:view"));

// Copy

gulp.task("dist:copy:fonts", function() {
  return gulp.src(paths.output + "/fonts/**/*")
    .pipe(gulp.dest(paths.dist + "fonts/"));
});

gulp.task("dist:copy:images", function() {
  return gulp.src(paths.output + "/images/**/*")
    .pipe(gulp.dest(paths.dist + "images/"));
});


gulp.task("dist:copy", gulp.parallel("dist:copy:fonts", "dist:copy:images"));

// GZip

gulp.task("dist:gzip", function() {
  return gulp.src(`${paths.dist}**/!(*.gz|*.br|*.jpg|*.png)`)
    .pipe(gzip({gzipOptions: {level: 9}}))
    .pipe(gulp.dest(paths.dist));
});

// Entry Point

gulp.task("dist", gulp.parallel(
  gulp.task("dist:templates"),
  gulp.task("dist:scss"),
  gulp.task("dist:copy")
));

