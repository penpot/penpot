#!/usr/bin/env bash

source ~/.bashrc
export NODE_ENV=production;

set -ex

npm ci

npx gulp dist:clean || exit 1;
npx gulp dist || exit 1;

cp -r ./target/dist ./target/dist2
mv ./target/dist2 ./target/dist/dbg

clojure -Adev tools.clj dist:all || exit 1;

npx gulp dist:gzip || exit 1;
