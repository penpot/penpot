#!/usr/bin/env node

import getopts from "getopts";
import { promises as fs, createReadStream } from "node:fs";
import gt from "gettext-parser";
import path from "node:path";
import readline from "node:readline";

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
    // getopts yields "" (not undefined) when -l is absent: an empty
    // list must mean "all locales", never "no locale".
    locales = locales.split(/,/).filter((s) => s !== "");
    if (locales.length === 0) {
      locales = undefined;
    }
  } else if (Array.isArray(locales)) {
  } else if (locales === undefined) {
  } else {
    console.error(`Invalid value found on locales parameter: '${locales}'`);
    process.exit(2);
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

    // Both frontend and shared sources: common holds schemas and
    // helpers whose translation keys must stay alive as well.
    for (const dir of ["src", "../common/src"]) {
      for await (const f of getFiles(dir)) {
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

// Brands kept as-is in every locale.
const GENERIC_BRANDS = [
  "GitHub",
  "GitLab",
  "YouTube",
  "InVision",
  "innerShadow",
  "dropShadow",
  "iOS",
  "macOS",
];

const TOKEN_RE = /[\p{L}\p{M}]+(?:[·'’\-][\p{L}\p{M}]+)*/gu;
const SKIP_RE = /[%{@/\\=<>|#0-9]/;
const PLACEHOLDER_RES = [/%[sd]/g, /\{[^}]*\}/g, /%\([^)]*\)[sd]/g];
const PUNCT_RE = /[,.:;!?…»)\]]([A-Za-zÀ-Úà-ú«("“‘$])/gu;
const CAMEL_RE = /[a-zàèéíòóúüç·]([A-ZÀÈÉÍÒÓÚÜ][a-zàèéíòóúü]+)/gu;
const PH_GLUED_RE = /%[sd](?=[A-Za-zÀ-Úà-ú])/gu;
const APOSTROPHE_DIGIT_RE = /[a-zàèéíòóúüç·]d['’][0-9]/gu;

function tokenize(text) {
  return [...text.matchAll(TOKEN_RE)].map((m) => m[0].toLowerCase());
}

function elisionBase(token, elision) {
  if (!elision) return null;
  const m = token.match(new RegExp(`^[${elision}]['’](.+)$`));
  return m ? m[1] : null;
}

function countIn(text, re) {
  re.lastIndex = 0;
  return [...text.matchAll(re)].length;
}

// A word is valid as one half of a split. Never the whole token:
// a repeated glue (`del'equip` x3) must not validate itself by frequency.
function validPart(word, words, freq) {
  return (
    words.functionWords.has(word) ||
    words.okWords.has(word) ||
    words.commonWords.has(word) ||
    (freq.get(word) ?? 0) >= 1
  );
}

// Every (left, right, rule) split of a token, including each
// hyphen-separated segment.
function* splits(tok, words, freq) {
  const cands = [tok, ...tok.split("-")];
  const seen = new Set();
  for (const c of cands) {
    if (c.length < 3) continue;
    for (let k = 1; k < c.length; k++) {
      const left = c.slice(0, k);
      const right = c.slice(k);
      if (left.length < 1) continue;
      if (right.length < 2 && !words.functionWords.has(right)) continue;
      const key = left + "|" + right;
      if (seen.has(key)) continue;
      seen.add(key);
      const rightBase = elisionBase(right, words.elision) ?? right;
      const rightOk = validPart(rightBase, words, freq);
      const leftOk = validPart(left, words, freq);
      if (words.functionWords.has(left) && rightOk)
        yield [left, right, "func-left"];
      else if (words.functionWords.has(right) && right.length <= 4 && leftOk)
        yield [left, right, "func-right"];
      else if (
        c.length >= 8 &&
        left.length >= 3 &&
        right.length >= 2 &&
        leftOk &&
        rightOk
      )
        yield [left, right, "content"];
    }
  }
}

const RULE_ORDER = { "func-left": 0, "func-right": 1, content: 2 };

function bestSplit(tok, words, freq) {
  let best = null;
  for (const [left, right, rule] of splits(tok, words, freq)) {
    if (left.includes("-")) continue;
    if (
      !best ||
      RULE_ORDER[rule] < RULE_ORDER[best[2]] ||
      (RULE_ORDER[rule] === RULE_ORDER[best[2]] && left.length > best[0].length)
    ) {
      best = [left, right, rule];
    }
  }
  return best;
}

function folded(s) {
  return s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
}

function distance(a, b) {
  const dp = Array.from({ length: a.length + 1 }, (_, i) => [i]);
  for (let j = 1; j <= b.length; j++) dp[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      dp[i][j] = Math.min(
        dp[i - 1][j] + 1,
        dp[i][j - 1] + 1,
        dp[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    }
  }
  return dp[a.length][b.length];
}

// Resemblance to the source: a correct word almost always resembles
// its cognate in `en`/`es`; a glued one never does. A pure affix
// (1-4 letters more or less) doesn't count: that is exactly the
// shape of a glue (`lapolitica` vs `politica`, `desdel` vs `desde`).
function isCognate(tok, refToks, elision) {
  const base = tok.replace(new RegExp(`^[${elision || "-"}]['’]`), "");
  const t = folded(base);
  const limit = t.length <= 4 ? 0 : 1;
  for (const r of refToks) {
    const rt = folded(r);
    if (Math.abs(rt.length - t.length) > Math.max(limit, 4)) continue;
    const d = distance(t, rt);
    if (d === 0) return true;
    if (d > limit) continue;
    if (isAffix(t, rt)) continue;
    return true;
  }
  return false;
}

function isAffix(t, rt) {
  const dif = Math.abs(t.length - rt.length);
  if (dif < 1 || dif > 4) return false;
  return (
    t.startsWith(rt) || t.endsWith(rt) || rt.startsWith(t) || rt.endsWith(t)
  );
}

function refToks(...texts) {
  const toks = new Set();
  for (const text of texts) for (const t of tokenize(text)) toks.add(t);
  return toks;
}

function cleanContext(frag) {
  return !/https?:|www\.|@|\|target:|\.mcp\.json/.test(frag);
}

function checkPunctuation(text, brands) {
  const found = [];
  for (const m of text.matchAll(PUNCT_RE)) {
    const punct = m[0][0];
    const next = m[1];
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    if (!cleanContext(frag)) continue;
    if (m[0] === "](") continue; // markdown link
    if (m[0] === ":s" && frag.includes("|target:")) continue; // [x|target:self]
    if (/%[sd]\.\(/.test(frag)) continue; // %s.(suffix)... notation
    if (punct === "." && !/[A-ZÀÈÉÍÒÓÚÜ«"“(%$]/.test(next)) continue;
    found.push({ what: m[0], frag });
  }
  for (const m of text.matchAll(CAMEL_RE)) {
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    if (brands.some((mk) => frag.includes(mk))) continue;
    found.push({ what: m[0], frag });
  }
  for (const m of text.matchAll(PH_GLUED_RE)) {
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    found.push({ what: m[0], frag });
  }
  for (const m of text.matchAll(APOSTROPHE_DIGIT_RE)) {
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    found.push({ what: m[0], frag });
  }
  return found;
}

function loadFile(path) {
  return fs.readFile(path).then((buf) => gt.po.parse(buf, "utf-8"));
}

function parseCatalog(text, locale) {
  const words = {
    functionWords: new Set(),
    commonWords: new Set(),
    okWords: new Set(),
    brands: [],
    elision: "",
  };
  const sections = { function: 1, common: 1, ok: 1, brands: 1, elision: 1 };
  let current = null;
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line === "" || line.startsWith("#")) continue;
    const sec = line.match(/^\[([a-z]+)\]$/);
    if (sec) {
      if (!sections[sec[1]]) {
        throw new Error(`words.${locale}.txt: unknown section [${sec[1]}]`);
      }
      current = sec[1];
      continue;
    }
    if (!current) {
      throw new Error(`words.${locale}.txt: word outside any section`);
    }
    if (current === "elision") {
      if (!/^[a-z]+$/.test(line)) {
        throw new Error(`words.${locale}.txt: bad [elision] line`);
      }
      words.elision += line;
    } else if (current === "brands") {
      words.brands.push(line);
    } else {
      words[`${current}Words`].add(line.toLowerCase());
    }
  }
  return words;
}

async function loadCatalog(locale) {
  const path = `./scripts/check-translations/words.${locale}.txt`;
  try {
    return parseCatalog(await fs.readFile(path, "utf-8"), locale);
  } catch (err) {
    if (err.code === "ENOENT") return null;
    throw err;
  }
}

function isFuzzy(entry) {
  return (entry.comments?.flag ?? "").split(/,\s*/).includes("fuzzy");
}

async function check(locale, words) {
  const [data, dataEn, dataEs] = await Promise.all([
    loadFile(`./translations/${locale}.po`),
    loadFile("./translations/en.po"),
    locale === "es" ? null : loadFile("./translations/es.po").catch(() => null),
  ]);
  const entries = data.translations[""];
  const entriesEn = dataEn.translations[""];
  const entriesEs = dataEs ? dataEs.translations[""] : {};
  const brands = [...GENERIC_BRANDS, ...(words?.brands ?? [])];
  const errors = [];
  const warnings = [];

  const freq = new Map();
  for (const [msgid, e] of Object.entries(entries)) {
    if (msgid === "" || isFuzzy(e)) continue;
    for (const t of e.msgstr)
      for (const w of tokenize(t)) {
        freq.set(w, (freq.get(w) ?? 0) + 1);
      }
  }

  for (const [msgid, e] of Object.entries(entries)) {
    if (msgid === "" || isFuzzy(e)) continue;
    const texts = e.msgstr;
    const eEn = entriesEn[msgid];
    const textsEn = eEn ? eEn.msgstr : [];
    const eEs = entriesEs[msgid];
    const textsEs = eEs ? eEs.msgstr : [];

    if (eEn?.msgid_plural && !e.msgid_plural) {
      errors.push(
        `${msgid}: source uses msgid_plural but ${locale} lacks msgstr[0]/[1]`,
      );
    }

    texts.forEach((text, i) => {
      if (!text) return;
      const textEn = textsEn[i] ?? "";
      const textEs = textsEs[i] ?? "";
      const refs = refToks(textEn, textEs);
      if (textEn) {
        const unused = (e.comments?.flag ?? "")
          .split(/,\s*/)
          .includes("unused");
        for (const re of PLACEHOLDER_RES) {
          const nEn = countIn(textEn, re);
          const nLoc = countIn(text, re);
          if (nEn !== nLoc) {
            // Unused keys are never rendered: report, don't fail.
            // "Fixing" them by deleting placeholders can destroy
            // content that a reactivation may need.
            (unused ? warnings : errors).push(
              `${msgid}[${i}]: placeholder mismatch ${re.source}: en=${nEn} ${locale}=${nLoc}`,
            );
          }
        }
      }
      for (const t of checkPunctuation(text, brands)) {
        errors.push(
          `${msgid}: glued punctuation ${JSON.stringify(t.what)} ...${t.frag}...`,
        );
      }
      if (!words) return;
      for (const tok of tokenize(text)) {
        if (SKIP_RE.test(tok)) continue;
        if (words.okWords.has(tok)) continue;
        // No whole-token frequency skip: a repeated glue
        // (`del'equip` x3) must never validate itself by frequency.
        const base = elisionBase(tok, words.elision);
        if (base && validPart(base, words, freq)) continue;
        if (isCognate(tok, refs, words.elision)) continue;
        const part = bestSplit(tok, words, freq);
        if (!part) continue;
        const [left, right, rule] = part;
        const line = `${msgid}: glued word ${JSON.stringify(tok)} -> ${left} + ${right}`;
        if (rule === "content") warnings.push(`${line} (review)`);
        else errors.push(line);
      }
    });
  }
  return { errors, warnings };
}

const FIXTURES = [
  // [text, expectsError]
  ["Els membres del'equip continuaran.", true],
  ["Accepteu lapolítica de privadesa.", true],
  ["Si necessiteu més informació,contacteu amb nosaltres.", true],
  ["Revisions delPenpot disponibles.", true],
  ["Seleccioneu Lowercase oCapitalize.", true],
  ["Cobreix fins a %seditors nous.", true],
  ["Bienvenido acasa nueva.", true],
  ["Les biblioteques compartides.", false],
  ["Edita el webhook.", false],
  ["Emplenament del grup.", false],
  ["S'està desant el fitxer.", false],
  ["Els components no es poden niar.", false],
  ["Gira horitzontalment.", false],
  ["Desbloquegeu les funcions.", false],
  ["Atributs SVG importats.", false],
  ["Commuta la negreta.", false],
  ["Bibliotecas compartidas.", false],
];

async function selfTest(catalog) {
  // Minimal vocabulary for the fixtures.
  const freq = new Map(
    "els membres continuaran accepteu de privadesa si necessiteu més informació amb nosaltres revisions disponibles seleccioneu lowercase infrequent les biblioteques compartides edita el webhook emplenament del grup està desant fitxer components no es poden niar gira commuta la negreta desbloquegeu les funcions atributs svg importats horitzontalment podeu crear equip política casa bibliotecas compartidas bienvenido nueva"
      .split(" ")
      .map((w) => [w, 3]),
  );
  const words = {
    functionWords: catalog.functionWords,
    commonWords: new Set(),
    okWords: new Set(),
    brands: [],
    elision: catalog.elision,
  };
  let bad = 0;
  for (const [text, expectsError] of FIXTURES) {
    const found = [];
    for (const t of checkPunctuation(text, GENERIC_BRANDS))
      found.push(`punct:${t.what}`);
    for (const tok of tokenize(text)) {
      if (SKIP_RE.test(tok) || words.functionWords.has(tok)) continue;
      if (words.okWords.has(tok)) continue;
      const base = elisionBase(tok, words.elision);
      if (base && validPart(base, words, freq)) continue;
      const part = bestSplit(tok, words, freq);
      if (part && part[2] !== "content") found.push(`tok:${tok}`);
    }
    if (found.length > 0 !== expectsError) {
      console.error(
        `SELF-TEST FAILED: ${JSON.stringify(text)} expected error=${expectsError}, found=${JSON.stringify(found)}`,
      );
      bad++;
    }
  }
  if (bad > 0) process.exit(1);
  console.log(`SELF-TEST OK: ${FIXTURES.length} cases`);
}

const HELP_TOP = `PO translation toolkit.
Usage: node ./scripts/translations.js <command> [options]

Available options (work before or after the command):

  --locale -l <locale> : restrict the command to one locale
  --verbose -v        : verbose output
  --help -h           : this help, or help for <command>

Available subcommands (run from \`frontend/\`):
`;

const COMMANDS = {
  rehash: {
    args: "",
    help: 'Scan ./src and ../common/src for (tr "key") usages and update en.po references.',
    run: (options, params) => rehash(options, ...params),
  },
  sync: {
    args: "[-l <locale>]",
    help: "Copy #: references and flags from en.po into each locale.",
    run: (options, params) => synchronize(options, ...params),
  },
  delete: {
    args: "<prefix> [-l <locale>]",
    help: "Delete every entry whose key starts with <prefix>.",
    run: (options, params) => {
      if (!params[0]) {
        console.error("delete needs a <prefix>");
        process.exit(2);
      }
      return deleteByPrefix(options, ...params);
    },
  },
  fuzzy: {
    args: "<prefix> [-l <locale>]",
    help: "Mark as fuzzy every entry whose key starts with <prefix>.",
    run: (options, params) => {
      if (!params[0]) {
        console.error("fuzzy needs a <prefix>");
        process.exit(2);
      }
      return markFuzzy(options, ...params);
    },
  },
  check: {
    args: "-l <locale> [--self-test]",
    help: "QA a locale PO: glued words, placeholders, plurals.",
    run: async (options, params) => {
      if (options["self-test"]) {
        const catalog = await loadCatalog("ca");
        if (!catalog) {
          console.error("SELF-TEST FAILED: cannot load words.ca.txt");
          process.exit(2);
        }
        await selfTest(catalog);
        return;
      }
      const locale = options.l ?? options.locale;
      if (!locale) {
        console.error("check needs -l <locale>");
        process.exit(2);
      }
      const catalog = await loadCatalog(locale);
      if (!catalog) {
        console.log(
          `note: no word catalog for '${locale}', lexical checks skipped`,
        );
      }
      let result;
      try {
        result = await check(locale, catalog);
      } catch (err) {
        console.error(
          `Could not read translations/${locale}.po: ${err.message}`,
        );
        process.exit(2);
      }
      for (const w of result.warnings) console.log(`warn: ${w}`);
      for (const e of result.errors) console.error(`error: ${e}`);
      console.log(
        `${locale}: ${result.errors.length} errors, ${result.warnings.length} warnings`,
      );
      process.exit(result.errors.length > 0 ? 1 : 0);
    },
  },
};

const options = getopts(process.argv.slice(2), {
  boolean: ["h", "v", "self-test"],
  string: ["l"],
  alias: {
    help: ["h"],
    locale: ["l"],
    verbose: ["v"],
  },
});

const [command, ...params] = options._;

function printHelp() {
  console.log(HELP_TOP);
  for (const [name, cmd] of Object.entries(COMMANDS)) {
    console.log(`  ${name} ${cmd.args}\n    ${cmd.help}\n`);
  }
}

if (!command || options.h || options.help) {
  if (command && !COMMANDS[command]) {
    console.error(`Unknown command '${command}'.`);
    printHelp();
    process.exit(2);
  }
  if (command) {
    const cmd = COMMANDS[command];
    console.log(
      `Usage: node ./scripts/translations.js ${command} ${cmd.args}\n\n${cmd.help}`,
    );
  } else {
    printHelp();
  }
} else if (!COMMANDS[command]) {
  console.error(`Unknown command '${command}'.`);
  printHelp();
  process.exit(2);
} else {
  await COMMANDS[command].run(options, params);
}
