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

gulp.task("scss", function() {
    return gulp.src(paths.app + "styles/main.scss")
                .pipe(plumber())
                .pipe(scss({style: "expanded"}))
                .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("autoprefixer", function() {
    return gulp.src(paths.output + "css/main.css")
               .pipe(autoprefixer('last 2 version', 'safari 5', 'ie 8', 'ie 9', 'opera 12.1', 'ios 6', 'android 4'))
               .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("cssmin", function() {
  return gulp.src(paths.output + "css/main.css")
             .pipe(cssmin())
             .pipe(gulp.dest(paths.output + "css/"));
});

gulp.task("template", function() {
  var ts = Math.floor(new Date());
  var tmpl = mustache({
    jsfile: "/js/main.js?v=" + ts,
    cssfile: "/css/main.css?v=" + ts
  })

  return gulp.src(paths.app + "index.mustache")
             .pipe(tmpl)
             .pipe(rename("index.html"))
             .pipe(gulp.dest(paths.output));
});

gulp.task("styles-dev", function(next) {
  runseq("scss", "autoprefixer", next);
});

gulp.task("styles-dist", function(next) {
  runseq("scss", "autoprefixer", next);
});

gulp.task("clean-dist", function(next) {
  rimraf(paths.dist, next);
});

gulp.task("clean", function(next) {
  rimraf(paths.output + "css/", function() {
    rimraf(paths.output + "js/", function() {
      rimraf(paths.target, next);
    });
  });
});

gulp.task("copy", function() {
  return gulp.src(paths.output + "/**/*.*")
             .pipe(gulp.dest(paths.dist));
});

// Default
gulp.task("dist", function(next) {
  runseq("styles-dist", "cssmin", "template", "clean-dist", "copy", next);
});

// Watch
gulp.task("default", ["styles-dev", "template"], function () {
    gulp.watch(paths.scss, ["styles-dev"]);
});
