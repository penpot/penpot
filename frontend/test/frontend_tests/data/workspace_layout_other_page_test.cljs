;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-layout-other-page-test
  "Regression test for #13015: with render-wasm/v1 active, a `:layout/update`
  for a page other than the current one must still lay out its shapes."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.shape-layout :as dwsl]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(t/use-fixtures :each
  {:before thw/setup-wasm-mocks!
   :after  thw/teardown-wasm-mocks!})

(defn- setup-file
  []
  (-> (cthf/sample-file :file1 :page-label :page-a)
      (cthf/add-sample-page :page-b)
      (ctho/add-frame :board
                      :x 0 :y 0 :width 500 :height 300
                      :layout :flex
                      :layout-flex-dir :column
                      :layout-align-items :start
                      :layout-padding-type :multiple
                      :layout-padding {:p1 50 :p2 50 :p3 50 :p4 50})
      (cths/add-sample-shape :child
                             :type :rect
                             :parent-label :board
                             :x 50 :y 50 :width 100 :height 20
                             :layout-item-h-sizing :fill)
      (cthf/switch-to-page :page-a)))

(t/deftest layout-update-on-another-page-resizes-fill-children
  (t/async
    done
    (let [file  (setup-file)
          store (ths/setup-store file)
          event (#'dwsl/update-layout-positions {:page-id (cthi/id :page-b)
                                                 :ids [(cthi/id :board)]})]
      (ths/run-store
       store done [event]
       (fn [new-state]
         (let [file' (ths/get-file-from-state new-state)
               child (cths/get-shape file' :child :page-label :page-b)]
           (t/is (= 400 (:width child))
                 "the fill child takes the board width minus its padding")
           (t/is (zero? (thw/call-count :propagate-modifiers))
                 "WASM only holds the current page, so it must not lay out another one")))))))
