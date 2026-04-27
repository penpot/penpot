;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.main.data.workspace-mcp-test
  (:require
   [app.main.data.workspace.mcp :as sut]
   [cljs.test :as t]
   [potok.v2.core :as ptk]))

(t/deftest set-plugin-panel-visible-updates-state
  (let [state {}
        evt   (sut/set-plugin-panel-visible false)
        next  (ptk/update evt state)]
    (t/is (false? (get-in next [:mcp :plugin-panel-visible]))))
    (let [evt2  (sut/set-plugin-panel-visible true)
          next2 (ptk/update evt2 next)]
      (t/is (true? (get-in next2 [:mcp :plugin-panel-visible]))))))
