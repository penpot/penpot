;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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

(t/deftest oracle-rejects-phantom-and-missing-overrides
  (let [situation (setup/simple-component-with-labeled-copy)
        shape (tm/resolve-shape situation :copy-child)
        changed (-> (tm/file situation)
                    (ths/update-shape :copy-child :opacity 0.4))
        authored (shadow/edit {} shape :opacity 0.4)
        missing (ths/update-shape changed :copy-child :touched nil)]
    (t/is (nil? (oracle/check situation {})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation changed) {})))
    (t/is (nil? (oracle/check (tm/with-file situation changed) authored)))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation missing) authored)))))

(t/deftest oracle-rejects-lost-propagation-and-overwritten-values
  (let [situation (setup/simple-component-with-labeled-copy)
        shape (tm/resolve-shape situation :copy-child)
        changed (ths/update-shape (tm/file situation) :copy-child :opacity 0.4)
        authored (shadow/edit {} shape :opacity 0.4)
        stale (ths/update-shape changed :copy-child :touched nil)
        lost (ths/update-shape changed :copy-child :opacity 0.7)]
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation stale) {})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation lost) authored)))))

(t/deftest equal-values-do-not-clear-an-authored-group
  (let [result (cg/run {:world [2 0] :ops [[:edit 3 1 1] [:edit 3 1 0]
                                           [:sync 0] [:undo] [:redo]]})]
    (t/is (pos? (get-in result [:executed :edit] 0)))
    (t/is (seq (:trace result)))))

(t/deftest replay-preserves-targets-and-skips
  (let [program {:world [2 1] :ops [[:reset 0] [:edit 3 0 1] [:sync 1]
                                    [:update-main 0] [:detach 0] [:undo] [:redo]]}
        result (cg/run program)]
    (t/is (= (:trace result) (:trace (cg/run program (:trace result)))))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (cg/run program [{:kind :wrong}])))))

(t/deftest transforms-run-without-a-frontend-and-check-their-inverses
  (let [result (cg/run {:world [2 0] :ops [[:edit 0 0 1]
                                           [:resize 0 0 0] [:sync 0] [:rotate 0 0 0]
                                           [:edit 0 0 2] [:undo] [:redo]]})]
    (t/is (= 1 (get-in result [:executed :resize] 0)))
    (t/is (= 1 (get-in result [:executed :rotate] 0)))))

(t/deftest structure-and-text-operations-run-through-production-nodes
  (let [result (cg/run {:world [2 0 {:text true :layout :flex}]
                        :ops [[:edit 0 0 1] [:add-child 0] [:remove-child 0]
                              [:duplicate 0] [:nest 0] [:delete 0] [:restore 0]
                              [:text 0 0 1] [:undo] [:redo]]})]
    (doseq [kind [:add-child :remove-child :duplicate :nest :delete :restore :text]]
      (t/is (pos? (get-in result [:executed kind] 0)) (str kind " did not execute")))))

(t/deftest empty-candidates-are-logged-and-empty-runs-rejected
  (let [result (cg/run {:world [2 0] :ops [[:restore 0] [:edit 0 0 1]]})]
    (t/is (= :skip (get-in result [:trace 0 :status])))
    (t/is (string? (get-in result [:trace 0 :reason]))))
  (t/is (thrown? #?(:clj Exception :cljs js/Error)
                 (cg/run {:world [2 0] :ops [[:restore 0]]}))))

(t/deftest random-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Executed component operations:" (pr-str @coverage))
    (doseq [kind [:edit :reset :update-main :detach :sync :undo :redo]]
      (t/is (pos? (get @coverage kind 0)) (str "No executed " kind)))))

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
  (let [situation (cg/world [3 0])
        mains (take 2 (filter #(get-in % [:shape :main-instance]) (cg/tree situation)))
        pending (reduce (fn [s entry]
                          (r/step s (n/change-property
                                     (constantly (get-in entry [:shape :id])) :fills "#ff0000")))
                        situation mains)
        ids (r/pending-components pending)
        partial (r/settle pending [(first (sort-by str ids))])
        settled (r/settle partial)]
    (t/is (= 2 (count ids)))
    (t/is (= 1 (count (r/pending-components partial))))
    (t/is (empty? (r/pending-components settled)))))

(t/deftest detach-from-a-deleted-near-main-inherits-only-the-skipped-level
  (t/is (map? (cg/run {:world [3 2 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:delete 1 0 0 0]
                             [:detach 13 0 0 0]]}))))

(t/deftest undo-after-a-main-child-add-and-root-rotation-is-fully-settled
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:undo 0 0 0 0]
                             [:add-child 17 0 0 0] [:rotate 19 0 0 0] [:undo 0 0 0 0]]}))))

(t/deftest minimum-size-transform-sync-shrunk-regression
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:rotate 2 0 0 0]
                             [:resize 7 0 0 0] [:duplicate 0 0 0 0]]}))))

(t/deftest updating-a-rotated-outer-main-composes-authored-child-orientation
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:duplicate 0 0 0 0]
                             [:resize 0 0 0 0] [:nest 0 0 0 0] [:rotate 4 0 0 0]
                             [:detach 3 0 0 0] [:update-main 4 0 0 0]]}))))

(t/deftest minimum-size-parent-orientation-does-not-amplify-oracle-drift
  (t/is (map? (cg/run {:world [3 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:edit 4 0 0 0]
                             [:duplicate 3 0 0 0] [:nest 10 0 0 0] [:rotate 9 0 0 0]
                             [:update-main 7 0 0 0] [:delete 0 0 0 0]]}))))

(t/deftest detaching-a-rotated-parent-inherits-geometry-in-the-copy-frame
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:resize 0 0 0 0]
                             [:delete 0 0 0 0] [:rotate 0 0 0 0] [:detach 0 0 0 0]]}))))

(t/deftest detach-shadow-does-not-read-the-resulting-child-orientation
  (let [before {:root {:id :root :shapes [:child]}
                :child {:id :child :shape-ref :near :rotation 90}
                :near {:id :near :shape-ref :main :rotation 45}}
        after {:root {:id :root :shapes [:child]}
               :child {:id :child :shape-ref :main :rotation 0}}
        model (shadow/detach {:near {:geometry-group {:rotation 45}}} before after :root)]
    (t/is (= 90 (get-in model [:child :geometry-group :rotation])))
    (t/is (false? (shadow/same-value? (:child after) :geometry-group
                                      (get-in model [:child :geometry-group]))))))

(t/deftest pushed-swap-shadow-predicts-orientation-without-reading-the-new-child
  (let [before {:copy {:id :copy :shape-ref :main :shapes [:child]}
                :main {:id :main :rotation 0}
                :child {:id :child :parent-id :copy :component-id :target :shape-ref :position
                        :rotation 45 :width 100 :height 100}
                :target-main {:id :target-main :component-id :target :parent-id :page
                              :rotation 0 :width 100 :height 100}
                :page {:id :page :rotation 0}}
        after (-> before
                  (assoc-in [:child :shape-ref] :pushed)
                  (assoc :pushed (assoc (:target-main before) :id :pushed
                                        :shape-ref :target-main :parent-id :main)))
        model (shadow/update-main {:child {:swap-group {}}} before after :copy)
        expected (get-in model [:pushed :geometry-group])]
    (t/is (= 45 (:rotation expected)))
    (t/is (false? (shadow/same-value? (:pushed after) :geometry-group expected)))))

(t/deftest pushed-swap-shadow-predicts-content-without-reading-the-new-child
  (let [before {:copy {:id :copy :shape-ref :main :shapes [:child]}
                :main {:id :main}
                :child {:id :child :parent-id :copy :component-id :target
                        :fills [:white] :shapes [:leaf]}
                :leaf {:id :leaf :parent-id :child :fills [:white]}
                :position {:id :position :component-id :target :fills [:green]}
                :position-leaf {:id :position-leaf :fills [:green]}}
        after (-> before
                  (assoc-in [:child :shape-ref] :pushed)
                  (assoc-in [:leaf :shape-ref] :pushed-leaf)
                  (assoc :pushed {:id :pushed :component-id :target :shape-ref :position
                                  :parent-id :main :fills [:green]}
                         :pushed-leaf {:id :pushed-leaf :shape-ref :position-leaf
                                       :parent-id :pushed :fills [:green]}))
        model (shadow/update-main {:child {:swap-group {}}} before after :copy)]
    (doseq [id [:pushed :pushed-leaf]]
      (t/is (= {:fills [:white] :hide-fill-on-export nil} (get-in model [id :fill-group])))
      (t/is (false? (shadow/same-value? (get after id) :fill-group (get-in model [id :fill-group])))))))

(t/deftest resizing-a-copy-of-an-empty-flex-main-keeps-layout-valid
  (t/is (map? (cg/run {:world [3 0 {:text true :layout :flex}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:remove-child 0 0 0 0]
                             [:reset 0 0 0 0] [:nest 0 0 0 0] [:resize 0 0 0 0]]}))))

(t/deftest updating-a-main-after-successive-rotations-keeps-child-flips
  (t/is (map? (cg/run {:world [2 1]
                       :ops [[:rotate 1 0 0 0] [:rotate 1 0 1 0] [:update-main 0 0 0 0]]}))))
