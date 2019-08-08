#!/usr/bin/env bash
source ~/.bashrc

set -ex

npm ci

npx gulp dist:clean || exit 1
npx gulp dist || exit 1

clojure -Adev tools.clj dbg-dist:all || exit 1

npx gulp dist:gzip || exit 1
