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
  (let [by-path (into {} (map (juxt :path (comp :id :shape))) (cg/forest live))]
    (into {}
          (keep (fn [{:keys [path shape]}]
                  (when-let [groups (get (:model state) (:id shape))]
                    (when-let [id (get by-path path)] [id groups]))))
          (cg/forest (:situation state)))))

(defn- ^:async replay-program
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
                live (ftm/refresh-generated live)
                expected (cg/resolve-op pure (:model state) tuple)
                actual (if (#{:undo :redo} (first tuple))
                         expected
                         (cg/resolve-op live (live-model state live) tuple))]
            (t/is (= (:trace expected) (:trace actual)) (str "Replay changed a target: " tuple))
            (let [next-live (if-let [op (:op actual)]
                              (await (ftm/run-generated-step live op))
                              (do
                                (t/is (= :settled (await (ftm/await-step []))))
                                (ftm/refresh-generated live)))
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
                (t/is (true? (oracle/equivalent? expected actual))
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

(defn ^:async replay
  [program]
  (await (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock
                            rx/timer mock/timer-mock}
           (await (replay-program program)))))

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

(t/deftest ^:async swap-programs-replay-through-the-frontend
  (doseq [seed [712 20261022]]
    (await (replay (gen/generate cg/swap-program-gen 5 seed)))))

(t/deftest ^:async partial-sync-geometry-boundary-shrunk-regression
  (await (replay {:world [2 0 {:text true :layout nil}]
                  :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                        [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                        [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                        [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                        [:detach 0] [:undo] [:redo] [:undo 0 0 0 0]
                        [:add-child 17 0 0 0] [:rotate 19 0 0 0] [:undo 0 0 0 0]]})))

(t/deftest ^:async update-main-after-nesting-a-rotated-text-copy-undoes-together
  (await (replay {:world [2 0 {:text true}]
                  :ops [[:duplicate 0] [:nest 0] [:rotate 0 0 0]
                        [:edit 0 0 1 1] [:update-main 0]]})))

(t/deftest ^:async swapping-main-subtrees-with-several-children-undoes-their-order
  (await (replay {:world [2 2 {:variants true :variant-diff false}]
                  :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                        [:undo] [:redo] [:reset 0] [:edit 0 1 1 1]
                        [:update-main 0] [:sync 1] [:add-child 1 0 0 0]
                        [:add-child 1 0 0 0] [:add-child 1 0 0 0]
                        [:swap 0 0 0 0] [:swap 0 0 0 0] [:text 0 0 0 0]]})))

(t/deftest ^:async updating-a-rotated-outer-main-composes-authored-child-orientation
  (await (replay {:world [2 0 {:text true :layout nil}]
                  :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                        [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                        [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                        [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                        [:detach 0] [:undo] [:redo] [:duplicate 0 0 0 0]
                        [:resize 0 0 0 0] [:nest 0 0 0 0] [:rotate 4 0 0 0]
                        [:detach 3 0 0 0] [:update-main 4 0 0 0]]})))

(t/deftest ^:async minimum-size-parent-orientation-does-not-amplify-oracle-drift
  (await (replay {:world [3 0 {:text true :layout nil}]
                  :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                        [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                        [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                        [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                        [:detach 0] [:undo] [:redo] [:edit 4 0 0 0]
                        [:duplicate 3 0 0 0] [:nest 10 0 0 0] [:rotate 9 0 0 0]
                        [:update-main 7 0 0 0] [:delete 0 0 0 0]]})))

(t/deftest ^:async detaching-a-rotated-parent-inherits-geometry-in-the-copy-frame
  (await (replay {:world [2 0 {:text true :layout nil}]
                  :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                        [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                        [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                        [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                        [:detach 0] [:undo] [:redo] [:resize 0 0 0 0]
                        [:delete 0 0 0 0] [:rotate 0 0 0 0] [:detach 0 0 0 0]]})))

(t/deftest ^:async rotated-swapped-head-keeps-its-position-anchor-with-a-different-content-size
  (await (replay {:world [2 2 {:variants true :variant-diff false}]
                  :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                        [:undo] [:redo] [:reset 0] [:edit 0 1 1 1] [:update-main 0]
                        [:sync 1] [:swap 17 0 0 0] [:resize 0 0 0 0] [:rotate 0 0 0 0]]})))

(t/deftest ^:async resizing-a-copy-of-an-empty-flex-main-keeps-layout-valid
  (await (replay {:world [3 0 {:text true :layout :flex}]
                  :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                        [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                        [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                        [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                        [:detach 0] [:undo] [:redo] [:remove-child 0 0 0 0]
                        [:reset 0 0 0 0] [:nest 0 0 0 0] [:resize 0 0 0 0]]})))

(t/deftest ^:async updating-a-main-after-successive-rotations-keeps-child-flips
  (await (replay {:world [2 1]
                  :ops [[:rotate 1 0 0 0] [:rotate 1 0 1 0] [:update-main 0 0 0 0]]})))

(t/deftest ^:async minimum-size-parent-placement-keeps-fixed-child-dimensions
  (await (replay {:world [2 0 {:text true :layout nil}]
                  :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                        [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                        [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                        [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                        [:detach 0] [:undo] [:redo] [:resize 1 0 0 0]
                        [:nest 2 0 0 0] [:rotate 0 0 3 0] [:edit 0 0 0 0]]})))

(t/deftest ^:async switching-an-untouched-rotated-copy-survives-full-sync
  (await (replay {:world [3 2 {:variants true :variant-diff false}]
                  :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                        [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                        [:edit 11 0 0 0] [:rotate 2 0 0 0] [:nest 3 0 0 0] [:resize 3 0 0 0]
                        [:nest 3 0 0 0] [:swap 10 0 0 0] [:resize 3 0 0 0]
                        [:update-main 5 0 0 0] [:switch 0 0 0 0] [:detach 0 0 0 0]]})))

(t/deftest ^:async switch-keeps-retained-orientation-together-with-carried-size
  (await (replay {:world [2 2 {:variants true :variant-diff false}]
                  :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                        [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                        [:swap 0 0 0 0] [:delete 0 0 0 0] [:rotate 0 0 0 0]
                        [:nest 0 0 0 0] [:detach 0 0 0 0] [:update-main 0 0 0 0]
                        [:switch 0 0 0 0]]})))

(t/deftest ^:async generated-update-main-settles-a-turned-swapped-position
  (await (replay {:world [2 2 {:variants true :variant-diff false}]
                  :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                        [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                        [:delete 8 0 0 0] [:swap 15 0 0 0] [:edit 14 0 0 0]
                        [:resize 4 0 0 0] [:resize 0 0 0 0] [:nest 4 0 0 0]
                        [:rotate 5 0 0 0] [:update-main 14 0 0 0] [:duplicate 0 0 0 0]]})))
