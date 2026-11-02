;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.purge
  "Options for purging managed benchmark artifacts.")

(def schema:options
  [:map {:closed true}
   [:target [:enum :builds :runs :comparisons :all]]])
