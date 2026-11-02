;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.ab
  "Options for comparing configurations of Cargo features."
  (:require
   [benches.render-wasm.node.cli.shared :as shared]))

(def schema:options
  [:merge shared/schema:measurement shared/schema:comparison-permissions
   [:map {:closed true}
    [:features [:vector {:min 1} shared/schema:feature]]
    [:max-features {:default 3} [:int {:min 1 :max 6}]]
    [:confirm {:default false} :boolean]
    [:dry-run {:default false} :boolean]
    [:build-timeout-ms {:default 300000} shared/schema:timeout-ms]]])
