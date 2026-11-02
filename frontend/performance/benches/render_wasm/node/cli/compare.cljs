;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.compare
  "Options for comparing saved benchmark records."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.cli.shared :as shared]))

(def schema:options
  [:merge shared/schema:comparison-permissions
   [:map {:closed true}
    [:baseline-path ::sm/text]
    [:candidate-path ::sm/text]
    [:case-pair {:default nil} [:maybe [:tuple ::sm/text ::sm/text]]]]])
