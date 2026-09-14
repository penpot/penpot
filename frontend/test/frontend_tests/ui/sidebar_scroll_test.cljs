;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.sidebar-scroll-test
  (:require
   [app.main.ui.workspace.sidebar.scroll :as sc]
   [app.util.timers :as tm]
   [cljs.test :as t :include-macros true]))

(def ^:private restore-loop! @#'sc/restore-loop!)

(defn- scroll-event
  [scroll-top]
  #js {:target #js {:scrollTop scroll-top}})

(t/deftest save-scroll-stores-position-per-key
  (let [store (atom {})]
    (sc/save-scroll! store [:layers :page-1] (scroll-event 250))
    (sc/save-scroll! store [:assets :file-1] (scroll-event 80))
    (t/is (= {[:layers :page-1] 250
              [:assets :file-1] 80}
             @store))))

(t/deftest save-scroll-ignores-events-without-target
  (let [store (atom {})]
    (sc/save-scroll! store [:layers :page-1] nil)
    (sc/save-scroll! store [:layers :page-1] #js {})
    (t/is (= {} @store))))

(t/deftest restore-scroll-writes-saved-position
  (let [node #js {:scrollTop 0}]
    (sc/restore-scroll! node 320)
    (t/is (= 320 (.-scrollTop node)))))

(t/deftest needs-restore-retry
  (t/is (true? (sc/needs-restore-retry? -1 300 0)))
  (t/is (true? (sc/needs-restore-retry? 100 300 9)))
  (t/is (false? (sc/needs-restore-retry? 300 300 0)))
  (t/is (false? (sc/needs-restore-retry? 100 300 10))))

(t/deftest restore-loop-writes-once-when-content-settled
  (let [node      #js {:scrollTop 0 :scrollHeight 300}
        scheduled (atom [])
        raf*      (volatile! nil)]
    (with-redefs [tm/raf (fn [f] (swap! scheduled conj f) 1)]
      (restore-loop! node 250 300 0 raf*)
      (t/is (= 250 (.-scrollTop node)))
      (t/is (empty? @scheduled) "no follow-up when height is stable")
      (t/is (nil? @raf*)))))

(t/deftest restore-loop-retries-while-content-grows
  (let [node      #js {:scrollTop 0 :scrollHeight 300}
        scheduled (atom [])
        raf*      (volatile! nil)]
    (with-redefs [tm/raf (fn [f] (swap! scheduled conj f) 1)]
      (restore-loop! node 250 -1 0 raf*)
      (t/is (= 250 (.-scrollTop node)))
      (t/is (= 1 (count @scheduled)) "follow-up scheduled while growing")
      (t/is (= 1 @raf*) "frame id tracked for cancellation")
      ;; Next frame: content settled at the same height, retry stops.
      ((first @scheduled))
      (t/is (= 250 (.-scrollTop node)))
      (t/is (= 1 (count @scheduled)) "no further frames once settled"))))
