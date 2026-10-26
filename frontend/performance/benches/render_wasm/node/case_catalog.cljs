;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.case-catalog
  "Node-library entry points compiled with the browser's scene declarations.

  Export collect-cases as collectCases and build-identity as buildIdentity.
  Each build's cases.cjs must keep its own JavaScript scope and registry."
  (:require
   [app.common.transit :as transit]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.identity :as identity]))

(defn collect-cases
  [encoded-selection]
  (codec/encode-str
   {:build-id identity/build-id
    :cases (cases/collect-cases (transit/decode-str encoded-selection))}))

(defn build-identity
  []
  (codec/encode-str (identity/data)))
