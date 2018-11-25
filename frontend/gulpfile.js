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
const gulpif = require("gulp-if");
const gzip = require("gulp-gzip");
const brotli = require("gulp-brotli");

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
      rimraf(paths.target, next);
    });
  });
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
      .pipe(plumber())
      .pipe(scss({style: "expanded"}))
      .pipe(makeAutoprefixer())
      .pipe(gulpif(isProduction, cssmin()))
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

gulp.task("scss", ["scss:main", "scss:view"]);

// Templates

function templatePipeline(options) {
  return function() {
    const input = options.input;
    const output = options.output;
    const jspath = options.jspath;
    const csspath = options.csspath;

    const ts = Math.floor(new Date());

    const tmpl = mustache({
      jsfile: `${jspath}?v=${ts}`,
      cssfile: `${csspath}?v=${ts}`
    });

    return gulp.src(input)
      .pipe(tmpl)
      .pipe(rename("index.html"))
      .pipe(gulp.dest(output));
  };
}

gulp.task("template:main", templatePipeline({
  input: paths.app + "index.mustache",
  output: paths.output,
  jspath: "/js/main.js",
  csspath: "/css/main.css"
}));

gulp.task("template:view", templatePipeline({
  input: paths.app + "view.mustache",
  output: paths.output + "view/",
  jspath: "/js/view.js",
  csspath: "/css/view.css"
}));

gulp.task("template", ["template:view",
                       "template:main"]);

// Entry Point

gulp.task("default", ["scss", "template"], function () {
  gulp.watch(paths.scss, ["scss"]);
});

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
  jspath: "/js/main.js",
  csspath: "/css/main.css"
}));

gulp.task("dist:template:view", templatePipeline({
  input: paths.app + "view.mustache",
  output: paths.dist + "view/",
  jspath: "/js/view.js",
  csspath: "/css/view.css"
}));

gulp.task("dist:template", ["dist:template:view",
                            "dist:template:main"]);

// Styles

gulp.task("dist:scss:main", scssPipeline({
  input: paths.app + "styles/main.scss",
  output: paths.dist + "css/"
}));

gulp.task("dist:scss:view", scssPipeline({
  input: paths.app + "styles/view.scss",
  output: paths.dist + "css/"
}));

gulp.task("dist:scss", ["dist:scss:main",
                        "dist:scss:view"]);

// Copy

gulp.task("dist:copy:fonts", function() {
  return gulp.src(paths.output + "/fonts/**/*")
    .pipe(gulp.dest(paths.dist + "fonts/"));
});

gulp.task("dist:copy:images", function() {
  return gulp.src(paths.output + "/images/**/*")
    .pipe(gulp.dest(paths.dist + "images/"));
});


gulp.task("dist:copy", ["dist:copy:fonts",
                        "dist:copy:images"]);

// GZip

gulp.task("dist:gzip", function() {
  return gulp.src(`${paths.dist}**/!(*.gz|*.br|*.jpg|*.png)`)
    .pipe(gzip({gzipOptions: {level: 9}}))
    .pipe(gulp.dest(paths.dist));
});

gulp.task("dist:brotli", function() {
  return gulp.src(`${paths.dist}**/!(*.gz|*.br|*.jpg|*.png)`)
    .pipe(brotli.compress({quality: 10}))
    .pipe(gulp.dest(paths.dist));
});

// Entry Point

gulp.task("dist", function(next) {
  runseq(["dist:clean"],
         ["dist:template", "dist:scss", "dist:copy"],
         //["dist:gzip", "dist:brotli"], 
         next);
});

