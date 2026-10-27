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
    (t/is (= (oracle/canonical situation) (oracle/canonical (tm/with-file situation moved))))
    (t/is (not= (oracle/canonical situation) (oracle/canonical (tm/with-file situation bad))))))

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
          turn #(gsh/absolute-move (gsh/transform-shape % (n/geometry-modifiers % :rotate 45))
                                   (gpt/point 0 0))
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
