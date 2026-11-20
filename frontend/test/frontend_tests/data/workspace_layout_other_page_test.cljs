;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-layout-other-page-test
  "Regression tests for #13015 other-page layout.

  With render-wasm/v1, other-page `:layout/update` goes through the worker
  WASM path only (no CLJS `apply-modifiers` fallback). Without a worker the
  shapes stay unchanged. The SVG renderer still lays out other pages via
  CLJS `apply-modifiers`."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.worker :as mw]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
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

(t/deftest layout-update-on-another-page-svg-resizes-fill-children
  (t/async
    done
    (let [file  (setup-file)
          store (ths/setup-store file {:renderer :svg})
          event (#'dwsl/update-layout-positions {:page-id (cthi/id :page-b)
                                                 :ids [(cthi/id :board)]})]
      (ths/run-store
       store done [event]
       (fn [new-state]
         (let [file' (ths/get-file-from-state new-state)
               child (cths/get-shape file' :child :page-label :page-b)]
           (t/is (= 400 (:width child))
                 "svg other-page layout fills to board width minus padding")))))))

(t/deftest layout-update-on-another-page-wasm-without-worker-skips-cljs
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
           (t/is (= 100 (:width child))
                 "wasm other-page layout does not fall back to CLJS without a worker")
           (t/is (zero? (thw/call-count :propagate-modifiers))
                 "the live WASM module must not lay out another page")))))))

(t/deftest layout-update-on-another-page-commits-worker-transforms
  (t/async
    done
    (let [file     (setup-file)
          store    (ths/setup-store file)
          event    (#'dwsl/update-layout-positions {:page-id (cthi/id :page-b)
                                                    :ids [(cthi/id :board)]})
          child-id (cthi/id :child)
          ;; Scale the fill child from width 100 @ x=50 to width 400 @ x=50.
          child-mtx (gmt/matrix 4 0 0 1 -150 0)
          asks     (atom [])
          ask!     (mock/stub
                    (fn [message]
                      (swap! asks conj message)
                      (rx/of {:transforms {child-id child-mtx}})))]
      (mock/with-mocks
        {mw/instance (js-obj)
         mw/ask! ask!}
        (fn [inner-done]
          (ths/run-store
           store inner-done [event]
           (fn [new-state]
             (let [file' (ths/get-file-from-state new-state)
                   child (cths/get-shape file' :child :page-label :page-b)
                   layout-asks (filterv #(= :layout/propagate-modifiers (:cmd %))
                                        @asks)]
               (t/is (= 400 (:width child))
                     "worker transforms are committed into the other page")
               (t/is (zero? (thw/call-count :propagate-modifiers))
                     "the live WASM module must not lay out another page")
               (t/is (seq layout-asks)
                     "other-page layout asks the worker")
               (t/is (contains? (:objects (first layout-asks)) (cthi/id :board))
                     "worker receives the other page's objects")))))
        done))))
