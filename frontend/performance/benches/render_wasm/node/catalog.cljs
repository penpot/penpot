;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.catalog
  "Loads case descriptors from a selected build, without the runner's registry."
  (:require
   ["module" :as node-module]
   [app.common.transit :as transit]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.declarations :as declarations]
   [benches.render-wasm.identity :as identity]))

(defn- load-catalog
  [build]
  (let [cases-path (:cases-path build)]
    (if (exists? js/require)
      (js/require cases-path)
      ((node-module/createRequire cases-path) cases-path))))

(defn build-identity
  "Reads and validates the selected catalog's compiled build facts."
  [build]
  (let [loaded (transit/decode-str (.buildIdentity ^js (load-catalog build)))]
    (identity/check (:id build) loaded)
    loaded))

(defn collect
  "Requires the selected cases.cjs and checks both identity responses.

  build comes from artifacts/load-build or a newly completed build. The
  createRequire branch also supports the Node ESM test entry points."
  [build selection]
  (let [catalog    (load-catalog build)
        loaded     (transit/decode-str (.buildIdentity ^js catalog))]
    (identity/check (:id build) loaded)
    (let [response (transit/decode-str
                    (.collectCases ^js catalog (codec/encode-str selection)))
          cases    (:cases response)]
      (identity/check (:id build) response)
      (when-not (and (vector? cases) (seq cases))
        (throw (ex-info "renderer build returned an invalid case catalog"
                        {:type ::invalid-catalog
                         :build-id (:id build)})))
      (mapv declarations/check-collected-case cases))))
