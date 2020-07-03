#!/usr/bin/env bash
source ~/.bashrc

set -ex;

yarn install
clojure -Adev tools.clj build:tests
node ./target/tests/main
