;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.grid-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(defn- setup-grid []
  (let [store   (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
        _       (set! st/state store)
        _       (set! st/stream (ptk/input-stream store))
        context (api/create-context plugin-id)
        board   (.createBoard ^js context)
        grid    (.addGridLayout ^js board)]
    {:store store :context context :board board :grid grid}))

(t/deftest add-column-at-index-accepts-fixed-track-type
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [^js grid]} (setup-grid)]
        (.addColumn grid "flex" 1)
        (.addColumnAtIndex grid 0 "fixed" 100)
        (t/is (= "fixed" (aget (aget (.-columns grid) 0) "type")))
        (t/is (= 100 (aget (aget (.-columns grid) 0) "value")))))))
