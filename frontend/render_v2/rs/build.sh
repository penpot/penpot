#!/bin/bash

EMSDK_QUIET=1 . "/usr/local/emsdk/emsdk_env.sh"

EMCC_CFLAGS="--no-entry -s ERROR_ON_UNDEFINED_SYMBOLS=0 -s MAX_WEBGL_VERSION=2 -s MODULARIZE=1 -s EXPORT_NAME=createRustSkiaModule -s EXPORTED_RUNTIME_METHODS=GL -s ENVIRONMENT=web" cargo build --target=wasm32-unknown-emscripten

