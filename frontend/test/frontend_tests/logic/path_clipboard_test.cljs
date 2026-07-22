;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL


(ns frontend-tests.logic.path-clipboard-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.workspace.path.clipboard :as path.clipboard]
   [app.main.data.workspace.path.edition :as path.edition]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.streams :as ms]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.set :as set]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.logic.path-test-helpers :as pth]
   [potok.v2.core :as ptk]))

(t/deftest cut-selected-nodes-copies-then-removes
  ;; Cut emits copy followed by the regular delete action.
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content {:nodes #{1} :segments #{} :handlers #{}})
        events   (atom [])
        _        (->> (ptk/watch (path.clipboard/cut-selected-nodes) state nil)
                      (rx/subs! #(swap! events conj %)))
        del      (atom [])
        _        (->> (ptk/watch (second @events) state nil)
                      (rx/subs! #(swap! del conj %)))
        state'   (ptk/update (first @del) state)
        nodes    (count (path/get-points (get-in state' [:workspace-drawing :object :content])))]
    ;; two events emitted (copy, then the removal)
    (t/is (= 2 (count @events)))
    ;; The removal leaves fewer than three nodes.
    (t/is (< nodes 3))))

(t/deftest duplicate-selection-content-copies-nodes-and-segments
  (let [content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :line-to :params {:x 20 :y 0}}])]
    ;; A lone node copies its incoming segment.
    (let [{:keys [sub selected]}
          (path.helpers/duplicate-selection-content
           content {:nodes #{2} :segments #{}} (gpt/point 10 10))]
      (t/is (= [[:move-to {:x 10 :y 0}]
                [:line-to {:x 30 :y 10}]]
               (mapv (juxt :command :params) sub)))
      ;; only the new endpoint (index 1) is selected, not the attach point
      (t/is (= #{1} selected)))
    ;; Interior-node copies meet at one offset node.
    (let [{:keys [sub selected]}
          (path.helpers/duplicate-selection-content
           content {:nodes #{1} :segments #{}} (gpt/point 10 10))]
      (t/is (= [[:move-to {:x 0 :y 0}]
                [:line-to {:x 20 :y 10}]
                [:move-to {:x 20 :y 0}]
                [:line-to {:x 20 :y 10}]]
               (mapv (juxt :command :params) sub)))
      (t/is (= #{1 3} selected)))
    ;; Segment copies select both offset endpoints.
    (let [{:keys [sub selected]}
          (path.helpers/duplicate-selection-content
           content {:nodes #{} :segments #{1}} (gpt/point 10 10))]
      (t/is (= [[:move-to {:x 10 :y 10}]
                [:line-to {:x 20 :y 10}]]
               (mapv (juxt :command :params) sub)))
      (t/is (= #{0 1} selected)))))

(t/deftest duplicate-offset-stays-constant-in-screen-pixels
  (t/is (= (gpt/point 10 10) (path.edition/duplicate-offset 1)))
  (t/is (= (gpt/point 2.5 2.5) (path.edition/duplicate-offset 4)))
  (t/is (= (gpt/point 20 20) (path.edition/duplicate-offset 0.5))))

(t/deftest splice-duplicated-appends-copies-and-selects-only-new-nodes
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}])
        result   (path.helpers/duplicate-selection-content
                  content {:nodes #{2} :segments #{}} (gpt/point 10 10))
        state    (pth/selectable-path-state id content {:nodes #{2} :segments #{} :handlers #{}})
        state'   (ptk/update (path.edition/splice-duplicated result) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; Append the copy as a new subpath.
    (t/is (= 5 (count content')))
    ;; Select only the new endpoint.
    (t/is (= #{4}
             (get-in state' [:workspace-local :edit-path id :selection :nodes])))))

(t/deftest pasting-path-content-splices-and-selects-new-nodes
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content
                                            {:nodes #{1}
                                             :segments #{}
                                             :handlers #{}})
        sub      [{:command :move-to :params {:x 30 :y 30}}
                  {:command :line-to :params {:x 40 :y 30}}]
        ;; Center the pasted fragment at the pointer.
        _        (rx/push! ms/mouse-position (gpt/point 100 100))
        state'   (ptk/update (path.clipboard/paste-content sub) state)
        content' (vec (get-in state' [:workspace-drawing :object :content]))
        pasted   (subvec content' 3)]
    (t/is (= (vec content) (subvec content' 0 3)))
    (t/is (= {:x 95 :y 100} (select-keys (:params (first pasted)) [:x :y])))
    (t/is (= {:x 105 :y 100} (select-keys (:params (second pasted)) [:x :y])))
    (t/is (= {:nodes #{3 4}
              :segments #{}
              :handlers #{}}
             (get-in state' [:workspace-local :edit-path id :selection])))))

(t/deftest pasting-over-identical-nodes-offsets-the-fragment
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content path.helpers/empty-selection)
        ;; Same coordinates as the existing segment between nodes 0 and 1
        sub      [{:command :move-to :params {:x 0 :y 0}}
                  {:command :curve-to :params {:c1x 2 :c1y 0
                                               :c2x 8 :c2y 0
                                               :x 10 :y 0}}]
        ;; Overlapping pasted nodes receive the collision offset.
        _        (rx/push! ms/mouse-position (gpt/point 5 0))
        state'   (ptk/update (path.clipboard/paste-content sub) state)
        content' (vec (get-in state' [:workspace-drawing :object :content]))
        pasted   (subvec content' 3)]
    ;; Pasted nodes do not overlap existing nodes.
    (t/is (= {:x 10 :y 10} (select-keys (:params (first pasted)) [:x :y])))
    (t/is (= {:x 20 :y 10} (select-keys (:params (second pasted)) [:x :y])))))

(t/deftest pasting-finds-a-free-offset-after-more-than-one-hundred-collisions
  (let [id       (random-uuid)
        content  (path/content
                  (into [{:command :move-to :params {:x 0 :y 0}}]
                        (map (fn [step]
                               {:command :line-to
                                :params {:x (* step 10) :y (* step 10)}}))
                        (range 1 101)))
        state    (pth/selectable-path-state id content path.helpers/empty-selection)
        sub      (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 1 :y 0}}])
        _        (rx/push! ms/mouse-position nil)
        state'   (ptk/update (path.clipboard/paste-content sub) state)
        content' (get-in state' [:workspace-drawing :object :content])
        pasted   (take-last 2 content')]
    (t/is (= [{:x 1010 :y 1010} {:x 1011 :y 1010}]
             (mapv #(select-keys (:params %) [:x :y]) pasted)))
    (t/is (empty? (set/intersection
                   (set (path/get-points content))
                   (set (path/get-points pasted)))))))

(defn- page-paths
  [state]
  (->> (:objects (cthf/current-page (ths/get-file-from-state state)))
       vals
       (filter #(= :path (:type %)))))

(t/deftest pasting-path-nodes-outside-editor-creates-a-new-path-shape
  (t/async
    done
    (let [file    (pth/setup-rect-file)
          store   (ths/setup-store file)
          content (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 40 :y 0}}
                                 {:command :line-to :params {:x 40 :y 40}}])
          target  (gpt/point 300 300)]
      ;; a new path shape is centred at the pointer position
      (rx/push! ms/mouse-position target)
      (ths/run-store
       store done
       [(path.clipboard/paste-nodes-as-shape content)]
       (fn [new-state]
         (let [paths  (page-paths new-state)
               pasted (first paths)]
           (t/is (= 1 (count paths)))
           (when pasted
             (t/is (= target (grc/rect->center (:selrect pasted))))
             (t/is (contains? (get-in new-state [:workspace-local :selected]) (:id pasted))))))))))

(t/deftest pasting-path-nodes-while-editing-does-not-create-a-shape
  (t/async
    done
    (let [file    (pth/setup-rect-file)
          id      (:id (cths/get-shape file :rect1))
          store   (ths/setup-store file)
          content (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 40 :y 0}}])
          ;; Outside-editor paste does nothing during path editing.
          events  (conj (pth/start-path-edition-events id)
                        (path.clipboard/paste-nodes-as-shape content))]
      (rx/push! ms/mouse-position (gpt/point 300 300))
      (ths/run-store
       store done events
       (fn [new-state]
         (t/is (empty? (page-paths new-state))))))))
