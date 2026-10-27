;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-oracle-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
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
   [app.common.types.component :as ctk]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest oracle-attribute-inventory-covers-every-sync-attribute-and-group
  (let [specified (into #{}
                        (mapcat (fn [[group attrs]] (map #(vector % group) attrs)))
                        (merge-with into shadow/attributes shadow/specialized-attributes))
        production (into #{}
                         (mapcat (fn [[attr group]]
                                   (map #(vector attr %) (if (map? group) (vals group) [group]))))
                         ctk/sync-attrs)]
    (t/is (= production specified))))

(t/deftest sync-equivalence-retains-nested-copy-head-placement
  (let [initial (setup/nested-component-with-copy)
        file (tm/file initial)
        page (thf/current-page file)
        id (:id (tm/role-shape initial :copy-instance))
        changed (thf/apply-changes file
                                   (cls/generate-update-shapes
                                    (pcb/empty-changes nil (:id page))
                                    (cons id (cfh/get-children-ids (:objects page) id))
                                    #(gsh/move % (gpt/point 5 0))
                                    (:objects page) {:ignore-touched true}))]
    (t/is (not (oracle/equivalent? (oracle/canonical initial)
                                   (oracle/canonical (tm/with-file initial changed)))))))

(t/deftest design-comparison-uses-the-false-default-for-fixed-scroll
  (let [initial (setup/simple-component-with-labeled-copy)
        with-value #(tm/with-file initial (-> (tm/file initial)
                                              (ths/update-shape :copy-root :fixed-scroll %)
                                              (ths/update-shape :copy-root :touched nil)))
        disabled (with-value false)
        enabled (with-value true)
        touched (tm/with-file disabled (ths/update-shape (tm/file disabled) :copy-root :touched #{:constraints-group}))]
    (t/is (oracle/equivalent? (oracle/canonical initial) (oracle/canonical disabled)))
    (t/is (not (oracle/equivalent? (oracle/canonical initial) (oracle/canonical enabled))))
    (t/is (not (oracle/equivalent? (oracle/canonical disabled) (oracle/canonical touched))))
    (t/is (not= (oracle/snapshot initial) (oracle/snapshot disabled)))
    (t/is (nil? (oracle/check disabled {})))
    (t/is (= :I4 (try (oracle/check enabled {}) nil
                      (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error))))))))

(t/deftest backend-parity-follows-a-free-head-without-hiding-copy-drift
  (let [initial (setup/nested-component-with-copy)
        file (tm/file initial)
        page (thf/current-page file)
        objects (:objects page)
        source (tm/resolve-shape initial :main2-nested-head)
        copy (tm/role-shape initial :copy-instance)
        move (fn [heads]
               (tm/with-file initial
                 (thf/apply-changes
                  file (cls/generate-update-shapes
                        (pcb/empty-changes nil (:id page))
                        (mapcat #(cfh/get-children-ids-with-self objects (:id %)) heads)
                        #(gsh/move % (gpt/point 30 0)) objects {:ignore-touched true}))))
        inherited (move [source copy])
        drifted (move [copy])
        parity {:relative-free-head-placement true}
        strict {:free-head-placement false}]
    (t/is (oracle/equivalent? (oracle/canonical initial parity) (oracle/canonical inherited parity)))
    (t/is (not (oracle/equivalent? (oracle/canonical initial parity) (oracle/canonical drifted parity))))
    (t/is (not (oracle/equivalent? (oracle/canonical initial strict) (oracle/canonical inherited strict))))
    (t/is (not (oracle/equivalent? (oracle/canonical initial) (oracle/canonical inherited))))))

(t/deftest oracle-rejects-unmarked-differences-in-all-scalar-groups
  (let [situation (setup/simple-component-with-labeled-copy)
        file (tm/file situation)]
    (doseq [[attr value] [[:hidden true] [:blocked true] [:r1 8]
                          [:strokes [{:stroke-color "#ff0000" :stroke-opacity 1
                                      :stroke-width 2 :stroke-style :solid
                                      :stroke-alignment :center}]]
                          [:shadow [{:id (thi/new-id! :shadow) :style :drop-shadow
                                     :color {:color "#000000" :opacity 0.5}
                                     :offset-x 2 :offset-y 2 :blur 4 :spread 0
                                     :hidden false}]]
                          [:blur {:id (thi/new-id! :blur) :type :layer-blur :value 4 :hidden false}]
                          [:show-content true]]]
      (t/testing (str attr)
        (let [target (if (= attr :show-content) :copy-root :copy-child)
              broken (-> file
                         (ths/update-shape target attr value)
                         (ths/update-shape target :touched nil))]
          (t/is (= :I4 (try
                         (oracle/check (tm/with-file situation broken) {})
                         nil
                         (catch #?(:clj Exception :cljs js/Error) error
                           (:invariant (ex-data error)))))))))))

(t/deftest oracle-rejects-touched-on-mains-and-detached-shapes
  (let [initial (setup/simple-component-with-labeled-copy)
        detached (-> initial (r/step (n/detach :copy-root)) r/settle)]
    (doseq [[situation label] [[initial :main-child] [detached :copy-child]]]
      (let [file (tm/file situation)
            page-id (:id (thf/current-page file))
            broken (assoc-in file [:data :pages-index page-id :objects (thi/id label) :touched]
                             #{:fill-group})]
        (t/is (thrown? #?(:clj Exception :cljs js/Error)
                       (oracle/check (tm/with-file situation broken) {} false)))))))

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

(t/deftest grid-visibility-expectations-still-reject-cell-corruption
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-frame :main-root (merge cls/initial-grid-layout {:width 300 :height 100}))
                 (tho/add-rect :main-first {:parent-label :main-root})
                 (tho/add-rect :main-second {:parent-label :main-root})
                 (tho/add-rect :main-third {:parent-label :main-root})
                 (tho/place-in-grid-columns :main-root 3 {1 :main-first 2 :main-second 3 :main-third})
                 (thc/make-component :component1 :main-root)
                 (thc/instantiate-component :component1 :copy-root
                                            :children-labels [:copy-first :copy-second :copy-third]))
        initial (tm/make-situation file)
        page-id (:id (thf/current-page file))
        first-child (ths/get-shape file :copy-first)
        hidden (r/step initial (n/change-property :copy-first :hidden true))
        model (shadow/edit {} first-child :hidden true)
        cells-path [:data :pages-index page-id :objects (thi/id :copy-root) :layout-grid-cells]
        cells (vals (get-in (tm/file hidden) cells-path))
        occupied (first (filter (comp seq :shapes) cells))
        empty-cell (first (remove (comp seq :shapes) cells))
        corrupt (fn [cell f]
                  (tm/with-file hidden
                    (update-in (tm/file hidden) (conj cells-path (:id cell)) f)))
        invariant (fn [situation]
                    (try (oracle/check situation model) nil
                         (catch #?(:clj Exception :cljs js/Error) error
                           (select-keys (ex-data error) [:invariant :group]))))]
    (t/is (nil? (oracle/check hidden model)))
    (doseq [[cell f] [[empty-cell #(assoc % :shapes [(thi/id :copy-first)])]
                      [occupied #(assoc % :shapes [])]
                      [occupied #(update % :column inc)]
                      [occupied #(update % :column-span inc)]
                      [occupied #(assoc % :position :auto)]]]
      (t/is (= {:invariant :I4 :group :layout-grid-cells}
               (invariant (corrupt cell f)))))))

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

(t/deftest measured-text-comparison-keeps-the-oriented-first-corner
  (doseq [grow-type [:auto-height :auto-width]
          angle [0 45 90 135]
          reflected? [false true]]
    (let [file (-> (thf/sample-file :file)
                   (ths/add-sample-shape :text :type :text :grow-type grow-type
                                         :width 70 :height 25))
          page-id (:id (thf/current-page file))
          path [:data :pages-index page-id :objects (thi/id :text)]
          orient (fn [shape]
                   (cond-> (gsh/transform-shape shape (n/geometry-modifiers shape :rotate angle))
                     reflected? (as-> shape (gsh/transform-shape shape (n/geometry-modifiers shape :flip-x nil)))))
          file (update-in file path orient)
          original (get-in file path)
          measured (update-in file path
                              (fn [shape]
                                (let [resized (gsh/transform-shape
                                               shape (ctm/change-size shape
                                                                      (if (= :auto-width grow-type) 55 70) 17))]
                                  (gsh/move resized (gpt/subtract (first (:points shape))
                                                                  (first (:points resized)))))))
          shifted (update-in measured path gsh/move (gpt/point 1 0))
          wrong-width (assoc-in measured (conj path :width) 60)
          design #(oracle/canonical (tm/make-situation %) {:free-head-placement false})]
      (t/testing (str grow-type ", angle " angle ", reflected " reflected?)
        (t/is (oracle/equivalent? (design file) (design measured))))
      (t/is (not (oracle/equivalent? (design file) (design shifted))))
      (t/is (not= (oracle/snapshot (tm/make-situation file))
                  (oracle/snapshot (tm/make-situation measured))))
      (t/is (nil? (oracle/check-requested-geometry (:objects (thf/current-page measured))
                                                   (get-in measured path) original)))
      (t/is (thrown? #?(:clj Exception :cljs js/Error)
                     (oracle/check-requested-geometry (:objects (thf/current-page shifted))
                                                      (get-in shifted path) original)))
      (when (= :auto-height grow-type)
        (t/is (not (oracle/equivalent? (design file) (design wrong-width))))))))

(t/deftest matrix-comparison-does-not-reintroduce-derived-text-dimensions
  (doseq [grow-type [:auto-height :auto-width]]
    (let [file (-> (thf/sample-file :file)
                   (ths/add-sample-shape :text :type :text :grow-type grow-type
                                         :width 70 :height 25))
          page-id (:id (thf/current-page file))
          path [:data :pages-index page-id :objects (thi/id :text)]
          measured (update-in file path
                              #(gsh/transform-shape % (ctm/change-size %
                                                                       (if (= grow-type :auto-width) 55 70)
                                                                       17)))
          changed (assoc-in measured (conj path :transform) (gmt/matrix 1 0 0 1 2 0))
          stretched (assoc-in measured (conj path :transform) (gmt/matrix 1 0 0 1.1 0 0))
          fixed-width (assoc-in measured (conj path :width) 60)
          ;; Both measured versions use the renderer's oriented corner anchor.
          turn (fn [shape]
                 (let [turned (gsh/transform-shape shape (n/geometry-modifiers shape :rotate 45))]
                   (gsh/move turned (gpt/negate (first (:points turned))))))
          rotated (update-in file path turn)
          rotated-measured (update-in measured path turn)
          design #(oracle/canonical (tm/make-situation %))]
      (t/is (oracle/equivalent? (design file) (design measured)))
      (t/is (oracle/equivalent? (design rotated) (design rotated-measured)))
      (t/is (not (oracle/equivalent? (design file) (design changed))))
      (t/is (not (oracle/equivalent? (design file) (design stretched))))
      (t/is (not= (oracle/snapshot (tm/make-situation file))
                  (oracle/snapshot (tm/make-situation measured))))
      (when (= grow-type :auto-height)
        (t/is (not (oracle/equivalent? (design file) (design fixed-width))))))))

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
