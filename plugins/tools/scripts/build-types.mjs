import { copy } from 'fs-extra';

const source = 'libs/plugin-types';
const dist = 'dist/plugin-types';

const handleErr = (err) => {
  console.error(err);
  process.exit(1);
};

copy(`${source}/package.json`, `${dist}/package.json`).catch(handleErr);
copy(`${source}/README.md`, `${dist}/README.md`).catch(handleErr);
copy(`${source}/index.d.ts`, `${dist}/index.d.ts`).catch(handleErr);
copy(`LICENSE`, `${dist}/LICENSE`).catch(handleErr);
