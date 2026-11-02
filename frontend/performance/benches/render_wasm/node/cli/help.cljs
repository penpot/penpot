;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.help
  "Options for benchmark CLI help.")

(def schema:options
  [:map {:closed true}
   [:topic {:default nil}
    [:maybe [:enum "build" "run" "ab" "compare" "merge" "purge"]]]])
