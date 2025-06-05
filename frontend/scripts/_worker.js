import proc from "node:child_process";
import fs from "node:fs/promises";
import ph from "node:path";
import url from "node:url";
import * as sass from "sass-embedded";
import log from "fancy-log";

import wpool from "workerpool";
import postcss from "postcss";
import modulesProcessor from "postcss-modules";
import autoprefixerProcessor from "autoprefixer";

const compiler = await sass.initAsyncCompiler();

async function compileFile(path) {
  const dir = ph.dirname(path);
  const name = ph.basename(path, ".scss");
  const dest = `${dir}${ph.sep}${name}.css`;

  return new Promise(async (resolve, reject) => {
    try {
      const result = await compiler.compileAsync(path, {
        loadPaths: [
          "node_modules/animate.css",
          "resources/styles/common/",
          "resources/styles",
        ],
        sourceMap: false,
        silenceDeprecations: ["import", "mixed-decls"],
      });
      // console.dir(result);
      resolve({
        inputPath: path,
        outputPath: dest,
        css: result.css,
      });
    } catch (cause) {
      console.error(cause);
      reject(cause);
    }
  });
}

function configureModulesProcessor(options) {
  const ROOT_NAME = "app";

  return modulesProcessor({
    getJSON: (cssFileName, json, outputFileName) => {
      // We do nothing because we don't want the generated JSON files
    },
    // Calculates the whole css-module selector name.
    // Should be the same as the one in the file `/src/app/main/style.clj`
    generateScopedName: (selector, filename, css) => {
      const dir = ph.dirname(filename);
      const name = ph.basename(filename, ".css");
      const parts = dir.split("/");
      const rootIdx = parts.findIndex((s) => s === ROOT_NAME);
      return parts.slice(rootIdx + 1).join("_") + "_" + name + "__" + selector;
    },
  });
}

function configureProcessor(options = {}) {
  const processors = [];

  if (options.modules) {
    processors.push(configureModulesProcessor(options));
  }
  processors.push(autoprefixerProcessor);

  return postcss(processors);
}

async function postProcessFile(data, options) {
  const proc = configureProcessor(options);

  // We compile to the same path (all in memory)
  const result = await proc.process(data.css, {
    from: data.outputPath,
    to: data.outputPath,
    map: false,
  });

  return Object.assign(data, {
    css: result.css,
  });
}

async function compile(path, options) {
  let result = await compileFile(path);
  return await postProcessFile(result, options);
}

wpool.worker(
  {
    compileSass: compile,
  },
  {
    onTerminate: async (code) => {
      // log.info("worker: terminate");
      await compiler.dispose();
    },
  },
);
