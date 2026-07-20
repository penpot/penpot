import esbuild from 'esbuild';
import { copy } from 'fs-extra';
import { dirname, resolve } from 'path';
import { fileURLToPath } from 'url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const source = resolve(root, 'libs/plugins-styles');
const dist = resolve(root, 'dist/plugins-styles');

const handleErr = (err) => {
  console.error(err);
  process.exit(1);
};

Promise.all([
  esbuild.build({
    entryPoints: [`${source}/src/lib/styles.css`],
    bundle: true,
    outfile: `${dist}/styles.css`,
    minify: true,
    loader: {
      '.svg': 'dataurl',
    },
  }),
  copy(`${source}/package.json`, `${dist}/package.json`),
  copy(`${source}/README.md`, `${dist}/README.md`),
  copy(resolve(root, 'LICENSE'), `${dist}/LICENSE`),
]).catch(handleErr);
