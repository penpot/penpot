;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.source-gen-test
  (:require
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def source-world
  [2 1 {:library true :stale-libraries true :library-text true :text true :text-styles true}])

(def dependency-text-world
  [2 0 {:library true :stale-libraries true :library-dependency true :library-text true}])

(t/deftest dependency-text-fixture-links-source-through-the-dependent-main
  (let [situation (cg/world dependency-text-world)
        {:keys [source-id dependent-id source-text-id dependent-head-id dependent-text-id
                consumer-root-ids consumer-text-ids direct-text-ids]}
        (cg/get-library-text-chain situation)
        dependent (get (tm/aux-files situation) dependent-id)
        head (tm/shape-by-id (tm/with-file situation dependent) dependent-head-id)
        text (tm/shape-by-id (tm/with-file situation dependent) dependent-text-id)
        main (ths/get-shape dependent :sync-library-dependent-head)
        ids (map (comp :id :shape) (cg/forest situation))]
    (t/is (some? head))
    (t/is (= source-id (:component-file head)))
    (t/is (some #{dependent-head-id} (:shapes main)))
    (t/is (= source-text-id (:shape-ref text)))
    (t/is (= 2 (count consumer-root-ids)))
    (t/is (= 2 (count consumer-text-ids)))
    (t/is (= 2 (count direct-text-ids)))
    (t/is (not-any? (set direct-text-ids) consumer-text-ids))
    (t/is (= (count ids) (count (set ids))))))

(defn- get-text
  [state file-id shape-id]
  (let [situation (:situation state)
        file (get (cf/all-files situation) file-id)]
    (thtx/view (:content (tm/shape-by-id (tm/with-file situation file) shape-id)))))

(defn- get-history-depth
  [state file-id]
  (count (get-in state [:situation :library-histories file-id :undo])))

(t/deftest transitive-text-waits-for-both-library-acceptances
  (doseq [tuple (into [[:library-text 0 0 1]]
                      (map #(vector :library-text-style 0 % 1) (range 12)))]
    (t/testing (str tuple)
      (let [initial (cg/initial-state dependency-text-world)
            situation (:situation initial)
            {:keys [source-id dependent-id source-text-id dependent-text-id
                    consumer-text-ids direct-text-ids]} (cg/get-library-text-chain situation)
            current-id (:id (tm/file situation))
            expected (thtx/normalize (:value (:op (cg/resolve-op situation {} tuple))))
            original (get-text initial source-id source-text-id)
            edited (-> initial (cg/step tuple) cg/settle-state)
            early-accept (-> edited (cg/step [:library-sync 0]) cg/settle-state)
            upstream (-> early-accept (cg/step [:library-accept 0]) cg/settle-state)
            accepted (-> upstream (cg/step [:library-sync 0]) cg/settle-state)]
        (t/is (not= original expected) "the requested source edit changes text")
        (t/is (= expected (get-text edited source-id source-text-id)))
        (t/is (= (dissoc (oracle/snapshot situation) source-id)
                 (dissoc (oracle/snapshot (:situation edited)) source-id))
              "a source edit changes neither dependent nor consumer files")
        (t/is (= (oracle/snapshot (:situation edited))
                 (oracle/snapshot (:situation early-accept)))
              "accepting a still-stale dependent cannot pull the upstream text")
        (t/is (zero? (r/undo-depth (:situation early-accept))))
        (t/is (= original (get-text early-accept dependent-id dependent-text-id)))
        (t/is (= expected (get-text upstream dependent-id dependent-text-id)))
        (t/is (= (dissoc (oracle/snapshot (:situation early-accept)) dependent-id)
                 (dissoc (oracle/snapshot (:situation upstream)) dependent-id))
              "upstream acceptance changes only the dependent editor's file")
        (t/is (= (repeat 2 original) (map #(get-text upstream current-id %) consumer-text-ids)))
        (t/is (zero? (r/undo-depth (:situation upstream))))
        (t/is (= 1 (get-history-depth upstream source-id)))
        (t/is (= 1 (get-history-depth upstream dependent-id)))
        (t/is (= (repeat 2 expected) (map #(get-text accepted current-id %) consumer-text-ids)))
        (t/is (= (repeat 2 original) (map #(get-text accepted current-id %) direct-text-ids))
              "accepting the dependent does not accept direct source copies")
        (t/is (= (dissoc (oracle/snapshot (:situation upstream)) current-id)
                 (dissoc (oracle/snapshot (:situation accepted)) current-id)))
        (t/is (= 1 (r/undo-depth (:situation accepted))))
        (t/is (map? (cg/run {:world dependency-text-world
                             :ops [tuple [:library-sync 0] [:library-accept 0] [:library-sync 0]
                                   [:undo] [:redo] [:library-undo 1] [:library-redo 1]
                                   [:library-undo 0] [:library-redo 0]]})))))))

(t/deftest transitive-text-editors-undo-only-their-own-files
  (let [initial (cg/initial-state dependency-text-world)
        {:keys [source-id dependent-id]} (cg/get-library-text-chain (:situation initial))
        accepted (-> initial (cg/step [:library-text-style 0 2 1]) cg/settle-state
                     (cg/step [:library-accept 0]) cg/settle-state
                     (cg/step [:library-sync 0]) cg/settle-state)
        current-id (:id (tm/file (:situation accepted)))
        before (oracle/snapshot (:situation accepted))]
    (doseq [[owner undo redo] [[current-id [:undo] [:redo]]
                               [source-id [:library-undo 1] [:library-redo 1]]
                               [dependent-id [:library-undo 0] [:library-redo 0]]]]
      (t/testing (str owner)
        (let [undone (-> accepted (cg/step undo) cg/settle-state)
              redone (-> undone (cg/step redo) cg/settle-state)
              after (oracle/snapshot (:situation undone))]
          (t/is (not= (get before owner) (get after owner)))
          (t/is (= (dissoc before owner) (dissoc after owner)))
          (t/is (= before (oracle/snapshot (:situation redone)))))))))

(defn- get-invariant
  [situation model]
  (try (oracle/check situation model) nil
       (catch #?(:clj Exception :cljs js/Error) error
         (:invariant (ex-data error)))))

(t/deftest transitive-text-checks-reject-premature-reference-advancement-and-corrupt-results
  (let [initial (cg/initial-state dependency-text-world)
        {:keys [source-id dependent-id source-text-id dependent-head-id dependent-text-id
                consumer-root-ids consumer-text-ids direct-text-ids]}
        (cg/get-library-text-chain (:situation initial))
        current-id (:id (tm/file (:situation initial)))
        original (:content (tm/shape-by-id (tm/with-file (:situation initial)
                                             (get (cf/all-files (:situation initial)) source-id))
                                           source-text-id))
        edited (-> initial (cg/step [:library-text-style 0 2 1]) cg/settle-state)
        upstream (-> edited (cg/step [:library-accept 0]) cg/settle-state)
        accepted (-> upstream (cg/step [:library-sync 0]) cg/settle-state)
        new-content (:content (tm/shape-by-id (tm/with-file (:situation edited)
                                                (get (cf/all-files (:situation edited)) source-id))
                                              source-text-id))]
    (doseq [[state owner root] [[edited dependent-id dependent-head-id]
                                [upstream current-id (first consumer-root-ids)]]]
      (let [situation (:situation state)
            advanced (cf/remember-subtree situation owner root (cf/all-files situation))]
        (t/is (= :I4 (get-invariant advanced (:model state)))
              "a newer reference cannot explain away a stale copy")))
    (doseq [[owner id content] (concat [[dependent-id dependent-text-id original]]
                                       (map #(vector current-id % original) consumer-text-ids)
                                       (map #(vector current-id % new-content) direct-text-ids))]
      (let [situation (:situation accepted)
            files (cf/all-files situation)
            file (get files owner)
            path [:data :pages-index (thf/current-page-id file) :objects id :content]
            corrupted (assoc-in file path content)
            situation (if (= owner current-id)
                        (tm/with-file situation corrupted)
                        (tm/with-aux-files situation (assoc (tm/aux-files situation) owner corrupted)))]
        (t/is (= :I4 (get-invariant situation (:model accepted)))
              "dropped propagation and premature direct acceptance both fail")))))

(t/deftest a-transform-does-not-accept-pending-library-content-or-structure
  (doseq [[edit index] [[[:library-text 0 1 1] 8]
                        [[:library-text 0 0 1] 8]
                        [[:library-edit 0 0 1] 4]
                        [[:library-add-child 0] 4]]]
    (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true :library-text true}]
                         :ops [edit [:transform index 3 3 4] [:undo] [:redo]
                               [:library-sync]]}))
          (str edit))))

(t/deftest source-transforms-keep-consumers-stale-until-acceptance
  (doseq [action (range 6) scope [0 1]]
    (let [initial (cg/initial-state source-world)
          situation (:situation initial)
          tuple [:library-transform 0 action 4 scope]
          resolved (cg/resolve-op situation {} tuple)
          owner (:library-id resolved)]
      (t/is (some? (:op resolved)) (str "source transform " action ", scope " scope))
      (when (:op resolved)
        (let [edited (-> initial (cg/step tuple) cg/settle-state)
              source (get (oracle/snapshot (:situation edited)) owner)]
          (t/is (not= (get (oracle/snapshot situation) owner) source))
          (t/is (= (dissoc (oracle/snapshot situation) owner)
                   (dissoc (oracle/snapshot (:situation edited)) owner)))
          (t/is (zero? (r/undo-depth (:situation edited))))
          (t/is (map? (cg/run {:world source-world
                               :ops [tuple [:library-sync] [:undo] [:redo]
                                     [:library-undo] [:library-redo] [:library-sync]]}))))))))

(t/deftest source-text-edits-cover-letters-and-every-style-unit
  (doseq [tuple (into [[:library-text 0 0 1]]
                      (map #(vector :library-text-style 0 % 1) (range 12)))]
    (let [initial (cg/initial-state source-world)
          situation (:situation initial)
          resolved (cg/resolve-op situation {} tuple)
          owner (:library-id resolved)]
      (t/is (some? (:op resolved)) (str tuple))
      (when (:op resolved)
        (let [edited (-> initial (cg/step tuple) cg/settle-state)]
          (t/is (not= (get (oracle/snapshot situation) owner)
                      (get (oracle/snapshot (:situation edited)) owner)))
          (t/is (not= (thtx/view (:content (:shape resolved)))
                      (thtx/normalize (:value (:op resolved))))
                (str "meaningful source text action " tuple))
          (t/is (= (dissoc (oracle/snapshot situation) owner)
                   (dissoc (oracle/snapshot (:situation edited)) owner)))
          (t/is (zero? (r/undo-depth (:situation edited))))
          (t/is (map? (cg/run {:world source-world
                               :ops [tuple [:library-sync] [:undo] [:redo]
                                     [:library-undo] [:library-redo] [:library-sync]]}))))))))

(t/deftest source-intent-checks-reject-a-dropped-primary-edit
  (doseq [tuple [[:library-transform 0 1 4 0]
                 [:library-transform 0 3 1 1]
                 [:library-text 0 0 1]
                 [:library-text-style 0 2 1]]]
    (let [initial (cg/initial-state source-world)]
      (t/is (= :I9
               (try
                 (with-redefs [r/step (fn [situation _] situation)]
                   (cg/step initial tuple))
                 nil
                 (catch #?(:clj Exception :cljs js/Error) error
                   (:invariant (ex-data error)))))
            (str "dropped source intent " tuple)))))

(t/deftest source-text-fixture-does-not-reuse-consumer-shape-ids
  (let [situation (cg/world source-world)
        entries (cg/forest situation)
        ids (map (comp :id :shape) entries)
        source-texts (for [[_ library] (tm/aux-files situation)
                           entry (cg/tree (tm/with-file situation library))
                           :when (= :text (get-in entry [:shape :type]))]
                       entry)]
    (t/is (= (count ids) (count (set ids))))
    (t/is (seq source-texts))))
