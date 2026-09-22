#!/usr/bin/env node
// QA for PO translation files: finds words glued by a missing space,
// broken placeholders and lost plural structures.
//
// Usage (from `frontend/`, like `translations.js`):
//   node ./scripts/check-translations.js [-l <locale>] [--self-test]
//
// Exit: 0 with no errors (warnings don't fail), 1 with errors,
// 2 on misuse or unreadable files.
//
// Language data lives in `./scripts/check-translations/words.<locale>.txt`
// (sections: [elision] [function] [common] [ok] [brands]). Without a
// catalog only the language-independent checks run (placeholders,
// plurals, punctuation, camelCase).

import getopts from "getopts";
import { promises as fs } from "node:fs";
import gt from "gettext-parser";

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
        for (const re of PLACEHOLDER_RES) {
          const nEn = countIn(textEn, re);
          const nLoc = countIn(text, re);
          if (nEn !== nLoc) {
            errors.push(
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

const options = getopts(process.argv.slice(2), {
  string: ["l"],
  boolean: ["self-test", "h"],
  alias: { locale: ["l"], help: ["h"] },
});

if (options.h) {
  console.log(`PO translation QA.
Usage: node ./scripts/check-translations.js [-l <locale>] [--self-test]
  -l: locale to check (default: ca), from frontend/
  --self-test: validate the detector with built-in cases`);
  process.exit(0);
}

if (options["self-test"]) {
  const catalog = await loadCatalog("ca");
  if (!catalog) {
    console.error("SELF-TEST FAILED: cannot load words.ca.txt");
    process.exit(2);
  }
  await selfTest(catalog);
} else {
  const locale = options.l ?? options.locale ?? "ca";
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
    console.error(`Could not read translations/${locale}.po: ${err.message}`);
    process.exit(2);
  }
  for (const w of result.warnings) console.log(`warn: ${w}`);
  for (const e of result.errors) console.error(`error: ${e}`);
  console.log(
    `${locale}: ${result.errors.length} errors, ${result.warnings.length} warnings`,
  );
  process.exit(result.errors.length > 0 ? 1 : 0);
}
