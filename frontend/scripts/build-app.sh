#!/usr/bin/env bash

source ~/.bashrc
set -ex

npm ci

export NODE_ENV=production;

# Clean the output directory
npx gulp clean || exit 1;

shadow-cljs release main
npx gulp build || exit 1;
npx gulp dist:clean || exit 1;
npx gulp dist:copy || exit 1;
npx gulp dist:gzip || exit 1;
