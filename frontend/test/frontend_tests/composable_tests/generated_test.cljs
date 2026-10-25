;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.generated-test
  (:require
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.normalize :as thn]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.test.check.generators :as gen]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.mock :as mock]))

(t/use-fixtures :each ftm/fixture)

(defn- live-model
  [state live]
  (let [by-path (into {} (map (juxt :path (comp :id :shape))) (cg/tree live))]
    (into {}
          (keep (fn [{:keys [path shape]}]
                  (when-let [groups (get (:model state) (:id shape))]
                    (when-let [id (get by-path path)] [id groups]))))
          (cg/tree (:situation state)))))

(defn ^:async replay
  "Replay a JVM-shrunk EDN program against the async frontend. The pure
   runner chooses targets independently and both must make the same choices."
  [{:keys [world ops] :as program}]
  (try
    (let [initial (cg/initial-state world)]
      (loop [state initial
             live (ftm/start-generated (:situation initial))
             tuples ops]
        (if-let [tuple (first tuples)]
          (let [state (cg/settle-state state)
                pure (:situation state)
                live (tm/with-file live (ftm/current-file))
                expected (cg/resolve-op pure (:model state) tuple)
                actual (if (#{:undo :redo} (first tuple))
                         expected
                         (cg/resolve-op live (live-model state live) tuple))]
            (t/is (= (:trace expected) (:trace actual)) (str "Replay changed a target: " tuple))
            (let [next-live (if-let [op (:op actual)]
                              (await (ftm/run-generated-step live op))
                              (do
                                (t/is (= :settled (await (ftm/await-step []))))
                                (tm/with-file live (ftm/current-file))))
                  next-state (-> state (cg/step tuple) (cg/settle-state))]
              (when-not next-live
                (throw (ex-info "Frontend step failed" {:program program :tuple tuple})))
              (try
                (oracle/check next-live (live-model next-state next-live))
                (catch :default error
                  (throw (ex-info (str "Frontend oracle at " tuple ": " (ex-message error))
                                  (ex-data error) error))))
              (let [expected (oracle/canonical (:situation next-state))
                    actual (oracle/canonical next-live)]
                (t/is (true? (= expected actual))
                      (str "Backend mismatch at " tuple "; replay " (pr-str program)
                           "; diff " (pr-str (thn/diff expected actual)))))
              (t/is (empty? (uc/failures next-live)) (pr-str (uc/failures next-live)))
              (recur next-state next-live (rest tuples))))
          (let [checked (await (ftm/finish-generated live))]
            (t/is (empty? (uc/failures checked)) (pr-str (uc/failures checked)))
            (ftm/uninstall!)))))
    (catch :default error
      (ftm/uninstall!)
      (t/is false (str (ex-message error) "\nReplay: " (pr-str program)
                       "\nDetails: " (pr-str (ex-data error)))))))

(t/deftest ^:async seeded-programs-replay-through-the-frontend
  (doseq [seed [712 20261022 1792691]]
    (await (replay (gen/generate cg/program-gen 20 seed)))))

(t/deftest ^:async detach-undo-shrunk-regression
  (await (replay {:world [2 0] :ops [[:edit 0 0 1 1] [:detach 0] [:undo] [:redo]]})))

(t/deftest ^:async structure-programs-replay-through-the-frontend
  (doseq [seed [712 20261022]]
    (await (replay (gen/generate cg/structure-program-gen 10 seed)))))

(t/deftest ^:async nested-text-rotation-shrunk-regression
  (await (replay {:world [2 0 {:text true}]
                  :ops [[:nest 0] [:detach 0] [:rotate 0]]})))

(t/deftest ^:async nesting-restores-default-constraints-on-undo
  (await (replay {:world [2 0] :ops [[:nest 0]]})))

(t/deftest ^:async library-programs-replay-through-the-frontend
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:sync 1]]}))
  (await (replay (gen/generate cg/library-program-gen 5 712))))

(t/deftest ^:async inherited-rotation-switch-shrunk-regression
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:rotate 0 0 0 0] [:switch 1 0 0 0]]})))

(t/deftest ^:async swapped-subtree-update-main-shrunk-regression
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:resize 0 0 0 0] [:duplicate 0 0 0 0]
                        [:switch 2 0 0 0] [:update-main 2 0 0 0]]})))

(t/deftest ^:async main-head-detach-shrunk-regression
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 1 0 0 0] [:detach 0 0 0 0] [:delete 0 0 0 0]]})))

(t/deftest ^:async updating-main-then-swapping-a-child-restores-parent-state
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 5 0 0 0] [:update-main 0 0 0 0] [:swap 13 0 0 0]]})))

(t/deftest ^:async updating-a-duplicated-switched-copy-restores-the-main-order
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 1 0 0 0] [:duplicate 0 0 0 0]
                        [:update-main 0 0 0 0] [:redo 0 0 0 0]]})))

(t/deftest ^:async switching-a-detached-rotated-copy-keeps-its-resize
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:rotate 1 0 0 0] [:detach 1 0 0 0]
                        [:detach 2 0 0 0] [:resize 2 0 0 0] [:switch 2 0 0 0]]})))

(t/deftest ^:async switching-a-copy-of-a-swap-keeps-position-overrides-inherited
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 13 0 0 0] [:rotate 0 0 0 0] [:switch 2 0 0 0]]})))

(t/deftest ^:async pushing-a-swap-keeps-the-orientation-inherited-from-its-position
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:rotate 0 0 0 0] [:swap 13 0 0 0] [:update-main 1 0 0 0]]})))

(t/deftest ^:async pushing-a-switched-child-uses-its-parents-pushed-frame
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 5 0 0 0] [:rotate 1 0 0 0] [:update-main 0 0 0 0]]})))

(t/deftest ^:async generated-library-pushed-swap-authors-content-against-its-new-position
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 2 0 0 0] [:edit 8 0 0 0] [:switch 0 0 0 0]
                        [:update-main 7 0 0 0]]})))

(t/deftest ^:async generated-library-swap-back-syncs-the-near-positions-overrides
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 15 0 0 0] [:duplicate 16 0 0 0] [:edit 8 0 0 0]
                        [:switch 4 0 0 0] [:update-main 0 0 0 0]]})))

(t/deftest ^:async generated-library-move-restores-existing-component-nesting-on-undo
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 5 0 0 0] [:nest 8 0 0 0] [:nest 5 0 0 0]
                        [:restore 0 0 0 0]]})))

(t/deftest ^:async generated-library-authored-child-follows-its-parents-synced-turn
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:resize 0 0 0 0] [:switch 2 0 0 0] [:rotate 1 0 0 0]
                        [:remove-child 0 0 0 0]]})))

(t/deftest ^:async generated-library-switch-keeps-overrides-from-a-deleted-near-main
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:delete 0 0 0 0] [:delete 3 0 0 0] [:resize 0 0 0 0]
                        [:delete 9 0 0 0] [:switch 0 0 0 0]]})))

(t/deftest ^:async generated-library-duplicate-refuses-an-expanding-component-branch
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 5] [:nest 8] [:duplicate 1]]})))

(t/deftest ^:async generated-library-parent-turn-settles-swapped-placement
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 2 0 0 0] [:resize 0 0 0 0] [:rotate 2 0 0 0]]})))

(t/deftest ^:async generated-library-restored-variant-syncs-its-converted-name
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:delete 3 0 0 0] [:delete 1 0 0 0] [:restore 7 0 0 0]
                        [:delete 1 0 0 0] [:restore 0 0 0 0]]})))

(t/deftest ^:async generated-library-switch-inherits-a-swapped-near-mains-authored-fill
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:edit 12 0 0 0] [:nest 10 0 0 0]
                        [:switch 7 0 0 0] [:switch 2 0 0 0]]})))

(t/deftest ^:async generated-library-reset-places-new-children-after-clearing-rotation
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 1 0 0 0] [:rotate 1 0 0 0] [:duplicate 0 0 0 0]
                        [:reset 2 0 0 0] [:redo 0 0 0 0]]})))
