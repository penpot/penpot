#!/usr/bin/env bash
source ~/.bashrc

set -ex;

npm ci
clojure -Adev tools.clj build:tests
node ./target/tests/main
