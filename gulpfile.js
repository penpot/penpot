var gulp = require("gulp");
var runseq = require('run-sequence');
var scss = require("gulp-sass");
var plumber = require("gulp-plumber");
var autoprefixer = require('gulp-autoprefixer');
var watch = require("gulp-watch");
var cssmin = require("gulp-cssmin");
var rimraf = require("rimraf");
var mustache = require("gulp-mustache");
var rename = require("gulp-rename");

var paths = {};
paths.app = "./resources/";
paths.output = "./resources/public/";
paths.dist = "./dist/";
paths.target = "./target/";
paths.scss = paths.app + "styles/**/*.scss";

function makeAutoprefixer() {
  return autoprefixer('last 2 version',
                      'safari 5',
                      'ios 6',
                      'android 4');
}

function scssPipeline(options) {
  var input = options.input;
  var output = options.output;

  return gulp.src(input)
             .pipe(plumber())
             .pipe(scss({style: "expanded"}))
             .pipe(makeAutoprefixer())
             .pipe(gulp.dest(output));
}

gulp.task("scss:theme-light", function() {
  return scssPipeline({
    input: paths.app + "styles/main.scss",
    output: paths.output + "css/"
  });
});

// gulp.task("scss:theme-dark", function() {
//   return scssPipeline({
//     input: paths.app + "styles/main-theme-dark.scss",
//     output: paths.output + "css/"
//   });
// });

gulp.task("scss:all", [ // "scss:theme-dark",
                       "scss:theme-light"]);

gulp.task("dist:cssmin", function() {
  return gulp.src(paths.output + "css/main.css")
             .pipe(cssmin())
             .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("template", function() {
  var ts = Math.floor(new Date());
  var tmpl = mustache({
    jsfile: "/js/main.js?v=" + ts,
    cssfile: "/css/main.css?v=" + ts
  });

  return gulp.src(paths.app + "index.mustache")
             .pipe(tmpl)
             .pipe(rename("index.html"))
             .pipe(gulp.dest(paths.output));
});

gulp.task("dist:scss", function(next) {
  runseq("scss:all", "dist:cssmin", next);
});

gulp.task("dist:clean", function(next) {
  rimraf(paths.dist, next);
});

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

// Default
gulp.task("dist", function(next) {
  runseq("template", "dist:scss", "dist:clean", "dist:copy", next);
});

// Watch
gulp.task("default", ["scss:all", "template"], function () {
    gulp.watch(paths.scss, ["scss:all"]);
});
