// The npm packages that the token resolution needs in the web worker
// (dashboard thumbnails). The worker build takes them from these globals:
// see `:resolve` in the `:worker` build of `shadow-cljs.edn`.
import StyleDictionary from "style-dictionary";
import * as sdTransforms from "@tokens-studio/sd-transforms";
import tinycolor from "tinycolor2";

globalThis.penpotStyleDictionary = { default: StyleDictionary };
globalThis.penpotSdTransforms = sdTransforms;
globalThis.penpotTinycolor = { default: tinycolor };
