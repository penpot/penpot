import * as h from "./_helpers.js";

await h.compileStyles();
await h.copyAssets();
await h.copyWasmPlayground();
await h.compileSvgSprites();
await h.compileTranslations();
await h.compileTemplates();
await h.compilePolyfills();
