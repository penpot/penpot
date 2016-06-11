const gulp = require("gulp");
const runseq = require('run-sequence');
const scss = require("gulp-sass");
const plumber = require("gulp-plumber");
const autoprefixer = require('gulp-autoprefixer');
const watch = require("gulp-watch");
const cssmin = require("gulp-cssmin");
const rimraf = require("rimraf");
const mustache = require("gulp-mustache");
const rename = require("gulp-rename");

const paths = {};
paths.app = "./resources/";
paths.output = "./resources/public/";
paths.dist = "./dist/";
paths.target = "./target/";
paths.scss = paths.app + "styles/**/*.scss";

/***********************************************
 * Styles
 ***********************************************/

function makeAutoprefixer() {
  return autoprefixer('last 2 version',
                      'safari 5',
                      'ios 6',
                      'android 4');
}

function scssPipeline(options) {
  const input = options.input;
  const output = options.output;

  return gulp.src(input)
    .pipe(plumber())
    .pipe(scss({style: "expanded"}))
    .pipe(makeAutoprefixer())
    .pipe(gulp.dest(output));
}

gulp.task("scss:main", function() {
  return scssPipeline({
    input: paths.app + "styles/main.scss",
    output: paths.output + "css/"
  });
});

gulp.task("scss:preview", function() {
  return scssPipeline({
    input: paths.app + "styles/preview.scss",
    output: paths.output + "css/"
  });
});

gulp.task("scss", ["scss:main", "scss:preview"]);

/***********************************************
 * Templates
 ***********************************************/

gulp.task("template:main", function() {
  const ts = Math.floor(new Date());
  const tmpl = mustache({
    jsfile: "/js/main.js?v=" + ts,
    cssfile: "/css/main.css?v=" + ts
  });

  return gulp.src(paths.app + "index.mustache")
    .pipe(tmpl)
    .pipe(rename("index.html"))
    .pipe(gulp.dest(paths.output));
});

gulp.task("template:preview", function() {
  const ts = Math.floor(new Date());
  const tmpl = mustache({
    jsfile: "/js/preview.js?v=" + ts,
    cssfile: "/css/preview.css?v=" + ts
  });

  return gulp.src(paths.app + "preview.mustache")
    .pipe(tmpl)
    .pipe(rename("index.html"))
    .pipe(gulp.dest(paths.output + "preview/"));
});

gulp.task("template", ["template:preview",
                       "template:main"]);

/***********************************************
 * Production Build
 ***********************************************/

gulp.task("dist:cssmin:main", function() {
  return gulp.src(paths.output + "css/main.css")
    .pipe(cssmin())
    .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("dist:cssmin:preview", function() {
  return gulp.src(paths.output + "css/preview.css")
    .pipe(cssmin())
    .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("dist:cssmin", ["dist:cssmin:main",
                          "dist:cssmin:preview"]);

gulp.task("dist:scss", function(next) {
  runseq("scss", "dist:cssmin", next);
});

gulp.task("dist:clean", function(next) {
  rimraf(paths.dist, next);
});

/***********************************************
 * Helper Tasks
 ***********************************************/

gulp.task("clean", function(next) {
  rimraf(paths.output + "css/", function() {
    rimraf(paths.output + "js/", function() {
      rimraf(paths.target, next);
    });
  });
});

gulp.task("dist:copy", function() {
  return gulp.src(paths.output + "/**/*.*")
    .pipe(gulp.dest(paths.dist));
});

/***********************************************
 * Entry Points
 ***********************************************/

// Default
gulp.task("dist", function(next) {
  runseq(["template", "dist:scss"], "dist:clean", "dist:copy", next);
});

// Watch
gulp.task("default", ["scss", "template"], function () {
  gulp.watch(paths.scss, ["scss"]);
});
