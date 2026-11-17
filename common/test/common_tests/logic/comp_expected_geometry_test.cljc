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
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.placement :as clp]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest updating-a-turned-copy-settles-swapped-placement-in-the-main
  (doseq [remote? [false true]
          angle [45 90]]
    (let [file      (-> (thf/sample-file :file)
                        (tho/add-simple-component :a :main-a :rect-a
                                                  :root-params {:width 100 :height 100})
                        (tho/add-simple-component :other :main-other :rect-other
                                                  :root-params {:width 100 :height 100})
                        (tho/add-frame :main-b {:width 200 :height 200})
                        (thc/instantiate-component :a :b-a :parent-label :main-b)
                        (thc/make-component :b :main-b)
                        (tho/add-frame :main-c {:width 300 :height 300})
                        (thc/instantiate-component :b :c-b :parent-label :main-c
                                                   :children-labels [:c-a])
                        (thc/make-component :c :main-c))
          prepared  (-> (tm/make-situation file)
                        (r/step (n/change-height :b-a 80)) r/settle
                        (r/step (n/swap-shape :c-a :other)) r/settle)
          library   (tm/file prepared)
          initial   (if remote?
                      (-> (tm/make-situation
                           (thc/instantiate-component (thf/sample-file :local) :c :copy-c
                                                      :library library :children-labels [:copy-b :copy-a]))
                          (tm/with-aux-files {(:id library) library}))
                      (tm/with-file prepared
                        (thc/instantiate-component library :c :copy-c
                                                   :children-labels [:copy-b :copy-a])))
          before    (-> initial (r/step (n/rotate :copy-c angle)) r/settle)
          updated   (-> before (r/step (n/update-main :copy-c)) r/settle)
          main-file (if remote? (get (tm/aux-files updated) (:id library)) (tm/file updated))
          head      (ths/get-shape main-file :c-a)
          full      (:situation (cf/sync-world updated (keys (get-in main-file [:data :components]))))
          full-main (if remote? (get (tm/aux-files full) (:id library)) (tm/file full))
          full-head (ths/get-shape-by-id full-main (:id head))]
      (t/is (mth/close? angle (:rotation head) 0.001))
      (t/is (= #{:swap-group} (:touched head)))
      (t/is (gsh/close-attrs? :points (:points full-head) (:points head) 0.001))
      (t/is (gsh/close-attrs? :points
                              (:points (tm/resolve-shape full :copy-a))
                              (:points (tm/resolve-shape updated :copy-a)) 0.001))
      (t/is (= (into {} (map (fn [[id f]] [id (thn/normalize-file f)])) (cf/all-files before))
               (into {} (map (fn [[id f]] [id (thn/normalize-file f)]))
                     (cf/all-files (r/step updated (n/undo)))))))))

(t/deftest resetting-a-turned-copy-places-replacement-children-in-the-main-frame
  (doseq [angle [45 90]]
    (let [file     (-> (thf/sample-file :file)
                       (tho/add-simple-component :a :main-a :rect-a
                                                 :root-params {:width 100 :height 100}
                                                 :child-params {:width 20 :height 20 :x 10 :y 10})
                       (tho/add-simple-component :other :main-other :rect-other
                                                 :root-params {:width 100 :height 100}
                                                 :child-params {:width 20 :height 20 :x 10 :y 10})
                       (tho/add-frame :main-b {:width 200 :height 200})
                       (thc/instantiate-component :a :b-a :parent-label :main-b)
                       (thc/make-component :b :main-b)
                       (thc/instantiate-component :b :copy-b :children-labels [:copy-a]))
          turned   (-> (tm/make-situation file)
                       (r/step (n/swap-shape :copy-a :other)) r/settle
                       (r/step (n/rotate :copy-b angle)) r/settle)
          reset    (-> turned (r/step (n/reset :copy-b)) r/settle)
          head     (tm/resolve-shape reset :copy-a)
          child    (tm/shape-by-id reset (first (:shapes head)))
          expected (gsh/move (ths/get-shape file :rect-a)
                             (gpt/subtract (gsh/shape->center head)
                                           (gsh/shape->center (ths/get-shape file :main-a))))
          file     (tm/file reset)
          full     (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
      (t/is (mth/close? 0 (:rotation head) 0.001))
      (t/is (mth/close? 0 (:rotation child) 0.001))
      (t/is (empty? (:touched head)))
      (t/is (empty? (:touched child)))
      (t/is (gsh/close-attrs? :points (:points expected) (:points child) 0.001))
      (t/is (gsh/close-attrs? :points (:points child)
                              (:points (ths/get-shape-by-id full (:id child))) 0.001))
      (t/is (= (thn/normalize-file (tm/file turned))
               (thn/normalize-file (tm/file (r/step reset (n/undo)))))))))

(t/deftest turning-a-parent-settles-a-swapped-head-with-a-different-position-size
  (doseq [angle [45 90]]
    (let [file    (-> (thf/sample-file :file)
                      (tho/add-simple-component :a :main-a :rect-a
                                                :root-params {:width 100 :height 100})
                      (tho/add-simple-component :other :main-other :rect-other
                                                :root-params {:width 100 :height 100})
                      (tho/add-frame :main-b {:width 200 :height 200})
                      (thc/instantiate-component :a :b-a :parent-label :main-b)
                      (thc/make-component :b :main-b)
                      (tho/add-frame :main-c {:width 300 :height 300})
                      (thc/instantiate-component :b :c-b :parent-label :main-c)
                      (thc/make-component :c :main-c)
                      (thc/instantiate-component :c :copy-c :children-labels [:copy-b :copy-a]))
          before  (-> (tm/make-situation file)
                      (r/step (n/swap-shape :copy-a :other)) r/settle
                      (r/step (n/change-height :b-a 80)) r/settle)
          turned  (-> before (r/step (n/rotate :copy-c angle)) r/settle)
          head    (tm/resolve-shape turned :copy-a)
          child   (tm/shape-by-id turned (first (:shapes head)))
          file    (tm/file turned)
          full    (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
      (t/is (mth/close? 100 (:height head) 0.001))
      (t/is (= #{:swap-group} (:touched head)))
      (t/is (empty? (:touched child)))
      (t/is (gsh/close-attrs? :points (:points head)
                              (:points (ths/get-shape-by-id full (:id head))) 0.001))
      (t/is (gsh/close-attrs? :points (:points child)
                              (:points (ths/get-shape-by-id full (:id child))) 0.001))
      (t/is (= (thn/normalize-file (tm/file before))
               (thn/normalize-file (tm/file (r/step turned (n/undo)))))))))

(t/deftest authored-child-geometry-follows-a-parent-rotation-received-through-sync
  (doseq [swapped? [false true]
          angle [45 135]
          own-angle [0 10]]
    (let [file         (-> (thf/sample-file :file)
                           (tho/add-simple-component :a :main-a :rect-a
                                                     :root-params {:width 100 :height 100})
                           (tho/add-simple-component :other :main-other :rect-other
                                                     :root-params {:width 100 :height 100})
                           (tho/add-frame :main-b {:width 200 :height 200})
                           (thc/instantiate-component :a :b-a :parent-label :main-b)
                           (thc/make-component :b :main-b)
                           (tho/add-frame :main-c {:width 300 :height 300})
                           (thc/instantiate-component :b :c-b :parent-label :main-c)
                           (thc/make-component :c :main-c)
                           (thc/instantiate-component :c :copy-c :children-labels [:copy-b :copy-a]))
          initial      (tm/make-situation file)
          initial      (cond-> initial swapped? (r/step (n/swap-shape :copy-a :other)))
          edited       (-> initial r/settle (r/step (n/change-height :copy-a 80)) r/settle
                           (r/step (n/rotate :copy-a own-angle)) r/settle)
          pending      (r/step edited (n/rotate :c-b angle))
          pending-file (tm/file pending)
          bare-changes (cll/generate-sync-shape-direct
                        (pcb/empty-changes) pending-file {(:id pending-file) pending-file}
                        (ctn/make-container (thf/current-page pending-file) :page)
                        (thi/id :copy-c) false)
          bare-file    (thf/apply-changes pending-file bare-changes)
          turned       (r/settle pending)
          parent       (tm/resolve-shape turned :copy-b)
          child        (tm/resolve-shape turned :copy-a)
          old-parent   (tm/resolve-shape edited :copy-b)
          old-child    (tm/resolve-shape edited :copy-a)
          expected     (-> old-child
                           (gsh/transform-shape (ctm/rotation-modifiers old-child (gsh/shape->center old-parent) angle))
                           (gsh/move (gpt/subtract (gsh/shape->center parent) (gsh/shape->center old-parent))))
          file         (tm/file turned)
          full         (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
      (t/is (mth/close? angle (:rotation parent) 0.001))
      (t/is (mth/close? (+ angle own-angle) (:rotation child) 0.001))
      (t/is (= (boolean (:flip-x parent)) (boolean (:flip-x child))))
      (t/is (= (boolean (:flip-y parent)) (boolean (:flip-y child))))
      (t/is (mth/close? 80 (:height child) 0.001))
      (t/is (gsh/close-attrs? :points (:points expected) (:points child) 0.001))
      (t/is (gsh/close-attrs? :points (:points expected)
                              (:points (ths/get-shape-by-id bare-file (:id child))) 0.001))
      (t/is (mth/close? (+ angle own-angle) (:rotation (ths/get-shape-by-id bare-file (:id child))) 0.001))
      (t/is (= (if swapped? #{:geometry-group :swap-group} #{:geometry-group}) (:touched child)))
      (t/is (gsh/close-attrs? :points (:points child)
                              (:points (ths/get-shape-by-id full (:id child))) 0.001))
      (t/is (= (thn/normalize-file (tm/file edited))
               (thn/normalize-file (tm/file (r/step turned (n/undo)))))))))

(t/deftest authored-child-position-follows-a-parent-move-received-through-sync
  (let [file    (-> (thf/sample-file :file)
                    (tho/add-simple-component :a :main-a :rect-a
                                              :root-params {:width 100 :height 100})
                    (tho/add-frame :main-b {:width 200 :height 200})
                    (thc/instantiate-component :a :b-a :parent-label :main-b)
                    (thc/make-component :b :main-b)
                    (tho/add-frame :main-c {:width 300 :height 300})
                    (thc/instantiate-component :b :c-b :parent-label :main-c)
                    (thc/make-component :c :main-c)
                    (thc/instantiate-component :c :copy-c :children-labels [:copy-b :copy-a]))
        edited  (-> (tm/make-situation file) r/settle (r/step (n/transform-shape :copy-a :move [5 3])) r/settle)
        moved   (-> edited (r/step (n/transform-shape :c-b :move [20 0])) r/settle)
        delta   (fn [label]
                  (gpt/subtract (gsh/shape->center (tm/resolve-shape moved label))
                                (gsh/shape->center (tm/resolve-shape edited label))))]
    (t/is (gpt/close? (gpt/point 20 0) (delta :copy-b)))
    (t/is (gpt/close? (gpt/point 20 0) (delta :copy-a)))
    (t/is (= #{:geometry-group} (:touched (tm/resolve-shape moved :copy-a))))))

(t/deftest a-nested-copy-keeps-its-detached-parents-orientation-on-sync
  (let [detached (-> (setup/nested-component-with-copy)
                     (r/step (n/rotate :copy-root 90)) r/settle
                     (r/step (n/detach :copy-root)) r/settle)
        head (tm/role-shape detached :copy-instance)
        id (first (:shapes head))
        before (tm/shape-by-id detached id)
        synced (-> detached (r/step (n/change-property :main1-child :fills "#ff0000")) r/settle)
        after (tm/shape-by-id synced id)]
    (t/is (= "#ff0000" (-> after :fills first :fill-color)))
    (t/is (gsh/close-attrs? :points (:points before) (:points after) 0.001))
    (t/is (= (:rotation before) (:rotation after)))
    (t/is (nil? (:touched after)))))

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

(t/deftest a-resized-copy-root-places-children-through-their-authored-constraints
  (doseq [[constraints-h box] [[:right [170 10 20 20]]
                               [:scale [140 10 40 20]]
                               [:leftright [70 10 120 20]]]]
    (t/testing (name constraints-h)
      (let [file     (component-with-child :left)
            page     (thf/current-page file)
            authored (thf/apply-changes file
                                        (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                                    #{(thi/id :copy-child)}
                                                                    #(assoc % :constraints-h constraints-h)
                                                                    (:objects page)
                                                                    {}))
            resized  (resize authored :copy-root 200 100)
            synced   (edit-and-sync resized)]
        (t/is (= #{:constraints-group} (:touched (ths/get-shape resized :copy-child))))
        (t/is (= box (child-box resized)) "the resize places the child")
        (t/is (= box (child-box synced)) "the sync keeps it there")
        (t/is (= constraints-h (:constraints-h (ths/get-shape synced :copy-child))))
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
  (doseq [angle [45 135 225]]
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
                                                  (n/rotate :outer-copy angle)])})
          file (tm/file situation)
          text (first (filter #(= (thi/id :copy-child) (:shape-ref %))
                              (vals (:objects (thf/current-page file)))))
          full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))
          synced (ths/get-shape-by-id full (:id text))]
      (t/is (some? text))
      (t/is (gmt/close? (:transform text) (:transform synced)))
      (t/is (gsh/close-attrs? :points (:points text) (:points synced) 0.01))
      (t/is (mth/close? (:x text) (:x synced) 0.01))
      (t/is (mth/close? (:y text) (:y synced) 0.01)))))

(t/deftest nesting-a-copy-settles-its-derived-orientation-before-its-new-main
  (let [initial (-> (setup/nested-component-with-copy)
                    (r/step (n/rotate :main2-root 45)) r/settle
                    (r/step (n/detach :copy-root)) r/settle
                    (as-> s (tm/apply-to (n/create-component "destination" setup/labeled-fill :size 100) s)))
        moved (-> initial
                  (r/step (n/nest-copy :copy-instance (n/main-head-of "destination"))) r/settle)
        file (tm/file moved)
        full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
    (t/is (= (thn/normalize-file file) (thn/normalize-file full)))))

(t/deftest full-sync-keeps-child-flips-after-several-small-root-rotations
  (let [situation (-> (setup/nested-component-with-copy)
                      (r/step (n/rotate :copy-root 45)) r/settle
                      (r/step (n/rotate :copy-root 45)) r/settle
                      (r/step (n/rotate :copy-root 45)) r/settle)
        file (tm/file situation)
        head (tm/role-shape situation :copy-instance)
        child (tm/shape-by-id situation (first (:shapes head)))
        synced (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))
        after (get-in (thf/current-page synced) [:objects (:id child)])]
    (t/is (= (select-keys child [:flip-x :flip-y]) (select-keys after [:flip-x :flip-y])))
    (t/is (gsh/close-attrs? :points (:points child) (:points after) 0.001))))

(t/deftest update-main-keeps-child-flips-when-syncing-through-a-rotated-parent
  (let [updated (-> (setup/nested-component-with-copy)
                    (r/step (n/rotate :copy-root 45)) r/settle
                    (r/step (n/rotate :copy-root 90)) r/settle
                    (r/step (n/update-main :copy-root)) r/settle)
        file (tm/file updated)
        main (tm/resolve-shape updated :main2-nested-head)
        child (tm/shape-by-id updated (first (:shapes main)))
        synced (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
    (doseq [shape [main child]]
      (let [after (ths/get-shape-by-id synced (:id shape))
            flips (fn [shape] (mapv #(boolean (get shape %)) [:flip-x :flip-y]))]
        (t/is (= (flips shape) (flips after)))
        (t/is (gsh/close-attrs? :points (:points shape) (:points after) 0.001))))))

(t/deftest a-mirrored-copy-child-may-store-either-sign-of-its-turn
  (let [situation (-> (setup/nested-component-with-copy)
                      (r/step (n/rotate :main2-nested-head 90)) r/settle
                      (r/step (n/transform-shape :copy-root :flip-x nil)) r/settle)
        file      (tm/file situation)
        page      (thf/current-page file)
        child     (tm/role-shape situation :copy-instance)
        expected  (oracle/expected-geometry file (ctn/make-container page :page)
                                            (cf/libraries situation) child)
        flagged   (fn [shape expected]
                    (try (oracle/check-requested-geometry (:objects page) shape expected) nil
                         (catch #?(:clj Throwable :cljs :default) cause (:attrs (ex-data cause)))))
        unflipped #(assoc % :flip-x false)]
    (t/is (empty? (:touched child)))
    (t/is (:flip-x child))
    ;; the flip stores the turn that decomposes the child's transform
    (t/is (mth/close? 90 (:rotation child)))
    (t/is (mth/close? 90 (:rotation expected)))
    (t/is (nil? (flagged child expected)))
    (t/is (nil? (flagged (assoc child :rotation 270) expected)))
    ;; any rotation the child stores at its expected corners
    (t/is (nil? (flagged (assoc child :rotation 45) expected)))
    (t/is (nil? (flagged (unflipped (assoc child :rotation 270)) (unflipped expected))))))

(t/deftest a-copy-child-turn-that-decomposes-its-own-transform-is-accepted
  ;; Parents with equivalent decompositions can add up to another turn.
  (let [shape   (cts/setup-shape {:type :rect :x 10 :y 10 :width 20 :height 20})
        flagged (fn [shape expected]
                  (try (oracle/check-requested-geometry {(:id shape) shape} shape expected) nil
                       (catch #?(:clj Throwable :cljs :default) cause (:attrs (ex-data cause)))))]
    (t/is (nil? (flagged shape (assoc shape :rotation 270))))
    (t/is (nil? (flagged (assoc shape :rotation 90) (assoc shape :rotation 270))))
    ;; the turn itself is checked through the corners
    (t/is (contains? (flagged (gsh/transform-shape shape (ctm/rotation-modifiers shape (gsh/shape->center shape) 45))
                              shape)
                     :points))
    (t/is (map? (cg/run {:world [2 2 {:variants true :rich-variants true :text true :text-styles true
                                      :layout nil :layout-copies true}]
                         :ops [[:rotate 0 0 0 0] [:switch 1 0 0 0] [:transform 0 4 0 4]
                               [:update-main 0 0 0 0] [:swap 0 0 0 0] [:nest 2 0 0 0]]})))))

(t/deftest both-flags-may-record-a-half-turn-the-rotation-counts
  (let [shape   (cts/setup-shape {:type :rect :x 10 :y 10 :width 20 :height 20})
        turned  (-> (gsh/transform-shape shape (ctm/rotation-modifiers shape (gsh/shape->center shape) 225))
                    (assoc :flip-x true :flip-y true))
        flagged (fn [shape expected]
                  (try (oracle/check-requested-geometry {(:id shape) shape} shape expected) nil
                       (catch #?(:clj Throwable :cljs :default) cause (:attrs (ex-data cause)))))]
    (t/is (mth/close? 225 (:rotation turned)))
    (t/is (nil? (flagged turned (assoc turned :rotation 135))))
    (t/is (nil? (flagged (assoc turned :rotation 90) (assoc turned :rotation 135))))
    (t/is (map? (cg/run {:world [2 2 {:variants true :rich-variants true :text true :text-styles true
                                      :layout nil :layout-copies true}]
                         :ops [[:transform 0 4 0 2] [:transform 3 4 0 3] [:rotate 0 0 2 0]]})))))

(t/deftest a-copy-keeps-the-size-a-plain-boards-constraint-gave-it
  ;; A resize through constraints is derived, never an override, and the
  ;; next sync of the copy's own component keeps it.
  (let [file      (-> (thf/sample-file :file1)
                      (tho/add-simple-component :inner :inner-root :inner-child
                                                :root-params {:width 100 :height 100}
                                                :child-params {:x 30 :y 30 :width 40 :height 40})
                      (tho/add-frame :outer-root {:x 200 :y 0 :width 240 :height 100})
                      (thc/instantiate-component :inner :nested :parent-label :outer-root
                                                 :children-labels [:nested-child])
                      (ths/update-shape :nested :constraints-v :scale)
                      (thc/make-component :outer :outer-root))
        shrunk    (-> (tm/make-situation file) (r/step (n/change-height :outer-root 80)) r/settle)
        resized   (tm/resolve-shape shrunk :nested)
        edited    (-> shrunk (r/step (n/change-property :inner-child :fills "#dc2626")) r/settle)
        after     (tm/resolve-shape edited :nested)]
    (t/is (mth/close? 80 (:height resized)))
    (t/is (not (contains? (:touched resized) :geometry-group)))
    (t/is (= "#dc2626" (get-in (tm/resolve-shape edited :nested-child) [:fills 0 :fill-color])))
    (t/is (mth/close? 80 (:height after)))
    (t/is (mth/close? 100 (:width after)))
    (t/is (not (contains? (:touched after) :geometry-group)))))

(t/deftest a-constraint-a-copy-sets-after-a-resize-moves-nothing-at-sync
  ;; A constraint the copy sets on a child applies to later resizes, so the
  ;; child keeps the place the earlier resize left it, as an override.
  (let [file     (-> (thf/sample-file :file1)
                     (tho/add-simple-component :c :c-root :c-child
                                               :root-params {:width 100 :height 100}
                                               :child-params {:x 0 :y 0 :width 20 :height 20})
                     (thc/instantiate-component :c :copy :children-labels [:copy-child]))
        resized  (-> (tm/make-situation file)
                     (r/step (n/change-height :copy 200))
                     (r/step (n/change-property :copy-child :constraints-v :scale))
                     r/settle)
        edited   (-> resized (r/step (n/change-property :c-child :fills "#dc2626")) r/settle)
        child    (tm/resolve-shape edited :copy-child)]
    (t/is (mth/close? 20 (:height (tm/resolve-shape resized :copy-child))))
    (t/is (= "#dc2626" (get-in child [:fills 0 :fill-color])))
    (t/is (mth/close? 20 (:height child)))
    (t/is (contains? (:touched child) :geometry-group))))

(t/deftest generated-constraint-a-copy-sets-after-a-resize-moves-nothing
  ;; a copy resized from its 0.01 main sets a child's vertical constraint to
  ;; scale; the child keeps the height the resize left it
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true}]
                       :ops [[:add-child 0 0 0 0] [:resize 0 0 0 0] [:delete 0 0 0 0] [:style 1 9 1]]}))))

(t/deftest a-constraint-resize-under-a-plain-parent-is-not-an-override
  ;; Update main shrinks the main root, and a nested copy inside it follows
  ;; through its scale constraint without being touched.
  (t/is (map? (cg/run {:world [2 0 {:variants true :rich-variants true :text true :text-styles true
                                    :library true :layout-copies true}]
                       :ops [[:resize 2 0 0 0] [:edit 7 5 0 0] [:style 15 9 1] [:edit 8 0 1 1]
                             [:update-main 8]]}))))

(t/deftest both-flags-and-a-half-turn-equal-neither
  (let [shape   (cts/setup-shape {:type :rect :x 10 :y 10 :width 20 :height 20})
        flagged (fn [shape expected]
                  (try (oracle/check-requested-geometry {(:id shape) shape} shape expected) nil
                       (catch #?(:clj Throwable :cljs :default) cause (:attrs (ex-data cause)))))
        both    #(assoc % :flip-x true :flip-y true)]
    (t/is (nil? (flagged (assoc (both shape) :rotation 180) shape)))
    (t/is (= #{:flip-x :flip-y} (flagged (assoc (both shape) :rotation 90) shape)))
    (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true
                                      :library-dependency true :library-text true}]
                         :ops [[:library-transform 0 5 4 0] [:library-transform 0 3 3 0]
                               [:library-remove-child 3 0 0 0] [:library-transform 0 4 4 0]]})))))

(t/deftest a-resized-turned-main-keeps-its-copies-at-their-first-corner
  ;; a width change keeps a shape's first corner; a copy oriented like its
  ;; main keeps its own first corner, whether the turn or flip is the main's
  ;; or a parent's
  (doseq [case [:turned :flipped :parent-turned]]
    (let [file   (-> (thf/sample-file :file)
                     (tho/add-simple-component :a :main-a :rect-a :root-params {:width 100 :height 100})
                     (cond-> (= case :parent-turned)
                       (-> (tho/add-frame :main-b {:width 200 :height 200})
                           (thc/instantiate-component :a :b-a :parent-label :main-b)
                           (thc/make-component :b :main-b))))
          before (-> (tm/make-situation file)
                     (r/step (n/rotate (if (= case :parent-turned) :main-b :main-a) 45)) r/settle
                     (cond-> (= case :flipped) (-> (r/step (n/transform-shape :main-a :flip-x nil)) r/settle)))
          before (r/settle (tm/with-file before
                             (thc/instantiate-component (tm/file before) (if (= case :parent-turned) :b :a)
                                                        :copy :children-labels [:copy-a])))
          label  (if (= case :parent-turned) :copy-a :copy)
          after  (-> before (r/step (n/transform-shape :main-a :width 80)) r/settle)
          copy   (ths/get-shape (tm/file after) label)]
      (t/is (mth/close? 80 (:width copy) 0.001) (str case))
      (t/is (gpt/close? (first (:points (ths/get-shape (tm/file before) label)))
                        (first (:points copy)))
            (str case)))))

(t/deftest a-turn-stores-a-mirrored-childs-rotation-as-its-transform-decomposes
  ;; a child mirrored across its diagonal, stored as a vertical flip after a
  ;; 270 degree turn, turned 45 degrees with its unflipped parent: only 225
  ;; decomposes its new transform with the flip
  (let [head    (cts/setup-shape {:type :frame :x 0 :y 0 :width 100 :height 100})
        target  (gsh/transform-shape head (ctm/rotation-modifiers head (gsh/shape->center head) 45))
        square  (cts/setup-shape {:type :rect :x 25 :y 25 :width 50 :height 50})
        mirror  (gmt/matrix 0 1 1 0 0 0)
        child   (assoc square
                       :transform mirror :transform-inverse (gmt/inverse mirror)
                       :rotation 270 :flip-y true
                       :points [(gpt/point 25 25) (gpt/point 25 75) (gpt/point 75 75) (gpt/point 75 25)])
        turned  ((cll/orient-like head target (gsh/shape->center head)) child)
        {:keys [a b c d]} (:transform turned)]
    (t/is (mth/close? 225 (:rotation turned) 0.01))
    (t/is (every? true? (map #(mth/close? %1 %2 1e-6)
                             [a b c d]
                             [(- (/ (mth/sqrt 2) 2)) (/ (mth/sqrt 2) 2) (/ (mth/sqrt 2) 2) (/ (mth/sqrt 2) 2)])))))

(t/deftest a-turn-leaves-out-the-scale-a-tiny-targets-transform-keeps
  ;; a 0.01 px board resized below its minimum size keeps the scale in its
  ;; transform; turning a subtree like it turns it without that scale
  (let [head    (cts/setup-shape {:type :frame :x 0 :y 0 :width 0.01 :height 0.01})
        scaled  (gmt/matrix 0 1 (- (/ 6 7)) 0 0 0)
        target  (assoc head :transform scaled :transform-inverse (gmt/inverse scaled) :rotation 90)
        child   (cts/setup-shape {:type :rect :x 10 :y 10 :width 20 :height 20})
        turned  ((cll/orient-like head target (gsh/shape->center head)) child)
        [p1 p2 _ p4] (:points turned)]
    (t/is (mth/close? 90 (:rotation turned) 0.01))
    (t/is (mth/close? 20 (gpt/distance p1 p2) 1e-6))
    (t/is (mth/close? 20 (gpt/distance p1 p4) 1e-6))))

(t/deftest an-unskewed-turn-that-tells-no-flip-apart-turns-before-it
  ;; a shape turned 45 degrees without flips, given a horizontal flip: both
  ;; orders of turn and flip lie as far from its axes, whatever rounding the
  ;; engine leaves in them, so the turn goes before the flip
  (let [c (mth/cos (mth/radians 45))]
    (doseq [[a b cc d] [[c c (- c) c]
                        [0.7071067811865475 0.7071067811865479 -0.7071067811865479 0.7071067811865479]
                        [0.7071067811865479 0.7071067811865475 -0.707106781186547 0.7071067811865475]
                        [0.7071067690849304 0.7071067690849304 -0.7071067690849304 0.7071067690849304]]]
      (let [shape     {:rotation 45 :flip-x true :flip-y false
                       :transform (gmt/matrix a b cc d 0 0)}
            transform (:transform (cll/unskewed shape))]
        (t/is (gmt/close? (gmt/multiply (gmt/rotate-matrix 45) (gmt/scale-matrix (gpt/point -1 1)))
                          transform)
              (pr-str [a b cc d transform]))))))
