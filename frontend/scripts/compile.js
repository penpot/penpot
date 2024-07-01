import fs from "node:fs/promises";
import ppt from "pretty-time";
import log from "fancy-log";
import * as h from "./_helpers.js";

await h.compileStyles();
await h.copyAssets();
await h.compileSvgSprites();
await h.compileTemplates();
await h.compilePolyfills();
