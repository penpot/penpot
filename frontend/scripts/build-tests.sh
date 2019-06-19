#!/usr/bin/env bash
source ~/.bashrc

npm install
npm run build:main || exit 1;
npm run build:view || exit 1;
npm run build:worker || exit 1;

# TODO
#node ./out/tests.js
