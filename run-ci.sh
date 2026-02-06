#!/bin/bash

set -e

echo "################ test common ################"
pushd common
pnpm install
pnpm run fmt:clj:check
pnpm run lint:clj
clojure -M:dev:test
pnpm run test
popd

echo "################ test frontend ################"
pushd frontend
pnpm install
pnpm run fmt:clj:check
pnpm run fmt:js:check
pnpm run lint:scss
pnpm run lint:clj
pnpm run test
popd

echo "################ test integration ################"
pushd frontend
pnpm install
pnpm run test:e2e -x --workers=4
popd

echo "################ test backend ################"
pushd backend
pnpm install
pnpm run fmt:clj:check
pnpm run lint:clj
clojure -M:dev:test --reporter kaocha.report/documentation
popd

echo "################ test exporter ################"
pushd exporter
pnpm install
pnpm run fmt:clj:check
pnpm run lint:clj
popd

echo "################ test render-wasm ################"
pushd render-wasm
cargo fmt --check
./lint --debug
./test
popd
