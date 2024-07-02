import gt from "gettext-parser";
import fs from "node:fs/promises";
import path from "node:path";
import util from "node:util";
import { execFile as execFileCb } from "node:child_process";

const execFile = util.promisify(execFileCb);

async function processMsgId(msgId) {
  return execFile("grep", ["-r", "-o", msgId, "./src"]).catch(() => {
    return msgId;
  });
}

async function processFile(f) {
  const content = await fs.readFile(f);
  const data = gt.po.parse(content, "utf-8");
  const translations = data.translations[""];
  const badIds = [];

  for (const property in translations) {
    const data = await processMsgId(translations[property].msgid);
    if (data != null && data.stdout === undefined) {
      badIds.push(data);
    }
  }

  return badIds;
}

async function cleanFile(f, badIds) {
  console.log("\n\nDoing automatic cleanup");

  const content = await fs.readFile(f);
  const data = gt.po.parse(content, "utf-8");
  const translations = data.translations[""];
  const keys = Object.keys(translations);

  for (const key of keys) {
    property = translations[key];
    if (badIds.includes(property.msgid)) {
      console.log("----> deleting", property.msgid);
      delete data.translations[""][key];
    }
  }

  const buff = gt.po.compile(data, { sort: true });
  await fs.writeFile(f, buff);
}

async function findExecutionTimeTranslations() {
  const { stdout } = await execFile("grep", [
    "-r",
    "-h",
    "-F",
    "(tr (",
    "./src",
  ]);
  console.log(stdout);
}

async function welcome() {
  console.log(
    "####################################################################",
  );
  console.log(
    "#                UNUSED TRANSLATIONS FINDER                        #",
  );
  console.log(
    "####################################################################",
  );
  console.log("\n");
  console.log(
    "DISCLAIMER: Some translations are only available at execution time.",
  );
  console.log("            This finder can't process them, so there can be");
  console.log("            false positives.\n");
  console.log("            If you want to do an automatic clean anyway,");
  console.log("            call the script with:");
  console.log("            npm run find-unused-translations -- --clean");
  console.log("            For example:");
  console.log(
    "--------------------------------------------------------------------",
  );
  await findExecutionTimeTranslations();
  console.log(
    "--------------------------------------------------------------------",
  );
}

const doCleanup = process.argv.slice(2)[0] == "--clean";

(async () => {
  await welcome();
  const target = path.normalize("./translations/en.po");
  const badIds = await processFile(target);

  if (doCleanup) {
    cleanFile(target, badIds);
  } else {
    for (const badId of badIds) {
      console.log(badId);
    }
  }
})();
