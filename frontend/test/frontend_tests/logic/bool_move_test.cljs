;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.bool-move-test
  "Moving shapes must only recalculate booleans whose children moved
  relative to them, and leave every boolean equal to a fresh calculation."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.path :as path]
   [app.common.types.shape-tree :as ctst]
   [app.main.data.workspace.modifiers :as dwm]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(def ^:private bool-calculations (atom 0))

(defn- counting-calc-bool-content
  [shape objects]
  (swap! bool-calculations inc)
  (path/calc-bool-content shape objects))

(def ^:private original-calculate-bool wasm.api/calculate-bool)

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (thw/setup-wasm-mocks!)
             (reset! bool-calculations 0)
             (set! wasm.api/calculate-bool counting-calc-bool-content)
             (set! path/wasm:calc-bool-content counting-calc-bool-content))
   :after  (fn []
             (set! wasm.api/calculate-bool original-calculate-bool)
             (set! path/wasm:calc-bool-content nil)
             (thw/teardown-wasm-mocks!))})

(defn- file-with-bool-in-board
  []
  (let [file (-> (cthf/sample-file :file1)
                 (ctho/add-frame :board :x 0 :y 0 :width 400 :height 400)
                 (cths/add-sample-shape :bool {:type :bool
                                               :bool-type :union
                                               :parent-label :board})
                 (ctho/add-rect :rect1 :x 10 :y 10 :width 100 :height 100 :parent-label :bool)
                 (ctho/add-rect :rect2 :x 60 :y 60 :width 100 :height 100 :parent-label :bool))
        page  (cthf/current-page file)
        bool  (path/update-bool-shape (cths/get-shape file :bool) (:objects page))]
    (update file :data ctpl/update-page (:id page) #(ctst/set-shape % bool))))

(defn- disable-pixel-grid
  []
  (fn [state]
    (update state :workspace-layout disj :snap-pixel-grid)))

(defn- run-move
  [done labels delta expected-calculations & {:keys [pixel-grid?] :or {pixel-grid? true}}]
  (let [file       (file-with-bool-in-board)
        store      (ths/setup-store file)
        ids        (map #(:id (cths/get-shape file %)) labels)
        modif-tree (dwm/create-modif-tree ids (ctm/move-modifiers delta))
        events     (cond->> [(dwm/apply-wasm-modifiers modif-tree)]
                     (not pixel-grid?) (cons (disable-pixel-grid)))]
    (reset! bool-calculations 0)
    (ths/run-store
     store done events
     (fn [new-state]
       (let [calculations @bool-calculations
             file'        (ths/get-file-from-state new-state)
             objects'     (:objects (cthf/current-page file'))
             after        (cths/get-shape file' :bool)
             fresh        (path/update-bool-shape after objects')]
         (t/is (= expected-calculations calculations))
         (t/is (= (:selrect fresh) (:selrect after)))
         (t/is (= (vec (:content fresh)) (vec (:content after)))))))))

(t/deftest moving-a-board-does-not-recalculate-the-booleans-inside
  (t/async
    done
    (run-move done [:board :bool :rect1 :rect2] (gpt/point 10 20) 0)))

(t/deftest moving-a-boolean-child-recalculates-the-boolean
  (t/async
    done
    (run-move done [:rect1] (gpt/point 10 20) 1)))

(t/deftest moving-a-board-without-pixel-grid-does-not-recalculate-its-booleans
  (t/async
    done
    (run-move done [:board] (gpt/point 10.5 20.25) 0 :pixel-grid? false)))
