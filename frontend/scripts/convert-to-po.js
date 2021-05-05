const l  = require("lodash");
const fs = require("fs");
const gt = require("gettext-parser");

function generateLang(data, lang) {
  let output = {};

  for (let key of Object.keys(data)) {
    const trObj = data[key];
    const trRef = trObj["used-in"];

    let content = trObj.translations[lang];
    let comments = {};

    if (l.isNil(content)) {
      continue;
    } else {
      let result = {
        msgid: key,
        comments: {}
      }

      if (l.isArray(trRef)) {
        result.comments.reference = trRef.join(", ");
      }

      if (trObj.permanent) {
        result.comments.flag = "permanent";
      }

      if (l.isArray(content)) {
        result.msgid_plural = key;
        result.msgstr = content;
      } else if (l.isString(content)) {
        result.msgstr = [content];
      } else {
        throw new Error("unexpected");
      }

      output[key] = result;
    }
  }

  if (lang.includes("_")) {
    const [a, b] = lang.split("_");
    lang = `${a}_${b.toUpperCase()}`;
  }

  const poData = {
    charset: "utf-8",
    headers: {
      "Language": lang,
      "MIME-Version": "1.0",
      "Content-Type": "text/plain; charset=UTF-8",
      "Content-Transfer-Encoding": "8bit",
      "Plural-Forms": "nplurals=2; plural=(n != 1);"
    },
    "translations": {
      "": output
    }
  }
  const buff = gt.po.compile(poData, {sort: true});
  fs.writeFileSync(`./translations/${lang}.po`, buff);
}

const content = fs.readFileSync("./resources/locales.json");
const data = JSON.parse(content);
const langs = ["de"];

for (let lang of langs) {
  generateLang(data, lang);
}

