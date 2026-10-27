;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.source-gen-test
  (:require
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.ids-map :as thi]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def source-world
  [2 1 {:library true :stale-libraries true :library-text true :text true :text-styles true}])

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
