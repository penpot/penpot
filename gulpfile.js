// Main Gulp
var gulp = require("gulp");

var clean = require("gulp-clean");
var scss = require("gulp-sass");
var plumber = require("gulp-plumber");
var autoprefixer = require('gulp-autoprefixer');
var watch = require("gulp-watch");

// Paths
var paths = {};
paths.app = "resources/public/";
paths.dist = "resources/public/";

paths.scss = paths.app + "styles/**/*.scss";

gulp.task("scss", function () {
    return gulp.src(paths.app + "styles/main.scss")
    .pipe(plumber())
    .pipe(scss({ style: "expanded" }))
    .pipe(gulp.dest(paths.dist + "css/"));
});

gulp.task("autoprefixer", ["scss"], function () {
    return gulp.src(paths.dist + "css/main.css")
    .pipe(autoprefixer('last 2 version', 'safari 5', 'ie 8', 'ie 9', 'opera 12.1', 'ios 6', 'android 4'))
    .pipe(gulp.dest(paths.dist + "css/"));
});


// Default
gulp.task("dist", ["autoprefixer"]);

// Watch
gulp.task("default", ["dist"], function () {
    gulp.watch(paths.scss, ["autoprefixer"]);
});
