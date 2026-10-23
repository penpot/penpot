(ns common-tests.logic.composable-oracle-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.placement :as clp]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
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
