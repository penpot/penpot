import esbuild from 'esbuild';
import { copy } from 'fs-extra';

const source = 'libs/plugins-styles';
const dist = 'dist/plugins-styles';

const handleErr = (err) => {
  console.error(err);
  process.exit(1);
};

esbuild
  .build({
    entryPoints: [`${source}/src/lib/styles.css`],
    bundle: true,
    outfile: `${dist}/styles.css`,
    minify: true,
    loader: {
      '.svg': 'dataurl',
    },
  })
  .catch(handleErr);

copy(`${source}/package.json`, `${dist}/package.json`).catch(handleErr);
copy(`${source}/README.md`, `${dist}/README.md`).catch(handleErr);
copy(`LICENSE`, `${dist}/LICENSE`).catch(handleErr);
