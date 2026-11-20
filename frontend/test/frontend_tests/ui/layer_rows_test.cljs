;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.layer-rows-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.sidebar.layer-rows :as lr]
   [cljs.test :as t :include-macros true]))

(def ^:private board-id (uuid/custom 0 1))
(def ^:private rect-id (uuid/custom 0 2))
(def ^:private child-a-id (uuid/custom 0 3))
(def ^:private child-b-id (uuid/custom 0 4))

(def ^:private objects
  {uuid/zero  {:id uuid/zero :shapes [board-id rect-id]}
   board-id   {:id board-id :parent-id uuid/zero :type :frame
               :shapes [child-a-id child-b-id]}
   rect-id    {:id rect-id :parent-id uuid/zero :type :rect}
   child-a-id {:id child-a-id :parent-id board-id :type :rect}
   child-b-id {:id child-b-id :parent-id board-id :type :rect}})

(defn- build
  ([expanded] (build (js/Map.) objects expanded))
  ([cache objects expanded] (lr/build-tree cache objects expanded)))

(defn- row-ids
  [root start end]
  (mapv lr/get-id (lr/get-rows root start end)))

(t/deftest collapsed-tree-shows-root-shapes-last-first
  (let [[root _] (build {})]
    (t/is (= 2 (lr/get-size root)))
    (t/is (= [rect-id board-id] (row-ids root 0 10)))))

(t/deftest expanded-tree-shows-children-after-parent
  (let [[root _] (build {board-id true})
        rows     (lr/get-rows root 0 10)]
    (t/is (= 4 (lr/get-size root)))
    (t/is (= [rect-id board-id child-b-id child-a-id] (mapv lr/get-id rows)))
    (t/is (= [-1 -1 0 0] (mapv lr/get-depth rows)))
    (t/is (= [1 0 1 0] (mapv lr/get-position rows)))))

(t/deftest get-rows-returns-only-the-window
  (let [[root _] (build {board-id true})]
    (t/is (= [board-id child-b-id] (row-ids root 1 3)))
    (t/is (= [child-a-id] (row-ids root 3 10)))
    (t/is (= [] (row-ids root 4 10)))))

(t/deftest get-index-finds-shown-rows-only
  (let [[open _]   (build {board-id true})
        [closed _] (build {})]
    (t/is (= 3 (lr/get-index open objects child-a-id)))
    (t/is (= 1 (lr/get-index open objects board-id)))
    (t/is (nil? (lr/get-index closed objects child-a-id)))))

(t/deftest get-top-row-returns-root-row-holding-index
  (let [[root _]      (build {board-id true})
        [row offset] (lr/get-top-row root 3)]
    (t/is (= board-id (lr/get-id row)))
    (t/is (= 1 offset))))

(t/deftest unchanged-rows-are-reused
  (let [[root cache]   (build {board-id true})
        objects'       (assoc-in objects [rect-id :name] "renamed")
        [root' _]      (build cache objects' {board-id true})
        board-row      #(first (lr/get-rows % 1 2))
        rect-row       #(first (lr/get-rows % 0 1))]
    (t/is (identical? (board-row root) (board-row root')))
    (t/is (not (identical? (rect-row root) (rect-row root'))))))

(t/deftest changed-child-rebuilds-its-parent
  (let [[root cache] (build {board-id true})
        objects'     (assoc-in objects [child-a-id :name] "renamed")
        [root' _]    (build cache objects' {board-id true})
        board-row    #(first (lr/get-rows % 1 2))]
    (t/is (not (identical? (board-row root) (board-row root'))))
    (t/is (= 4 (lr/get-size root')))))

(t/deftest copy-parent-flag-reaches-descendants
  (let [objects  (assoc-in objects [board-id :shape-ref] (uuid/custom 0 9))
        [root _] (build (js/Map.) objects {board-id true})
        rows     (lr/get-rows root 0 10)]
    (t/is (= [false false true true] (mapv lr/copy-parent? rows)))))
