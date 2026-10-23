(ns common-tests.logic.composable-gen-test
  (:require
   [app.common.schema.test :as smt]
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
