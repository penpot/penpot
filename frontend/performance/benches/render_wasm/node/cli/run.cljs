;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.run
  "Options for a benchmark run and its selected build source."
  (:require
   [benches.render-wasm.node.cli.build :as build]
   [benches.render-wasm.node.cli.shared :as shared]))

(def schema:build
  [:multi {:dispatch :kind}
   [:prepare
    [:merge build/schema:options
     [:map {:closed true}
      [:kind [:= :prepare]]]]]
   [:existing
    [:map {:closed true}
     [:kind [:= :existing]]
     [:id shared/schema:build-id]]]])

(def schema:options
  [:merge shared/schema:measurement
   [:map {:closed true}
    [:build schema:build]
    [:remove {:default false} :boolean]]])
