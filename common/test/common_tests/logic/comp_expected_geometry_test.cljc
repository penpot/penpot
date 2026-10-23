;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-expected-geometry-test
  "A copy whose root (or another shape) keeps a geometry of its own, and the
   children of that shape: a transform of the shape leaves them untouched,
   and sync gives them their main's geometry placed against it, resized
   through their constraints (decision (f) of the component contract)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.placement :as clp]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- component-with-child
  "A 100x100 main board with a 20x20 rect at (70, 10) of it, constrained
   `constraints-h`, and a copy of it at (300, 0)."
  [constraints-h]
  (-> (thf/sample-file :file1)
      (tho/add-frame :main-root {:x 0 :y 0 :width 100 :height 100})
      (tho/add-rect :main-child {:parent-label :main-root :x 70 :y 10 :width 20 :height 20
                                 :constraints-h constraints-h :constraints-v :top})
      (thc/make-component :component1 :main-root)
      (thc/instantiate-component :component1 :copy-root :children-labels [:copy-child])))

(defn- resize
  "Resize the shape `label` to `width` x `height`, as the editor does: the
   modifiers propagate to its children through their constraints, and the
   placement step decides what the transform touches. With `scale-content`,
   strokes, radii and the like scale by it too."
  [file label width height & {:keys [scale-content]}]
  (let [page       (thf/current-page file)
        objects    (:objects page)
        shape      (ths/get-shape file label)
        modifiers  (cond-> (ctm/change-size shape width height)
                     (some? scale-content) (ctm/scale-content scale-content))
        modif-tree (gm/set-objects-modifiers {(:id shape) {:modifiers modifiers}}
                                             objects)
        ids        (keys modif-tree)
        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               ids
                                               (clp/modifiers-transform modif-tree)
                                               objects
                                               {:ignore-tree (clp/calculate-ignore-tree objects ids
                                                                                        (clp/modifiers-transform modif-tree)
                                                                                        #{(:id shape)})
                                                :attrs clp/transform-attrs})]
    (thf/apply-changes file changes)))

(defn- edit-and-sync
  "Change the fill of the main child and sync the copy."
  [file]
  (let [page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id :main-child)}
                                            #(assoc % :fills (ths/sample-fills-color :fill-color "#ff0000"))
                                            (:objects page)
                                            {})
        file    (thf/apply-changes file changes)]
    (thf/apply-changes file (cll/generate-sync-file-changes (pcb/empty-changes) nil :components (:id file)
                                                            (thi/id :component1) (:id file)
                                                            {(:id file) file} (:id file)))))

(defn- child-box
  "The position of the copy child relative to its root, and its size."
  [file]
  (let [root  (ths/get-shape file :copy-root)
        child (ths/get-shape file :copy-child)]
    (mapv #(mth/round (double %))
          [(- (-> child :selrect :x) (-> root :selrect :x))
           (- (-> child :selrect :y) (-> root :selrect :y))
           (-> child :selrect :width)
           (-> child :selrect :height)])))

(t/deftest a-resized-copy-root-keeps-its-children-placed-through-their-constraints
  (doseq [[constraints-h box] [[:left [70 10 20 20]]
                               [:right [170 10 20 20]]
                               [:center [120 10 20 20]]
                               [:scale [140 10 40 20]]
                               [:leftright [70 10 120 20]]]]
    (t/testing (name constraints-h)
      (let [resized (resize (component-with-child constraints-h) :copy-root 200 100)
            synced  (edit-and-sync resized)]
        (t/is (= box (child-box resized)) "the resize places the child")
        (t/is (contains? (:touched (ths/get-shape resized :copy-root)) :geometry-group))
        (t/is (nil? (:touched (ths/get-shape resized :copy-child))) "the child is not touched")
        (t/is (= box (child-box synced)) "the sync keeps it there")
        (t/is (= "#ff0000" (-> (ths/get-shape synced :copy-child) :fills first :fill-color)))))))

(t/deftest a-copy-root-resized-with-its-content-touches-only-the-root
  (let [file    (-> (component-with-child :scale)
                    (ths/update-shape :main-child :strokes [{:stroke-color "#000000" :stroke-opacity 1
                                                             :stroke-width 2 :stroke-alignment :center
                                                             :stroke-style :solid}])
                    (thc/instantiate-component :component1 :copy2-root :children-labels [:copy2-child]))
        resized (resize file :copy2-root 200 200 :scale-content 2)
        child   (ths/get-shape resized :copy2-child)
        synced  (edit-and-sync resized)]
    (t/is (mth/close? 4 (-> child :strokes first :stroke-width)))
    (t/is (nil? (:touched child)))
    (t/is (mth/close? 4 (-> (ths/get-shape synced :copy2-child) :strokes first :stroke-width)))
    (t/is (nil? (:touched (ths/get-shape synced :copy2-child))))))

(t/deftest content-scale-survives-repeated-sync-and-reset-undo
  (let [file          (-> (component-with-child :scale)
                          (ths/update-shape :main-child :r1 5)
                          (thc/instantiate-component :component1 :copy2-root :children-labels [:copy2-child]))
        scaled        (-> file
                          (resize :copy2-root 200 200 :scale-content 2)
                          (resize :copy2-root 300 300 :scale-content 1.5))
        synced        (edit-and-sync scaled)
        changed       (-> synced
                          (ths/update-shape :main-child :r1 7)
                          (edit-and-sync))
        reset-changes (cll/generate-reset-component
                       (pcb/empty-changes) changed {(:id changed) changed}
                       (ctn/make-container (thf/current-page changed) :page) (thi/id :copy2-root))
        reset         (thf/apply-changes changed reset-changes)
        undone        (thf/apply-changes reset {:redo-changes (:undo-changes reset-changes)})]
    (t/is (mth/close? 3 (:content-scale (ths/get-shape changed :copy2-root))))
    (t/is (mth/close? 21 (:r1 (ths/get-shape changed :copy2-child))))
    (t/is (nil? (:touched (ths/get-shape changed :copy2-child))))
    (t/is (mth/close? 7 (:r1 (ths/get-shape reset :copy2-child))))
    (t/is (nil? (:content-scale (ths/get-shape reset :copy2-root))))
    (t/is (= (thn/normalize-file changed) (thn/normalize-file undone)))))


(t/deftest rotating-a-copy-with-text-in-a-detached-child-is-already-synced
  (let [setup (fn []
                (tm/make-situation
                 (-> (thf/sample-file :file1)
                     (thtx/add-text-component-with-copy [[["Hello world"]]])
                     (ths/update-shape :main-child :grow-type :auto-height)
                     (ths/update-shape :copy-child :grow-type :auto-height)
                     (ths/update-shape :copy-child :touched nil)
                     (tho/add-simple-component :outer :outer-root :outer-child
                                               :root-params {:width 100 :height 100})
                     (thc/instantiate-component :outer :outer-copy))))
        situation (r/run-variant
                   {:setup setup
                    :operation (tm/in-sequence [(n/nest-copy :copy-root :outer-root)
                                                (n/detach :copy-root)
                                                (n/rotate :outer-copy 45)])})
        file (tm/file situation)
        text (first (filter #(= (thi/id :copy-child) (:shape-ref %))
                            (vals (:objects (thf/current-page file)))))
        full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))
        synced (ths/get-shape-by-id full (:id text))]
    (t/is (some? text))
    (t/is (gsh/close-attrs? :points (:points text) (:points synced) 0.01))
    (t/is (mth/close? (:x text) (:x synced) 0.01))
    (t/is (mth/close? (:y text) (:y synced) 0.01))))
