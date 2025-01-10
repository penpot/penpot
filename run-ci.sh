#!/bin/bash

set -e

echo "################ test common ################"
cd common
yarn install
yarn run fmt:clj:check
yarn run lint:clj
clojure -M:dev:test
yarn run test
cd ..

echo "################ test frontend ################"
cd frontend
yarn install
yarn run fmt:clj:check
yarn run fmt:js:check
yarn run lint:scss
yarn run lint:clj
yarn run test
cd ..

echo "################ test integration ################"
cd frontend
yarn install
yarn run test:e2e -x --workers=4
cd ..

echo "################ test backend ################"
cd backend
yarn install
yarn run fmt:clj:check
yarn run lint:clj
clojure -M:dev:test --reporter kaocha.report/documentation
cd ..

echo "################ test exporter ################"
cd exporter
yarn install
yarn run fmt:clj:check
yarn run lint:clj
cd ..

echo "################ test render-wasm ################"
cd render-wasm
cargo fmt --check
./test
cd ..

