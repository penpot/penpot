#!/usr/bin/env bash
# NOTE: this script should be called from the parent directory to
# properly work

yarn run wasm-pack build ./renderer --target web --out-dir ../src/app/util/renderer/ --release
mkdir -p ./resources/public/js/renderer
mv ./src/app/util/renderer/renderer_bg.wasm ./resources/public/js/renderer/
echo "Patching renderer.js…"
sed -i 's/renderer_bg\.wasm/\/js\/renderer\/renderer_bg\.wasm/g' ./src/app/util/renderer/renderer.js
sed -i 's/, import\.meta\.url/, new URL(document\.baseURI)\.origin/g' ./src/app/util/renderer/renderer.js
echo "Done."
