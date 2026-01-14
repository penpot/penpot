import fs from "node:fs/promises";
import * as h from "./_helpers.js";

await fs.mkdir("resources/public/js", {recursive: true});

await h.compileStorybookStyles();
await h.copyAssets();
await h.compileSvgSprites();
await h.compileTranslations();
await h.compileTemplates();
await h.compilePolyfills();
