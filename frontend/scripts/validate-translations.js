import { promises as fs } from "fs";
import gt from "gettext-parser";
import l from "lodash";
import path from "path";

async function* getFiles(dir) {
  const dirents = await fs.readdir(dir, { withFileTypes: true });
  for (const dirent of dirents) {
    const res = path.resolve(dir, dirent.name);
    if (dirent.isDirectory()) {
      yield* getFiles(res);
    } else {
      yield res;
    }
  }
}

(async () => {
  const fileRe = /.+\.po$/;
  const target = path.normalize("./translations/");
  const parent = path.join(target, "..");
  for await (const f of getFiles(target)) {
    if (!fileRe.test(f)) continue;
    const entry = path.relative(parent, f);
    console.log(`=> processing: ${entry}`);
    const content = await fs.readFile(f);
    const data = gt.po.parse(content, "utf-8");
    const buff = gt.po.compile(data, { sort: true });
    await fs.writeFile(f, buff);
  }
})();
