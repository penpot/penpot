;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-oracle-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.placement :as clp]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest sync-equivalence-compares-geometry-without-rounding-boundaries
  (let [initial (setup/simple-component-with-labeled-copy)
        file (ths/update-shape (tm/file initial) :copy-child :opacity 0.4)
        page (thf/current-page file)
        id (thi/id :copy-child)
        move-to (fn [x]
                  (tm/with-file initial
                    (thf/apply-changes file
                                       (cls/generate-update-shapes
                                        (pcb/empty-changes nil (:id page)) [id]
                                        #(gsh/absolute-move % (gpt/point x (:y %)))
                                        (:objects page) {:ignore-touched true}))))
        before (move-to (- 103.005 1e-6))
        after (move-to (+ 103.005 1e-6))
        scalar (tm/with-file before
                 (assoc-in (tm/file before) [:data :pages-index (:id page) :objects id :opacity]
                           (+ 0.4 1e-15)))]
    (t/is (oracle/equivalent? (oracle/canonical before) (oracle/canonical after)))
    (t/is (not (oracle/equivalent? (oracle/canonical before) (oracle/canonical (move-to 101)))))
    (t/is (not (oracle/equivalent? (oracle/canonical before) (oracle/canonical scalar))))))

(t/deftest swapped-head-inputs-follow-position-and-content-separately
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component :a :a-root :a-child
                                           :root-params {:height 120})
                 (tho/add-simple-component :b :b-root :b-child
                                           :root-params {:constraints-h :right
                                                         :layout-item-margin {:m1 9 :m2 9 :m3 9 :m4 9}})
                 (tho/add-frame :outer-root)
                 (thc/instantiate-component :a :position :parent-label :outer-root)
                 (thc/make-component :outer :outer-root)
                 (thc/instantiate-component :outer :copy-root :children-labels [:swapped])
                 (tho/swap-component-in-shape :swapped :b))
        file (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))
        model {(thi/id :swapped) {:swap-group {}}}]
    (t/is (nil? (oracle/check (tm/make-situation file) model)))
    (doseq [[attr value] [[:width 101]
                          [:constraints-h :right]
                          [:layout-item-margin {:m1 9 :m2 9 :m3 9 :m4 9}]]]
      (let [broken (assoc-in file [:data :pages-index (:id (thf/current-page file))
                                   :objects (thi/id :swapped) attr] value)]
        (t/is (thrown? #?(:clj Exception :cljs js/Error)
                       (oracle/check (tm/make-situation broken) model)))))))

(t/deftest detached-rotated-parent-still-places-its-nested-copy
  (let [detached (-> (setup/nested-component-with-copy)
                     (r/step (n/rotate :copy-root 90)) r/settle
                     (r/step (n/detach :copy-root)) r/settle)]
    (t/is (nil? (oracle/check detached {})))
    (let [file (tm/file detached)
          page (thf/current-page file)
          head (tm/role-shape detached :copy-instance)
          id (first (:shapes head))
          changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page)) [id]
                                              #(gsh/move % (gpt/point 10 0)) (:objects page)
                                              {:ignore-touched true})
          broken (tm/with-file detached (thf/apply-changes file changes))]
      (t/is (thrown? #?(:clj Exception :cljs js/Error) (oracle/check broken {}))))))

(defn- sample
  [constraint]
  (-> (thf/sample-file :file1)
      (tho/add-frame :main-root {:width 100 :height 100})
      (tho/add-rect :main-child {:parent-label :main-root :x 20 :y 10
                                 :width 30 :height 20
                                 :constraints-h constraint :constraints-v :top})
      (thc/make-component :component1 :main-root)
      (thc/instantiate-component :component1 :copy-root :children-labels [:copy-child])))

(defn- transform
  [file label modifiers]
  (let [page (thf/current-page file)
        objects (:objects page)
        id (thi/id label)
        tree (gm/set-objects-modifiers {id {:modifiers modifiers}} objects)
        change (clp/modifiers-transform tree)]
    (thf/apply-changes file
                       (cls/generate-update-shapes
                        (pcb/empty-changes nil (:id page)) (keys tree) change objects
                        {:ignore-tree (clp/calculate-ignore-tree objects (keys tree) change #{id})
                         :attrs clp/transform-attrs}))))

(defn- geometry-model
  [file]
  {(thi/id :copy-root) {:geometry-group (shadow/values (ths/get-shape file :copy-root)
                                                       :geometry-group)}})

(t/deftest oracle-accepts-placement-and-constraint-resize
  (doseq [constraint [:scale :leftright :left :center]]
    (let [file (sample constraint)
          root (ths/get-shape file :copy-root)
          resized (transform file :copy-root (ctm/change-size root 200 100))
          rotated (transform resized :copy-root
                             (ctm/rotation-modifiers (ths/get-shape resized :copy-root)
                                                     (gsh/shape->center (ths/get-shape resized :copy-root))
                                                     90))]
      (t/is (nil? (oracle/check (tm/make-situation file) {})))
      (t/is (nil? (oracle/check (tm/make-situation resized) (geometry-model resized))))
      (t/is (nil? (oracle/check (tm/make-situation rotated) (geometry-model rotated)))))))

(t/deftest oracle-rejects-wrong-child-position-and-size
  (let [file (sample :left)
        page (thf/current-page file)
        change (fn [f]
                 (thf/apply-changes
                  file (cls/generate-update-shapes
                        (pcb/empty-changes nil (:id page)) #{(thi/id :copy-child)}
                        f (:objects page) {:ignore-touched true})))
        moved (change #(gsh/move % (gpt/point 5 0)))
        resized (change #(gsh/transform-shape % (ctm/change-size % 40 20)))]
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/make-situation moved) {})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/make-situation resized) {})))))

(t/deftest oracle-rejects-unmarked-constraint-differences
  (let [file (-> (sample :left)
                 (ths/update-shape :copy-child :constraints-h :right)
                 (ths/update-shape :copy-child :touched nil))]
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/make-situation file) {})))))

(t/deftest oracle-accepts-layout-owned-fill-sizing
  (doseq [layout [:flex :grid]]
    (let [file (-> (thf/sample-file :file1)
                   (tho/add-frame :main-root {:width 100 :height 100})
                   (tho/add-rect :main-child {:parent-label :main-root :x 20 :y 10
                                              :width 30 :height 20 :constraints-h :scale})
                   (thc/make-component :component1 :main-root)
                   (tho/add-frame :layout-parent (merge (case layout :flex cls/initial-flex-layout
                                                              :grid cls/initial-grid-layout)
                                                        {:width 200 :height 200}))
                   (thc/instantiate-component :component1 :copy-root :parent-label :layout-parent
                                              :children-labels [:copy-child])
                   (ths/update-shape :copy-root :layout-item-h-sizing :fill)
                   (cond-> (= layout :grid)
                     (tho/place-in-grid-columns :layout-parent 1 {1 :copy-root})))
          root (ths/get-shape file :copy-root)
          page (thf/current-page file)
          tree (gm/set-objects-modifiers {(:id root) {:modifiers (ctm/change-size root 200 nil)}}
                                         (:objects page))
          resized (thf/apply-changes
                   file (cls/generate-update-shapes
                         (pcb/empty-changes nil (:id page)) (keys tree)
                         (clp/modifiers-transform tree) (:objects page) {:ignore-touched true}))]
      (t/is (nil? (oracle/check (tm/make-situation resized)
                                {(thi/id :copy-root) {:layout-item-h-sizing {:layout-item-h-sizing :fill}}}))))))

(t/deftest oracle-accepts-measured-auto-height-text
  (let [situation (cg/world [2 0 {:text true}])
        file (tm/file situation)
        page (thf/current-page file)
        resized (thf/apply-changes
                 file (cls/generate-update-shapes
                       (pcb/empty-changes nil (:id page)) #{(thi/id :copy-child)}
                       #(gsh/transform-shape % (ctm/change-size % nil 80))
                       (:objects page) {:ignore-touched true}))]
    (t/is (nil? (oracle/check (tm/with-file situation resized) {})))))

(t/deftest oracle-accepts-a-layout-copy-after-main-child-addition
  (doseq [layout [:flex :grid]]
    (let [result (cg/run {:world [2 0 {:layout layout}]
                          :ops [[:add-child 0] [:sync 1]]})]
      (t/is (= 1 (get-in result [:executed :add-child]))))))

(t/deftest oracle-rejects-unmarked-layout-input-differences
  (let [situation (cg/world [2 0 {:layout :flex}])
        root ((n/copy-head-of "random-0") situation)
        file (-> (tm/file situation)
                 (ths/update-shape-by-id root :layout-gap {:row-gap 10 :column-gap 10})
                 (ths/update-shape-by-id root :touched nil))]
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation file) {})))))

(t/deftest design-comparison-keeps-relative-positions-under-layout-movement
  (let [state (reduce cg/step (cg/initial-state [2 0 {:text true :layout :flex}])
                      [[:nest 0] [:detach 0]])
        situation (:situation (cg/settle-state state))
        file (tm/file situation)
        page (thf/current-page file)
        move (fn [file ids delta]
               (thf/apply-changes
                file (cls/generate-update-shapes
                      (pcb/empty-changes nil (:id page)) ids
                      #(gsh/move % (gpt/point delta 0))
                      (:objects (thf/current-page file)) {:ignore-touched true})))
        moved (move file #{(thi/id :copy-root) (thi/id :copy-child)} 50)
        bad (move moved #{(thi/id :copy-child)} 5)]
    (t/is (oracle/equivalent? (oracle/canonical situation) (oracle/canonical (tm/with-file situation moved))))
    (t/is (not (oracle/equivalent? (oracle/canonical situation) (oracle/canonical (tm/with-file situation bad)))))))

(t/deftest sync-comparison-treats-nil-and-false-flips-as-the-same-orientation
  (let [situation (setup/simple-component-with-labeled-copy)
        file (tm/file situation)
        unflipped (tm/with-file situation (-> file (ths/update-shape :copy-root :flip-x false) (ths/update-shape :copy-root :touched nil)))
        flipped (tm/with-file situation (-> file (ths/update-shape :copy-root :flip-x true) (ths/update-shape :copy-root :touched nil)))]
    (t/is (oracle/equivalent? (oracle/canonical situation) (oracle/canonical unflipped)))
    (t/is (not (oracle/equivalent? (oracle/canonical situation) (oracle/canonical flipped))))))

(t/deftest expected-child-flips-follow-successive-parent-rotations
  (let [file (sample :left)
        turn (fn [file]
               (let [root (ths/get-shape file :copy-root)]
                 (transform file :copy-root
                            (ctm/rotation-modifiers root (gsh/shape->center root) 45))))
        rotated (-> file turn turn)]
    (t/is (nil? (oracle/check (tm/make-situation rotated) (geometry-model rotated))))))

(t/deftest backend-comparison-allows-instance-placement-after-leaving-a-layout
  (let [situation (setup/nested-component-with-copy)
        file (tm/file situation)
        page (thf/current-page file)
        head (tm/resolve-shape situation :main2-nested-head)
        child (first (:shapes head))
        move (fn [ids]
               (thf/apply-changes
                file (cls/generate-update-shapes
                      (pcb/empty-changes nil (:id page)) ids
                      #(gsh/move % (gpt/point 30 0)) (:objects page)
                      {:ignore-touched true})))
        placed (tm/with-file situation (move [(:id head) child]))
        broken (tm/with-file situation (move [child]))]
    (t/is (oracle/equivalent? (oracle/canonical situation) (oracle/canonical placed)))
    (t/is (not (oracle/equivalent? (oracle/canonical situation) (oracle/canonical broken))))))

(t/deftest nested-copy-child-flips-follow-successive-root-rotations
  (let [result (cg/run
                {:world [2 1 {:text true}]
                 :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                       [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                       [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                       [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                       [:detach 0] [:undo] [:redo]
                       [:rotate 4 0 0 0] [:rotate 4 0 5 0]]})]
    (t/is (= 3 (get-in result [:executed :rotate])))))

(t/deftest matrix-equivalence-measures-its-effect-in-pixels
  (doseq [[size equal?] [[0.01 true] [100 false]]]
    (let [file (-> (thf/sample-file :file1)
                   (tho/add-rect :rect {:width size :height size}))
          changed (ths/update-shape file :rect :transform (gmt/matrix 0.8 0 0 1 0 0))]
      (t/is (= equal? (oracle/equivalent? (oracle/canonical (tm/make-situation file))
                                          (oracle/canonical (tm/make-situation changed))))))))

(t/deftest authored-child-orientation-follows-the-parent-frame
  (let [model {:child {:geometry-group {:width 20 :height 30 :rotation 90}}}
        before {:child {:id :child :parent-id :parent}
                :parent {:rotation 45}}
        after {:child {:id :child :parent-id :parent :rotation 0 :width 20 :height 30}
               :parent {:rotation 135 :flip-x true :flip-y true}}
        expected (get-in (shadow/parent-orientation model before after) [:child :geometry-group])]
    (t/is (= {:width 20 :height 30 :rotation 180 :flip-x true :flip-y true} expected))
    (t/is (not (shadow/same-value? (:child after) :geometry-group expected)))))

(t/deftest swapped-head-content-size-keeps-the-position-anchor-after-rotation
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component :a :a-root :a-child
                                           :root-params {:width 100 :height 100}
                                           :child-params {:width 20 :height 20})
                 (tho/add-simple-component :b :b-root :b-child
                                           :root-params {:width 100 :height 100}
                                           :child-params {:width 20 :height 20})
                 (tho/add-frame :outer-root)
                 (thc/instantiate-component :a :position :parent-label :outer-root))
        root (ths/get-shape file :position)
        file (transform file :position (ctm/change-size root 100 80))
        root (ths/get-shape file :position)
        file (-> (transform file :position
                            (ctm/rotation-modifiers root (gsh/shape->center root) 45))
                 (thc/make-component :outer :outer-root)
                 (thc/instantiate-component :outer :copy-root :children-labels [:swapped])
                 (tho/swap-component-in-shape :swapped :b))
        file (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))
        model {(thi/id :position) {:geometry-group (shadow/values (ths/get-shape file :position)
                                                                  :geometry-group)}
               (thi/id :swapped) {:swap-group {}}}
        page (thf/current-page file)
        moved (thf/apply-changes
               file (cls/generate-update-shapes
                     (pcb/empty-changes nil (:id page)) [(thi/id :swapped)]
                     #(gsh/move % (gpt/point 5 0)) (:objects page)
                     {:ignore-touched true}))]
    (t/is (nil? (oracle/check (tm/make-situation file) model)))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/make-situation moved) model)))))

(t/deftest minimum-size-parent-placement-keeps-fixed-child-dimensions
  (let [result (cg/run {:world [2 0 {:text true :layout nil}]
                        :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                              [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                              [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                              [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                              [:detach 0] [:undo] [:redo] [:resize 1 0 0 0]
                              [:nest 2 0 0 0] [:rotate 0 0 3 0] [:edit 0 0 0 0]]})
        situation (:situation result)
        file (tm/file situation)
        page (thf/current-page file)
        objects (:objects page)
        child (first (filter (fn [shape]
                               (let [parent (get objects (:parent-id shape))]
                                 (and (= :frame (:type shape)) (:component-id shape)
                                      (:shape-ref shape) (empty? (:touched shape))
                                      (> (:height shape) 1)
                                      (< (:width parent 1) 0.02)
                                      (< (:height parent 1) 0.02))))
                             (vals objects)))
        changed (thf/apply-changes
                 file (cls/generate-update-shapes
                       (pcb/empty-changes nil (:id page)) [(:id child)]
                       #(gsh/transform-shape % (ctm/change-size % nil 80)) objects
                       {:ignore-touched true}))]
    (t/is (some? child))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation changed) (:model result))))))
