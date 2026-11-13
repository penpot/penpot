;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.drag-on-save-failure-test
  "Tests for drags and drawings in progress when saves start failing and
  the workspace locks."
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.changes :as dch]
   [app.main.data.persistence.lock :as plk]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.drawing.curve :as-alias dwdc]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- shape-rect
  [store]
  (-> (ths/get-file-from-state @store)
      (cths/get-shape :rect1)
      :points
      (grc/points->rect)))

(defn- ^:async drag
  "Drags rect1 with the gesture `start` makes, from (120, 220) to (160, 260),
  then emits `stop`. Resolves to the commits made and the store."
  [start stop]
  (let [file    (-> (cthf/sample-file :file1)
                    (ctho/add-rect :rect1 :x 100 :y 200 :width 50 :height 50))
        rect    (cths/get-shape file :rect1)
        store   (ths/setup-store file {:renderer :svg})
        commits (atom 0)
        sub     (->> (ptk/input-stream store)
                     (rx/filter dch/commit?)
                     (rx/subs! (fn [_] (swap! commits inc))))]
    (await
     (mock/with-mocks*
       ;; Called with seven arguments, more than a stub forwards
       {snap/closest-snap-move     (fn [& _] (rx/of (gpt/point 0 0)))
        snap/closest-snap-point    (fn [& _] (rx/of (gpt/point 0 0)))
        ;; Moves a DOM node the tests lack
        dwt/set-ghost-displacement (mock/stub (fn [_] (ptk/data-event ::ghost-moved)))}
       (try
         (rx/push! ms/mouse-position (gpt/point 120 220))
         (ptk/emit! store
                    #(assoc-in % [:workspace-local :selected] (d/ordered-set (:id rect)))
                    (start rect))
         (rx/push! ms/mouse-position (gpt/point 160 260))
         (await (async/wait-for-store store #(seq (:workspace-modifiers @store)) "the drag shows a preview"))
         (ptk/emit! store stop)
         (await (async/settle))
         (finally
           (rx/push! ms/mouse-position nil)
           (rx/dispose! sub)))))
    {:commits @commits :store store}))

(def ^:private save-failure
  "Saves fail for good: the workspace locks."
  (ptk/reify ::save-failure
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :persistence {:status :error}))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (plk/sync-failure-lock)))))

(defn- move
  [rect]
  (dwt/start-move (gpt/point 120 220) [(:id rect)]))

(defn- rotate
  [rect]
  (dwt/start-rotate [rect]))

;; Scenario: the user drags a shape and stops it with Escape. Proves: the
;; drag commits where it stopped (the case the lock must not follow).
(t/deftest ^:async an-interrupted-move-commits-its-position
  (let [{:keys [commits store]} (await (drag move :interrupt))]
    (t/is (pos? commits))
    (t/is (= 140 (:x (shape-rect store))))))

;; Scenario: saves fail while the user drags a shape, or rotates it. Proves:
;; the lock cancels the gesture, the shape goes back to where it was and
;; nothing is committed behind the failed save.
(defn- check-cancelled
  [{:keys [commits store]}]
  (t/is (some? (get-in @store [:workspace-global :persistence-lock])) "the workspace is locked")
  (t/is (= 0 commits) "nothing is committed")
  (t/is (empty? (:workspace-modifiers @store)) "the preview is dropped")
  (t/is (nil? (get-in @store [:workspace-local :transform])))
  (t/is (= (grc/make-rect 100 200 50 50) (shape-rect store))
        "the shape stays where it was"))

(t/deftest ^:async a-save-failure-cancels-a-move-in-progress
  (check-cancelled (await (drag move save-failure))))

(t/deftest ^:async a-save-failure-cancels-a-rotation-in-progress
  (check-cancelled (await (drag rotate save-failure))))

;; -- Drawing ---------------------------------------------------------

(defn- drawn-shapes
  "Shapes of the current page other than the root."
  [store]
  (let [page-id (:current-page-id @store)
        objects (get-in (ths/get-file-from-state @store) [:data :pages-index page-id :objects])]
    (remove #(= (:id %) (:parent-id %)) (vals objects))))

(defn- ^:async draw
  "Starts drawing with `tool` on an empty page and moves the pointer with
  `move` (gets the store, resolves once the drawing shows), then emits
  `stop` and waits for the drawing to end. Resolves to the commits made
  and the store."
  [tool move stop]
  (let [file    (cthf/sample-file :file1)
        store   (ths/setup-store file {:renderer :svg})
        commits (atom 0)
        sub     (->> (ptk/input-stream store)
                     (rx/filter dch/commit?)
                     (rx/subs! (fn [_] (swap! commits inc))))]
    (await
     (mock/with-mocks*
       ;; Called with seven arguments, more than a stub forwards
       {snap/closest-snap-point (fn [& _] (rx/of (gpt/point 0 0)))}
       (try
         (rx/push! ms/mouse-position (gpt/point 100 100))
         (ptk/emit! store (dwd/handle-drawing tool))
         (await (move store))
         (ptk/emit! store stop)
         ;; A box drawing ends 100 ms after its stop
         (await (async/wait-for-store store #(nil? (get-in @store [:workspace-drawing :object]))
                                      "the drawing ends"))
         (await (async/settle))
         (finally
           (rx/push! ms/mouse-position nil)
           (rx/dispose! sub)))))
    {:commits @commits :store store}))

(defn- ^:async drag-box
  [store]
  (rx/push! ms/mouse-position (gpt/point 160 180))
  (await (async/wait-for-store store #(< 50 (get-in @store [:workspace-drawing :object :width] 0))
                               "the box follows the pointer")))

(defn- ^:async drag-curve
  [store]
  (doseq [x [110 130 150 170]]
    (rx/push! ms/mouse-position (gpt/point x (+ x 20)))
    (await (async/wait-for-store store #(some (fn [p] (= x (:x p)))
                                              (get-in @store [:workspace-drawing :object ::dwdc/points]))
                                 "the curve takes the point"))))

(defn- ^:async click-path
  "Stands for the pen clicks: the drawn path gets two nodes."
  [store]
  (await (async/wait-for-store store #(some? (get-in @store [:workspace-drawing :object])) "the pen starts"))
  (ptk/emit! store #(assoc-in % [:workspace-drawing :object :content]
                              (path/content [{:command :move-to :params {:x 100 :y 100}}
                                             {:command :line-to :params {:x 180 :y 160}}]))))

(defn- check-drawn
  [{:keys [commits store]}]
  (t/is (pos? commits))
  (t/is (= 1 (count (drawn-shapes store))) "the shape is added"))

(defn- check-not-drawn
  [{:keys [commits store]}]
  (t/is (plk/locked? @store) "the workspace is locked")
  (t/is (= 0 commits) "nothing is committed")
  (t/is (empty? (drawn-shapes store)) "no shape is added")
  (t/is (nil? (get-in @store [:workspace-drawing :object])) "no drawing is left on screen"))

;; Scenario: the user draws a rectangle, a curve or a path and stops it
;; with Escape. Proves: the shape is added (the case the lock must not
;; follow).
(t/deftest ^:async an-interrupted-rectangle-is-added
  (check-drawn (await (draw :rect drag-box :interrupt))))

(t/deftest ^:async an-interrupted-curve-is-added
  (check-drawn (await (draw :curve drag-curve :interrupt))))

(t/deftest ^:async an-interrupted-path-is-added
  (check-drawn (await (draw :path click-path :interrupt))))

;; Scenario: saves fail while the user draws a rectangle, a curve or a
;; path. Proves: the lock drops the drawing: no shape is added, nothing is
;; committed behind the failed save and no half-drawn shape stays.
(t/deftest ^:async a-save-failure-drops-a-rectangle-in-progress
  (check-not-drawn (await (draw :rect drag-box save-failure))))

(t/deftest ^:async a-save-failure-drops-a-curve-in-progress
  (check-not-drawn (await (draw :curve drag-curve save-failure))))

(t/deftest ^:async a-save-failure-drops-a-path-in-progress
  (check-not-drawn (await (draw :path click-path save-failure))))
