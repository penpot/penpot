#!/usr/bin/env bash

esbuild --bundle --minify --sourcemap --target=es2021 --format=esm --platform=browser editor/TextEditor.js --outfile=dist/TextEditor.mjs
