import * as h from "./_helpers.js";

await h.compileStyles();
await h.copyAssets();
await h.compileSvgSprites();
await h.compileTemplates();
await h.compilePolyfills();
