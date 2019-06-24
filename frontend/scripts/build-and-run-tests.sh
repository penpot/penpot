#!/usr/bin/env bash
source ~/.bashrc

npm install
npm run build:test || exit 1;

node ./target/tests/main
