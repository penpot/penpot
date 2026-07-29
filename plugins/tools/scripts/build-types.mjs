import { copy } from 'fs-extra';
import { dirname, resolve } from 'path';
import { fileURLToPath } from 'url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const source = resolve(root, 'libs/plugin-types');
const dist = resolve(root, 'dist/plugin-types');

const handleErr = (err) => {
  console.error(err);
  process.exit(1);
};

Promise.all([
  copy(`${source}/package.json`, `${dist}/package.json`),
  copy(`${source}/README.md`, `${dist}/README.md`),
  copy(`${source}/index.d.ts`, `${dist}/index.d.ts`),
  copy(resolve(root, 'LICENSE'), `${dist}/LICENSE`),
]).catch(handleErr);
