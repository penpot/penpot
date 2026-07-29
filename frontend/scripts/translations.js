#!/usr/bin/env node

import getopts from "getopts";
import { promises as fs, createReadStream } from "fs";
import gt from "gettext-parser";
import l from "lodash";
import path from "path";
import readline from "readline";

const baseLocale = "en";

async function* getFiles(dir) {
  // console.log("getFiles", dir)
  const dirents = await fs.readdir(dir, { withFileTypes: true });
  for (const dirent of dirents) {
    let res = path.resolve(dir, dirent.name);
    res = path.relative(".", res);

    if (dirent.isDirectory()) {
      yield* getFiles(res);
    } else {
      yield res;
    }
  }
}

async function translationExists(locale) {
  const target = path.normalize("./translations/");
  const targetPath = path.join(target, `${locale}.po`);

  try {
    const result = await fs.stat(targetPath);
    return true;
  } catch (cause) {
    return false;
  }
}

async function readLocaleByPath(path) {
  const content = await fs.readFile(path);
  return gt.po.parse(content, "utf-8");
}

async function writeLocaleByPath(path, data) {
  const buff = gt.po.compile(data, { sort: true });
  await fs.writeFile(path, buff);
}

async function readLocale(locale) {
  const target = path.normalize("./translations/");
  const targetPath = path.join(target, `${locale}.po`);
  return readLocaleByPath(targetPath);
}

async function writeLocale(locale, data) {
  const target = path.normalize("./translations/");
  const targetPath = path.join(target, `${locale}.po`);
  return writeLocaleByPath(targetPath, data);
}

async function* scanLocales() {
  const fileRe = /.+\.po$/;
  const target = path.normalize("./translations/");
  const parent = path.join(target, "..");

  for await (const f of getFiles(target)) {
    if (!fileRe.test(f)) continue;
    const data = path.parse(f);
    yield data;
  }
}

async function processLocale(options, f) {
  let locales = options.locale;
  if (typeof locales === "string") {
    locales = locales.split(/,/);
  } else if (Array.isArray(locales)) {
  } else if (locales === undefined) {
  } else {
    console.error(`Invalid value found on locales parameter: '${locales}'`);
    process.exit(-1);
  }

  for await (const { name } of scanLocales()) {
    if (locales === undefined || locales.includes(name)) {
      await f(name);
    }
  }
}

async function processTranslation(data, prefix, f) {
  for (let key of Object.keys(data.translations[""])) {
    if (key === prefix || key.startsWith(prefix)) {
      let value = data.translations[""][key];
      value = await f(value);
      data.translations[""][key] = value;
    }
  }
  return data;
}

async function* readLines(filePath) {
  const fileStream = createReadStream(filePath);

  const reader = readline.createInterface({
    input: fileStream,
    crlfDelay: Infinity,
  });

  let counter = 1;

  for await (const line of reader) {
    yield [counter, line];
    counter++;
  }
}

const trRe1 = /\(tr\s+"([\w\.\-]+)"/g;

function getTranslationStrings(line) {
  const result = Array.from(line.matchAll(trRe1)).map((match) => {
    return match[1];
  });

  return result;
}

async function deleteByPrefix(options, prefix, ...params) {
  if (!prefix) {
    console.error(`Prefix undefined`);
    process.exit(1);
  }

  await processLocale(options, async (locale) => {
    const data = await readLocale(locale);
    let deleted = [];

    for (const [key, value] of Object.entries(data.translations[""])) {
      if (key.startsWith(prefix)) {
        delete data.translations[""][key];
        deleted.push(key);
      }
    }

    await writeLocale(locale, data);

    console.log(
      `=> Processed locale '${locale}': deleting prefix '${prefix}' (deleted=${deleted.length})`,
    );

    if (options.verbose) {
      for (let key of deleted) {
        console.log(`-> Deleted key: ${key}`);
      }
    }
  });
}

async function markFuzzy(options, prefix, ...other) {
  if (!prefix) {
    console.error(`Prefix undefined`);
    process.exit(1);
  }

  await processLocale(options, async (locale) => {
    let data = await readLocale(locale);
    data = await processTranslation(data, prefix, (translation) => {
      if (translation.comments === undefined) {
        translation.comments = {};
      }

      const flagData = translation.comments.flag ?? "";
      const flags = flagData.split(/\s*,\s*/).filter((s) => s !== "");

      if (!flags.includes("fuzzy")) {
        flags.push("fuzzy");
      }

      translation.comments.flag = flags.join(", ");

      console.log(
        `=> Processed '${locale}': marking fuzzy '${translation.msgid}'`,
      );

      return translation;
    });

    await writeLocale(locale, data);
  });
}

async function rehash(options, ...other) {
  const fileRe = /.+\.(?:clj|cljs|cljc)$/;

  // Iteration 1: process all locales and update it with existing
  // entries on the source code.

  const used = await (async function () {
    const result = {};

    for await (const f of getFiles("src")) {
      if (!fileRe.test(f)) continue;

      for await (const [n, line] of readLines(f)) {
        const strings = getTranslationStrings(line);

        strings.forEach((key) => {
          const entry = `${f}:${n}`;
          if (result[key] !== undefined) {
            result[key].push(entry);
          } else {
            result[key] = [entry];
          }
        });
      }
    }

    await processLocale({ locale: baseLocale }, async (locale) => {
      const data = await readLocale(locale);

      for (let [key, val] of Object.entries(result)) {
        let entry = data.translations[""][key];

        if (entry === undefined) {
          entry = {
            msgid: key,
            comments: {
              reference: val.join(", "),
              flag: "fuzzy",
            },
            msgstr: [""],
          };
        } else {
          if (entry.comments === undefined) {
            entry.comments = {};
          }

          entry.comments.reference = val.join(", ");

          const flagData = entry.comments.flag ?? "";
          let flags = flagData.split(/\s*,\s*/).filter((s) => s !== "");

          if (flags.includes("unused")) {
            flags = flags.filter((o) => o !== "unused");
          }

          entry.comments.flag = flags.join(", ");
        }

        data.translations[""][key] = entry;
      }

      await writeLocale(locale, data);

      const keys = Object.keys(data.translations[""]);
      console.log(`=> Found ${keys.length} used translations`);
    });

    return result;
  })();

  // Iteration 2: process only base locale and properly detect unused
  // translation strings.

  await (async function () {
    let totalUnused = 0;

    await processLocale({ locale: baseLocale }, async (locale) => {
      const data = await readLocale(locale);

      for (let [key, val] of Object.entries(data.translations[""])) {
        if (key === "") continue;

        if (!used.hasOwnProperty(key)) {
          totalUnused++;

          const entry = data.translations[""][key];
          if (entry.comments === undefined) {
            entry.comments = {};
          }

          const flagData = entry.comments.flag ?? "";
          const flags = flagData.split(/\s*,\s*/).filter((s) => s !== "");

          if (!flags.includes("unused")) {
            flags.push("unused");
          }

          entry.comments.flag = flags.join(", ");

          data.translations[""][key] = entry;
        }
      }

      await writeLocale(locale, data);
    });

    console.log(`=> Found ${totalUnused} unused strings`);
  })();
}

async function synchronize(options, ...other) {
  const baseData = await readLocale(baseLocale);

  await processLocale(options, async (locale) => {
    if (locale === baseLocale) return;

    const data = await readLocale(locale);

    for (let [key, val] of Object.entries(baseData.translations[""])) {
      if (key === "") continue;

      const baseEntry = baseData.translations[""][key];
      const entry = data.translations[""][key];

      if (entry === undefined) {
        // Do nothing
      } else {
        entry.comments = baseEntry.comments;
        data.translations[""][key] = entry;
      }
    }

    for (let [key, val] of Object.entries(data.translations[""])) {
      if (key === "") continue;

      const baseEntry = baseData.translations[""][key];
      const entry = data.translations[""][key];

      if (baseEntry === undefined) {
        delete data.translations[""][key];
      }
    }

    await writeLocale(locale, data);
  });
}

const options = getopts(process.argv.slice(2), {
  boolean: ["h", "v"],
  alias: {
    help: ["h"],
    locale: ["l"],
    verbose: ["v"],
  },
  stopEarly: true,
});

const [command, ...params] = options._;

if (command === "rehash") {
  await rehash(options, ...params);
} else if (command === "sync") {
  await synchronize(options, ...params);
} else if (command === "delete") {
  await deleteByPrefix(options, ...params);
} else if (command === "fuzzy") {
  await markFuzzy(options, ...params);
} else {
  console.log(`Translations manipulation script.
How to use:
./scripts/translation.js <options> <subcommand>

Available options:

  --locale -l     : specify a concrete locale
  --verbose -v    : enables verbose output
  --help -h       : prints this help

Available subcommands:

  rehash          : reads and writes all translations files, sorting and validating
  sync            : synchronize baselocale file with all other locale files
  delete <prefix> : delete all entries that matches the prefix
  fuzzy <prefix>  : mark as fuzzy all entries that matches the prefix
`);
}
