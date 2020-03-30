#!/usr/bin/env bash

source ~/.bashrc
set -ex

npm ci

export NODE_ENV=production;

npx gulp dist:clean || exit 1;
npx gulp dist || exit 1;

shadow-cljs release main

npx gulp dist:gzip || exit 1;
