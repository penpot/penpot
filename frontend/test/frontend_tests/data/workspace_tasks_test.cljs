;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-tasks-test
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tasks :as dwtk]
   [cljs.test :as t :include-macros true]))

(def ^:private board-id (uuid/custom 1 1))
(def ^:private button-id (uuid/custom 1 2))

(defn- objects
  []
  (let [board  (cts/setup-shape {:id board-id :type :frame :name "Checkout"
                                 :x 0 :y 0 :width 400 :height 800
                                 :parent-id uuid/zero :frame-id uuid/zero})
        button (cts/setup-shape {:id button-id :type :rect :name "Pay button"
                                 :x 20 :y 600 :width 300 :height 48
                                 :parent-id board-id :frame-id board-id})
        root   (cts/setup-shape {:id uuid/zero :type :frame :name "Root"
                                 :x 0 :y 0 :width 1 :height 1
                                 :parent-id uuid/zero :frame-id uuid/zero})]
    {uuid/zero (assoc root :shapes [board-id])
     board-id  (assoc board :shapes [button-id])
     button-id button}))

(t/deftest shape-at-position-prefers-the-layer-over-its-board
  (t/is (= button-id (:id (dwtk/shape-at-position (objects) {:x 50 :y 610}))))
  (t/is (= board-id (:id (dwtk/shape-at-position (objects) {:x 50 :y 50}))))
  (t/is (nil? (dwtk/shape-at-position (objects) {:x 900 :y 900}))))

(t/deftest anchor-position-falls-back-to-the-board-of-a-lost-layer
  (let [objs (objects)]
    (t/is (= {:x 320 :y 600}
             (dwtk/anchor-position objs {:shape-id button-id :frame-id board-id})))
    (t/is (= {:x 400 :y 0}
             (dwtk/anchor-position (dissoc objs button-id)
                                   {:shape-id button-id :frame-id board-id})))
    (t/is (nil? (dwtk/anchor-position objs {:shape-id nil :frame-id uuid/zero})))))

(t/deftest task-predicates
  (t/is (dwtk/to-do? {:status "reopened"}))
  (t/is (dwtk/to-verify? {:status "done"}))
  (t/is (dwtk/closed? {:status "wont-do"}))
  (t/is (dwtk/history? {:status "verified" :merge-id (uuid/next)}))
  (t/is (not (dwtk/history? {:status "open" :merge-id (uuid/next)}))))

(t/deftest tasks-for-counts-the-open-work-of-a-person
  (let [me (uuid/next)]
    (t/is (= 1 (count (dwtk/tasks-for [{:status "open" :assignee-id me}
                                       {:status "done" :assignee-id me}
                                       {:status "open" :assignee-id (uuid/next)}]
                                      me))))))

(t/deftest sort-tasks-puts-open-work-first
  (t/is (= ["open" "done" "verified"]
           (mapv :status (dwtk/sort-tasks [{:status "verified" :created-at 1}
                                           {:status "done" :created-at 2}
                                           {:status "open" :created-at 3}])))))
