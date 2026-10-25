(ns common-tests.logic.composable-gen-test
  (:require
   [app.common.schema.test :as smt]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest structure-and-text-operations-run-through-production-nodes
  (let [result (cg/run {:world [2 0 {:text true :layout :flex}]
                        :ops [[:edit 0 0 1] [:add-child 0] [:remove-child 0]
                              [:duplicate 0] [:nest 0] [:delete 0] [:restore 0]
                              [:text 0 0 1] [:undo] [:redo]]})]
    (doseq [kind [:add-child :remove-child :duplicate :nest :delete :restore :text]]
      (t/is (pos? (get-in result [:executed kind] 0)) (str kind " did not execute")))))

(t/deftest random-structure-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/structure-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Executed structure operations:" (pr-str @coverage))
    (doseq [kind cg/structure-kinds]
      (t/is (pos? (get @coverage kind 0)) (str "No executed " kind)))))

(t/deftest partial-sync-keeps-independent-components-pending
  (let [initial (cg/initial-state [3 0])
        source (get-in (cg/resolve-op (:situation initial) {} [:nest 0]) [:shape :id])
        nested (-> initial (cg/step [:nest 0]) (cg/settle-state))
        candidates (cg/candidates (:situation nested) (:model nested) :nest nil)
        index (first (keep-indexed #(when (= source (get-in %2 [:shape :id])) %1) candidates))
        state (cg/step nested [:nest index])
        partial (cg/step state [:sync 1])
        settled (cg/settle-state partial)]
    (t/is (= 2 (count (r/pending-components (:situation state)))))
    (t/is (= 1 (count (r/pending-components (:situation partial)))))
    (t/is (empty? (r/pending-components (:situation settled))))))

(t/deftest generated-library-world-has-remote-mains-and-local-copies
  (let [situation (cg/world [2 1 {:library true}])
        file (tm/file situation)
        libraries (tm/aux-files situation)
        remote (filter #(contains? libraries (:component-file (:shape %))) (cg/tree situation))]
    (t/is (= 1 (count libraries)))
    (t/is (seq remote))
    (t/is (every? :shape-ref (map :shape remote)))
    (t/is (not (contains? libraries (:id file))))))

(t/deftest generated-library-update-main-checks-remote-and-local-inverses
  (let [program {:world [2 1 {:library true :variants true}]
                 :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                       [:undo] [:redo] [:sync 1]]}
        result (cg/run program)]
    (t/is (= 1 (get-in result [:executed :update-main])))
    (t/is (= 1 (get-in result [:executed :library-sync])))
    (t/is (= 1 (count (tm/aux-files (:situation result)))))))

(t/deftest random-library-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/library-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Executed library operations:" (pr-str @coverage))
    (doseq [kind [:edit :update-main :library-sync :undo :redo]]
      (t/is (pos? (get @coverage kind 0)) (str "No executed " kind)))))

(t/deftest update-main-moves-an-authored-swap-to-the-replaced-main-shape
  (let [result (cg/run {:world [2 1 {:library true :variants true}]
                        :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                              [:undo] [:redo] [:reset 0] [:sync 1]
                              [:switch 2 0 0 0] [:update-main 0 0 0 0]]})]
    (t/is (some #(contains? % :swap-group) (vals (:model result))))))

(t/deftest update-main-after-deleting-a-swapped-main-keeps-the-model-in-step
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:delete 2 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest switch-keeps-root-geometry-inherited-through-a-near-main
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 0 0 0 0] [:switch 1 0 0 0]]}))))

(t/deftest a-main-detach-removes-an-indirect-copys-invalid-swap
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:detach 0 0 0 0] [:delete 0 0 0 0]]}))))

(t/deftest detached-swapped-head-follows-its-own-component-without-position-overrides
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:duplicate 1 0 0 0] [:swap 13 0 0 0] [:detach 16 0 0 0]]}))))

(t/deftest detaching-an-outer-copy-keeps-its-surviving-inner-swap
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 3 0 0 0] [:detach 3 0 0 0]]}))))

(t/deftest updating-main-then-swapping-a-child-restores-parent-state
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5 0 0 0] [:update-main 0 0 0 0] [:swap 13 0 0 0]]}))))

(t/deftest pushed-variant-subtree-restores-its-main-parent-order
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:resize 0 0 0 0] [:duplicate 0 0 0 0]
                             [:switch 2 0 0 0] [:update-main 2 0 0 0]]}))))

(t/deftest updating-a-duplicated-switched-copy-restores-the-main-order
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:duplicate 0 0 0 0]
                             [:update-main 0 0 0 0] [:redo 0 0 0 0]]}))))

(t/deftest switching-a-detached-rotated-copy-keeps-its-resize
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 1 0 0 0] [:detach 1 0 0 0]
                             [:detach 2 0 0 0] [:resize 2 0 0 0] [:switch 2 0 0 0]]}))))

(t/deftest switching-a-copy-of-a-swap-keeps-position-overrides-inherited
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 13 0 0 0] [:rotate 0 0 0 0] [:switch 2 0 0 0]]}))))

(t/deftest generated-library-pushed-swap-authors-content-against-its-new-position
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 2 0 0 0] [:edit 8 0 0 0] [:switch 0 0 0 0]
                             [:update-main 7 0 0 0]]}))))

(t/deftest generated-library-swap-back-syncs-the-near-positions-overrides
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 15 0 0 0] [:duplicate 16 0 0 0] [:edit 8 0 0 0]
                             [:switch 4 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest generated-library-move-restores-existing-component-nesting-on-undo
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5 0 0 0] [:nest 8 0 0 0] [:nest 5 0 0 0]
                             [:restore 0 0 0 0]]}))))

(t/deftest generated-library-authored-child-follows-its-parents-synced-turn
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:resize 0 0 0 0] [:switch 2 0 0 0] [:rotate 1 0 0 0]
                             [:remove-child 0 0 0 0]]}))))

(t/deftest pushing-a-swap-keeps-the-orientation-inherited-from-its-position
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 0 0 0 0] [:swap 13 0 0 0] [:update-main 1 0 0 0]]}))))

(t/deftest pushing-a-switched-child-uses-its-parents-pushed-frame
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5 0 0 0] [:rotate 1 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest generated-library-switch-keeps-overrides-from-a-deleted-near-main
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:delete 0 0 0 0] [:delete 3 0 0 0] [:resize 0 0 0 0]
                             [:delete 9 0 0 0] [:switch 0 0 0 0]]}))))

(t/deftest generated-library-duplicate-refuses-an-expanding-component-branch
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5] [:nest 8] [:duplicate 1]]}))))

(t/deftest generated-library-parent-turn-settles-swapped-placement
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 2 0 0 0] [:resize 0 0 0 0] [:rotate 2 0 0 0]]}))))

(t/deftest generated-library-restored-variant-syncs-its-converted-name
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:delete 3 0 0 0] [:delete 1 0 0 0] [:restore 7 0 0 0]
                             [:delete 1 0 0 0] [:restore 0 0 0 0]]}))))

(t/deftest generated-library-switch-inherits-a-swapped-near-mains-authored-fill
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:edit 12 0 0 0] [:nest 10 0 0 0]
                             [:switch 7 0 0 0] [:switch 2 0 0 0]]}))))

(t/deftest generated-library-reset-places-new-children-after-clearing-rotation
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:rotate 1 0 0 0] [:duplicate 0 0 0 0]
                             [:reset 2 0 0 0] [:redo 0 0 0 0]]}))))
