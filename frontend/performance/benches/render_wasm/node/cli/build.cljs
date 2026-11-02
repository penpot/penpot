;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.build
  "Options for preparing a benchmark build."
  (:require
   [benches.render-wasm.node.cli.shared :as shared]))

(def schema:options
  [:map {:closed true}
   [:default-features {:default true} :boolean]
   [:features {:default []} [:vector shared/schema:feature]]
   [:build-timeout-ms {:default 300000} shared/schema:timeout-ms]])
