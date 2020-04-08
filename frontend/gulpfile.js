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

function readConfig() {
  const publicURL = process.env.UXBOX_PUBLIC_URL;
  const demoWarn = process.env.UXBOX_DEMO_WARNING;
  const deployDate = process.env.UXBOX_DEPLOY_DATE;
  const deployCommit = process.env.UXBOX_DEPLOY_COMMIT;

  let cfg = {
    demoWarning: demoWarn === "true"
  };

  if (publicURL !== undefined) {
    cfg.publicURL = publicURL;
  }

  if (deployDate !== undefined) {
    cfg.deployDate = deployDate;
  }

  if (deployCommit !== undefined) {
    cfg.deployCommit = deployCommit;
  }

  return JSON.stringify(cfg);
}

function templatePipeline(options) {
  return function() {
    const input = options.input;
    const output = options.output;
    const ts = Math.floor(new Date());
    const th = process.env.UXBOX_THEME || 'light';
    const themes = ['light', 'dark'];

    const locales = readLocales();
    const config = readConfig();

    const tmpl = mustache({
      ts: ts,
      th: th,
      config: JSON.stringify(config),
      translations: JSON.stringify(locales),
      themes: JSON.stringify(themes),
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

gulp.task("scss:main-light", scssPipeline({
  input: paths.resources + "styles/main-light.scss",
  output: paths.output + "css/main-light.css"
}));

gulp.task("scss:main-dark", scssPipeline({
  input: paths.resources + "styles/main-dark.scss",
  output: paths.output + "css/main-dark.css"
}));

gulp.task("scss", gulp.parallel("scss:main-light", "scss:main-dark"));

gulp.task("svg:sprite", function() {
  return gulp.src(paths.resources + "images/icons/*.svg")
    .pipe(rename({prefix: 'icon-'}))
    .pipe(svgSprite({mode:{symbol: {inline: false}}}))
    .pipe(gulp.dest(paths.output + "images/svg-sprite/"));
});

gulp.task("template:main", templatePipeline({
  input: paths.resources + "templates/index.mustache",
  output: paths.output
}));

gulp.task("templates", gulp.series("template:main"));

/***********************************************
 * Development
 ***********************************************/

gulp.task("dev:clean", function(next) {
  rimraf(paths.output, next);
});

gulp.task("dev:copy:images", function() {
  return gulp.src(paths.resources + "images/**/*")
    .pipe(gulp.dest(paths.output + "images/"));
});

gulp.task("dev:copy:fonts", function() {
  return gulp.src(paths.resources + "fonts/**/*")
    .pipe(gulp.dest(paths.output + "fonts/"));
});

gulp.task("dev:copy", gulp.parallel("dev:copy:images",
                                    "dev:copy:fonts"));

gulp.task("dev:dirs", function(next) {
  mkdirp("./resources/public/css/").then(() => next())
});

gulp.task("watch:main", function() {
  gulp.watch(paths.scss, gulp.series("scss"));
  gulp.watch(paths.resources + "images/**/*",
             gulp.series("svg:sprite",
                         "dev:copy:images"));

  gulp.watch([paths.resources + "templates/*.mustache",
              paths.resources + "locales.json"],
             gulp.series("templates"));
});

gulp.task("watch", gulp.series(
  "dev:dirs",
  gulp.parallel("scss", "templates", "svg:sprite"),
  "dev:copy",
  "watch:main"
));

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

gulp.task("dist:gzip", function() {
  return gulp.src(`${paths.dist}**/!(*.gz|*.br|*.jpg|*.png)`)
    .pipe(gzip({gzipOptions: {level: 9}}))
    .pipe(gulp.dest(paths.dist));
});

gulp.task("dist", gulp.series(
  "dev:clean",
  "dist:clean",
  gulp.parallel("scss", "templates", "svg:sprite", "dev:copy"),
  "dist:copy"
));
