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

(t/deftest swaps-and-variant-switches-run-in-a-three-level-world
  (let [program {:world [2 2 {:variants true}]
                 :ops [[:switch 0] [:swap 0] [:undo] [:redo]
                       [:reset 0] [:switch 1] [:sync 0]]}
        result (cg/run program)]
    (t/is (pos? (get-in result [:executed :swap] 0)))
    (t/is (pos? (get-in result [:executed :switch] 0)))
    (t/is (= (:trace result) (:trace (cg/run program (:trace result)))))))

(t/deftest variant-carry-follows-the-two-mains
  (doseq [differ? [false true]]
    (let [spec [2 2 {:variants true :variant-diff differ?}]
          initial (cg/initial-state spec)
          situation (:situation initial)
          head (:shape (cg/resolve-op situation {} [:switch 0]))
          child (first (:shapes head))
          index (first (keep-indexed #(when (= child (get-in %2 [:shape :id])) %1)
                                     (cg/candidates situation {} :edit 1)))
          result (cg/run {:world spec :ops [[:edit index 0 2 1] [:switch 0] [:sync 0]
                                            [:undo] [:redo]]})
          groups (mapcat keys (vals (:model result)))]
      (t/is (some? index))
      (t/is (= (not differ?) (boolean (some #{:fill-group} groups)))))))

(t/deftest random-swap-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/swap-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Executed swap operations:" (pr-str @coverage))
    (doseq [kind [:swap :switch :edit :reset :undo :redo]]
      (t/is (pos? (get @coverage kind 0)) (str "No executed " kind)))))

(t/deftest random-renames-leave-variant-management-to-its-own-operations
  (let [situation (cg/world [2 2 {:variants true}])
        file (tm/file situation)]
    (doseq [index (range (count (cg/tree situation)))
            :let [shape (:shape (cg/resolve-op situation {} [:edit index 2 1]))]]
      (t/is (not (:is-variant-container shape)))
      (t/is (nil? (:variant-id shape)))
      (t/is (nil? (get-in file [:data :components (:component-id shape) :variant-id]))))))

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

(t/deftest swap-after-updating-a-rotated-main-is-already-fully-synced
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1]
                             [:update-main 0] [:sync 1] [:rotate 0 0 0 0]
                             [:update-main 0 0 0 0] [:swap 0 0 0 0] [:restore 0 0 0 0]]}))))

(t/deftest update-main-after-deleting-a-swapped-main-keeps-the-model-in-step
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:delete 2 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest detach-from-a-deleted-near-main-inherits-only-the-skipped-level
  (t/is (map? (cg/run {:world [3 2 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:delete 1 0 0 0]
                             [:detach 13 0 0 0]]}))))

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

(t/deftest pushed-swap-followed-by-nesting-undoes-exactly
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1]
                             [:update-main 0] [:sync 1] [:swap 0 0 0 0]
                             [:switch 1 0 0 0] [:update-main 0 0 0 0] [:nest 0 0 0 0]]}))))

(t/deftest updating-a-rotated-outer-main-composes-authored-child-orientation
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:duplicate 0 0 0 0]
                             [:resize 0 0 0 0] [:nest 0 0 0 0] [:rotate 4 0 0 0]
                             [:detach 3 0 0 0] [:update-main 4 0 0 0]]}))))

(t/deftest swapping-main-subtrees-with-several-children-undoes-their-order
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1]
                             [:update-main 0] [:sync 1] [:add-child 1 0 0 0]
                             [:add-child 1 0 0 0] [:add-child 1 0 0 0]
                             [:swap 0 0 0 0] [:swap 0 0 0 0] [:text 0 0 0 0]]}))))

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

(t/deftest switch-shadow-tolerates-main-size-noise-but-rejects-real-size-changes
  (doseq [[difference expected] [[1e-12 80] [1 nil]]]
    (let [main {:id :old :name "Board" :type :frame :width 100 :height 100 :rotation 0}
          copy (assoc main :id :copy :shape-ref :old :height 80 :parent-id :page)
          target (assoc main :id :new :height (+ 100 difference))
          result (shadow/swap {:copy {:geometry-group (shadow/values copy :geometry-group)}}
                              {:old main :copy copy}
                              {:new target :copy (assoc copy :shape-ref :new)}
                              :copy :old :new true)]
      (t/is (= expected (get-in result [:copy :geometry-group :height]))))))

(t/deftest switch-shadow-follows-authored-content-through-a-swapped-near-main
  (doseq [swapped? [false true]]
    (let [main    {:id :a :name "Board" :type :frame :main-instance true
                   :component-id :a-component :component-file :local :fills [:white]}
          before  {:a main
                   :b (assoc main :id :b :component-id :b-component)
                   :position (assoc main :id :position :main-instance false :shape-ref :b
                                    :component-id :b-component :fills [:blue])
                   :near (assoc main :id :near :main-instance false :shape-ref (if swapped? :position :a)
                                :parent-id :outer-main :fills [:green])
                   :parent {:id :parent :shape-ref :outer-main}
                   :copy (assoc main :id :copy :main-instance false :shape-ref :near
                                :parent-id :parent :fills [:green])}
          after   (assoc before :copy (assoc (:copy before) :component-id :b-component
                                             :fills [:wrong]))
          model   {:near (cond-> {:fill-group {:fills [:green]}}
                           swapped? (assoc :swap-group {}))
                   :position {:fill-group {:fills [:blue]}}}
          result  (shadow/swap model before after :copy :a :b true)
          expected (get-in result [:copy :fill-group])]
      (t/is (= [:green] (:fills expected)))
      (t/is (false? (shadow/same-value? (:copy after) :fill-group expected))))))

(t/deftest switching-a-copy-of-a-swap-keeps-position-overrides-inherited
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 13 0 0 0] [:rotate 0 0 0 0] [:switch 2 0 0 0]]}))))

(t/deftest rotated-swapped-head-keeps-its-position-anchor-with-a-different-content-size
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1] [:update-main 0]
                             [:sync 1] [:swap 17 0 0 0] [:resize 0 0 0 0] [:rotate 0 0 0 0]]}))))

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

(t/deftest switch-shadow-keeps-inherited-orientation-even-if-the-result-loses-it
  (let [before {:page {:id :page}
                :main-a {:id :main-a :parent-id :page :name "A" :rotation 45}
                :main-b {:id :main-b :parent-id :page :name "B" :rotation 0}
                :copy {:id :copy :parent-id :page :shape-ref :main-a :rotation 45}}
        after (assoc before :copy (assoc (:copy before) :shape-ref :main-b :rotation 0))
        model (shadow/swap {} before after :copy :main-a :main-b true)
        expected (get-in model [:copy :geometry-group])]
    (t/is (= 45 (:rotation expected)))
    (t/is (false? (shadow/same-value? (:copy after) :geometry-group expected)))))

(t/deftest switch-shadow-combines-carried-size-with-retained-orientation
  (let [before {:page {:id :page}
                :main-a {:id :main-a :parent-id :page :name "A" :width 100 :height 100 :rotation 45}
                :main-b {:id :main-b :parent-id :page :name "B" :width 100 :height 100 :rotation 0}
                :copy {:id :copy :parent-id :page :shape-ref :main-a
                       :width 100 :height 80 :rotation 90}}
        after (assoc before :copy (assoc (:copy before) :shape-ref :main-b :rotation 0))
        model (shadow/swap {:copy {:geometry-group (shadow/values (:copy before) :geometry-group)}}
                           before after :copy :main-a :main-b true)
        expected (get-in model [:copy :geometry-group])]
    (t/is (= 80 (:height expected)))
    (t/is (= 90 (:rotation expected)))
    (t/is (false? (shadow/same-value? (:copy after) :geometry-group expected)))))

(t/deftest switch-keeps-retained-orientation-together-with-carried-size
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                             [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                             [:swap 0 0 0 0] [:delete 0 0 0 0] [:rotate 0 0 0 0]
                             [:nest 0 0 0 0] [:detach 0 0 0 0] [:update-main 0 0 0 0]
                             [:switch 0 0 0 0]]}))))

(t/deftest switching-an-untouched-rotated-copy-survives-full-sync
  (t/is (map? (cg/run {:world [3 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                             [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                             [:edit 11 0 0 0] [:rotate 2 0 0 0] [:nest 3 0 0 0] [:resize 3 0 0 0]
                             [:nest 3 0 0 0] [:swap 10 0 0 0] [:resize 3 0 0 0]
                             [:update-main 5 0 0 0] [:switch 0 0 0 0] [:detach 0 0 0 0]]}))))

(t/deftest pushing-a-swap-keeps-the-orientation-inherited-from-its-position
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 0 0 0 0] [:swap 13 0 0 0] [:update-main 1 0 0 0]]}))))

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

(t/deftest generated-update-main-settles-a-turned-swapped-position
  (let [result (cg/run {:world [2 2 {:variants true :variant-diff false}]
                        :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                              [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                              [:delete 8 0 0 0] [:swap 15 0 0 0] [:edit 14 0 0 0]
                              [:resize 4 0 0 0] [:resize 0 0 0 0] [:nest 4 0 0 0]
                              [:rotate 5 0 0 0] [:update-main 14 0 0 0] [:duplicate 0 0 0 0]]})]
    (t/is (pos? (get-in result [:executed :update-main] 0)))
    (t/is (pos? (get-in result [:executed :rotate] 0)))))
