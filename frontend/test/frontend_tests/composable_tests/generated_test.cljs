;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.generated-test
  (:require
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.grid :as grid]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.normalize :as thn]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.test.check.generators :as gen]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.mock :as mock]))

(t/use-fixtures :each ftm/fixture)

(defn- live-model
  [state live]
  (let [by-path (into {} (map (juxt :path (comp :id :shape))) (cg/forest live))
        ids-map (into {} (keep (fn [{:keys [path shape]}]
                                 (when-let [id (get by-path path)] [(:id shape) id])))
                      (cg/forest (:situation state)))]
    (into {}
          (keep (fn [{:keys [path shape]}]
                  (when-let [groups (get (:model state) (:id shape))]
                    (when-let [id (get by-path path)] [id (grid/remap-groups groups ids-map)]))))
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
                actual (if (#{:undo :redo :library-undo :library-redo} (first tuple))
                         expected
                         (cg/resolve-op live (live-model state live) tuple))]
            (t/is (= (:trace expected) (:trace actual)) (str "Replay changed a target: " tuple))
            (let [next-live (try (if-let [op (:op actual)]
                                   (if-let [owner (:library-id actual)]
                                     (await (ftm/run-generated-source-step live owner op))
                                     (await (ftm/run-generated-step live op)))
                                   (do
                                     (t/is (= :settled (await (ftm/await-step []))))
                                     (ftm/refresh-generated live)))
                                 (catch :default error
                                   (throw (ex-info (ex-message error)
                                                   (assoc (ex-data error) :replay-tuple tuple) error))))
                  next-state (-> state (cg/step tuple) (cg/settle-state))]
              (when-not next-live
                (throw (ex-info "Frontend step failed" {:program program :tuple tuple})))
              (when (and (:op actual) (= :update-main (first tuple)))
                (oracle/check-requested-update-main live next-live (live-model state live)
                                                    (get-in actual [:shape :id])))
              (when (and (:op actual) (#{:resize :rotate :transform} (first tuple)))
                (oracle/check-requested-geometry
                 (:objects (cthf/current-page (tm/file next-live)))
                 (tm/shape-by-id next-live (get-in actual [:shape :id]))
                 (cg/requested-geometry live actual (first tuple))))
              (when (and (:op actual) (#{:grid-cell :grid-direction} (first tuple)))
                (oracle/check-requested-grid
                 (tm/shape-by-id next-live (get-in actual [:shape :id])) (:shape actual)
                 (get-in actual [:op :action]) (get-in actual [:op :value])))
              (try
                (oracle/check next-live (live-model next-state next-live))
                (catch :default error
                  (throw (ex-info (str "Frontend oracle at " tuple ": " (ex-message error))
                                  (assoc (ex-data error) :replay-tuple tuple) error))))
              (let [parity {:relative-free-head-placement true}
                    expected (oracle/canonical (:situation next-state) parity)
                    actual (oracle/canonical next-live parity)]
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
      (throw error))))

(defn ^:async replay
  [program]
  (await (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock
                            rx/timer mock/timer-mock}
           (try (await (replay-program program))
                (catch :default error
                  (t/is false (str (ex-message error) "\nReplay: " (pr-str program)
                                   "\nDetails: " (pr-str (ex-data error)))))))))

(defn- ^:async replay-library-grid-with-source-control
  [program]
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (t/is (map? (cg/run program)))
     ;; The same source edits are valid when the source owns the editor.
     (let [situation (cg/world (:world program))
           files     (cf/all-files situation)
           owner     (first (keys (tm/aux-files situation)))
           parent-id (get-in (cg/resolve-op situation {} [:library-add-child 0]) [:shape :id])
           editor    (-> situation (tm/with-file (get files owner)) (tm/with-aux-files (dissoc files owner)))
           initial   (ftm/start-generated editor)
           before    (ftm/snapshot)
           first     (await (ftm/run-generated-step initial (n/add-child (constantly parent-id) :source-a :width 20 :height 20)))
           second    (await (ftm/run-generated-step first (n/add-child (constantly parent-id) :source-b :width 20 :height 20)))
           shape     (tm/shape-by-id second parent-id)]
       (t/is (= 5 (count (:shapes shape))))
       (t/is (= [2 3] (mapv #(count (get shape %)) [:layout-grid-rows :layout-grid-columns])))
       (t/is (= (dissoc (:files before) owner) (dissoc (:files (ftm/snapshot)) owner)))
       (t/is (empty? (uc/failures (await (ftm/finish-generated second)))))
       (ftm/uninstall!))
     (await (replay-program program)))))

(def update-main-intent-programs
  [{:world [1 0] :ops [[:edit 0 0 1 1] [:update-main 0] [:undo] [:redo]]}
   {:world [1 0 {:library true}] :ops [[:edit 0 0 1 2] [:update-main 0] [:undo] [:redo]]}
   {:world [1 0 {:layout :grid :layout-children true :grid-square true}]
    :ops [[:grid-cell 0 0 0 1] [:update-main 0] [:undo] [:redo]]}
   {:world [1 0 {:library true :library-depth 0 :library-grid true}]
    :ops [[:grid-cell 0 0 0 2] [:update-main 0] [:undo] [:redo]]}
   {:world [1 0 {:text true :text-styles true}]
    :ops [[:text-style 1 2 1] [:update-main 0] [:undo] [:redo]]}
   {:world [1 0] :ops [[:transform 0 3 2 4] [:update-main 0] [:undo] [:redo]]}])

(t/deftest ^:async update-main-preserves-authored-intent-in-the-destination
  (doseq [program update-main-intent-programs]
    (await (replay program))))

(t/deftest ^:async update-main-intent-rejects-a-reset-dispatched-by-the-editor
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (doseq [{:keys [world ops]} update-main-intent-programs]
       (let [state   (cg/initial-state world)
             live    (ftm/start-generated (:situation state))
             edit    (first ops)
             edited  (await (ftm/run-generated-step live (:op (cg/resolve-op live {} edit))))
             state   (-> state (cg/step edit) cg/settle-state)
             model   (live-model state edited)
             request (cg/resolve-op edited model [:update-main 0])
             result  (await (mock/with-mocks* {dwl/update-component-sync (fn [id _] (dwl/reset-component id))}
                              (await (ftm/run-generated-step edited (:op request)))))
             settled (try (oracle/check result {}) nil
                          (catch :default error (ex-data error)))
             failure (try (oracle/check-requested-update-main edited result model (get-in request [:shape :id])) nil
                          (catch :default error (ex-data error)))]
         (t/is (nil? settled) "The wrong main and every copy agree")
         (t/is (= :I9 (:invariant failure)) (pr-str failure))
         (t/is (= :requested-update-main (:phase failure)) (pr-str failure))
         (let [checked (await (ftm/finish-generated result))]
           (t/is (empty? (uc/failures checked)) (pr-str (uc/failures checked))))
         (ftm/uninstall!)
         (let [failure (await (mock/with-mocks* {dwl/update-component-sync (fn [id _] (dwl/reset-component id))}
                                (try (await (replay-program {:world world :ops ops})) nil
                                     (catch :default error (ex-data error)))))]
           (t/is (= :I9 (:invariant failure)) (pr-str failure))
           (t/is (= :requested-update-main (:phase failure)) (pr-str failure))))))))

(t/deftest ^:async empty-grid-direction-edits-replay-through-the-frontend
  (doseq [depth [0 1]
          scope [nil 1]]
    (await (replay {:world [1 depth {:layout :grid :grid-empty true}]
                    :ops [[:grid-cell 0 0 0 scope] [:grid-direction 0 0 1 scope]
                          [:undo] [:redo]]}))))

(t/deftest ^:async empty-grid-direction-histories-replay-through-the-frontend
  (doseq [depth [0 1]
          tail [[[:grid-direction 0 0 0 1]] [[:update-main 0]] [[:reset 0]]
                [[:duplicate 0]] [[:detach 0]] [[:add-child 0]]]]
    (await (replay {:world [1 depth {:layout :grid :grid-empty true}]
                    :ops (into [[:grid-direction 0 0 1 1]] (concat tail [[:sync] [:undo] [:redo]]))}))))

(t/deftest ^:async grid-growth-replays-with-independent-cell-expectations
  (doseq [direction [:row :column]
          turn-copy? [false true]]
    (await (replay {:world [1 0 {:layout :grid :layout-children true :grid-square true}]
                    :ops (vec (concat (when (= direction :column) [[:grid-direction 0 0 1 0]])
                                      [[:grid-cell 0 0 0 1]]
                                      (when turn-copy? [[:grid-direction 0 0 (if (= direction :row) 1 0) 1]])
                                      [[:add-child 0] [:add-child 0] [:sync] [:undo] [:redo]]))}))))

(t/deftest ^:async grid-growth-context-replays-through-structural-and-library-operations
  (doseq [program
          (concat
           (for [tail [[[:duplicate 0] [:undo] [:redo]] [[:update-main 0] [:undo] [:redo]]
                       [[:reset 0] [:undo] [:redo]] [[:detach 0] [:undo] [:redo]]]]
             {:world [1 0 {:layout :grid :layout-children true :grid-square true}]
              :ops (into [[:grid-cell 0 0 0 1] [:add-child 0] [:add-child 0] [:sync 1]] tail)})
           [{:world [1 1 {:layout :grid :layout-children true :grid-square true}]
             :ops [[:grid-cell 0 0 0 1] [:add-child 0] [:add-child 0] [:sync 1] [:detach 0] [:undo] [:redo]]}
            {:world [1 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
             :ops [[:grid-cell 0 0 0 2] [:library-add-child 0] [:library-add-child 0]
                   [:edit 0 1 1 2] [:library-sync] [:undo] [:redo]]}])]
    (if (get-in program [:world 2 :stale-libraries])
      (await (replay-library-grid-with-source-control program))
      (await (replay program)))))

(t/deftest ^:async source-grid-additions-replay-without-accepting-the-library
  (await (replay {:world [1 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                  :ops [[:grid-cell 0 0 0 2] [:library-add-child 0] [:library-add-child 0]
                        [:edit 0 1 1 2] [:undo] [:redo]]})))

(t/deftest ^:async accepting-source-grid-growth-keeps-oracle-references-through-undo
  (await (replay {:world [1 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                  :ops [[:grid-cell 0 0 0 2] [:library-add-child 0] [:library-add-child 0]
                        [:edit 0 1 1 2] [:library-sync] [:undo] [:redo]]})))

(t/deftest ^:async accepted-reference-history-keeps-newer-loaded-sources-independent
  (doseq [ops [[[:library-add-child 0] [:library-sync] [:library-add-child 0]
                [:library-sync] [:undo] [:undo] [:redo] [:redo]]
               [[:library-add-child 0] [:library-sync] [:edit 0 1 1 2]
                [:undo] [:undo] [:redo] [:redo]]
               [[:library-edit 0 0 1] [:library-sync] [:library-edit 0 0 2]
                [:undo] [:redo]]]]
    (await (replay {:world [0 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                    :ops ops}))))

(t/deftest ^:async accepted-source-child-undo-keeps-backend-parity
  (await (replay {:world [0 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                  :ops [[:library-add-child 0] [:library-add-child 0] [:library-sync]
                        [:library-undo 0] [:undo] [:library-redo 0] [:redo]]})))

(t/deftest ^:async transitive-library-acceptance-keeps-each-editors-reference-history
  (await (replay {:world [0 0 {:library true :library-depth 0 :library-dependency true :stale-libraries true}]
                  :ops [[:library-edit 1 0 1] [:library-sync 0] [:library-accept 0]
                        [:library-sync 0] [:library-undo 0] [:undo]
                        [:library-redo 0] [:redo]]})))

(t/deftest ^:async a-source-undo-retains-the-consumers-accepted-child-reference
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (let [world    [0 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
           ops      [[:library-add-child 0] [:library-add-child 0] [:library-sync]]
           initial  (cg/world world)
           resolved (cg/resolve-op initial {} (first ops))
           owner    (:library-id resolved)
           main-id  (get-in resolved [:shape :id])
           live     (loop [live (ftm/start-generated initial) tuples ops]
                      (if-let [tuple (first tuples)]
                        (let [{:keys [op library-id]} (cg/resolve-op live {} tuple)]
                          (recur (if library-id
                                   (await (ftm/run-generated-source-step live library-id op))
                                   (await (ftm/run-generated-step live op)))
                                 (rest tuples)))
                        live))
           source   (get (cf/all-files live) owner)
           ids      (:shapes (tm/shape-by-id (tm/with-file live source) main-id))
           before   (tm/file live)
           live     (await (ftm/run-generated-source-step live owner (n/undo)))
           source   (get (cf/all-files live) owner)
           current  (:shapes (tm/shape-by-id (tm/with-file live source) main-id))]
       (t/is (= 5 (count ids)))
       (t/is (= 4 (count current)))
       (t/is (= before (tm/file live)))
       (doseq [{:keys [shape]} (cg/tree live) :when (= main-id (:shape-ref shape))]
         (let [accepted (get (cf/reference-libraries live shape) owner)]
           (t/is (= ids (:shapes (tm/shape-by-id (tm/with-file live accepted) main-id))))))
       (t/is (nil? (oracle/check live {})))
       (t/is (= [] (vec (for [file (vals (oracle/canonical live))
                              objects (cf/object-trees file)
                              shape (vals objects)
                              :when (uuid? (:shape-ref shape))]
                          (:shape-ref shape)))))
       (t/is (empty? (uc/failures (await (ftm/finish-generated live)))))
       (ftm/uninstall!)))))

(t/deftest ^:async source-history-checkpoints-legitimate-consumer-writes
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (t/is (map? (cg/run {:world [0 0 {:library true :library-depth 0 :stale-libraries true}]
                          :ops [[:library-edit 0 0 1] [:library-sync] [:edit 0 1 1 2]
                                [:update-main 0] [:library-edit 0 0 2]]})))
     (let [initial (cg/world [0 0 {:library true :library-depth 0 :stale-libraries true}])
           edit    (cg/resolve-op initial {} [:library-edit 0 0 1])
           owner   (:library-id edit)
           child   (get-in edit [:shape :id])
           main    (get-in edit [:shape :parent-id])
           copy    (get-in (cg/resolve-op initial {} [:edit 0 1 1 2]) [:shape :id])
           page-id (cthf/current-page-id (get (cf/all-files initial) owner))
           live    (ftm/start-generated initial)
           live    (await (ftm/run-generated-source-step live owner (:op edit)))
           entry   (get-in live [::ftm/source-editors owner :undo :items 0])
           live    (await (ftm/run-generated-step live (n/sync-from-library owner)))
           live    (await (ftm/run-generated-step live (n/change-property (constantly copy) :opacity 0.4)))
           live    (await (ftm/run-generated-step live (n/update-main (constantly copy))))
           source  (get (cf/all-files live) owner)
           _       (t/is (= 0.4 (:opacity (tm/shape-by-id (tm/with-file live source) main))))
           live    (await (ftm/run-generated-source-step live owner
                                                         (n/change-property (constantly child) :fills "#00ff00")))
           editor  (get-in live [::ftm/source-editors owner])
           base    (uc/baseline (:situation editor))
           source  (get (cf/all-files live) owner)]
       (t/is (= "#00ff00" (get-in (tm/shape-by-id (tm/with-file live source) child) [:fills 0 :fill-color])))
       (t/is (= 0.4 (:opacity (tm/shape-by-id (tm/with-file live source) main))))
       (t/is (= entry (get-in editor [:undo :items 0])))
       (t/is (= 2 (count (get-in editor [:undo :items]))))
       (t/is (= 0 (:depth base)))
       (t/is (= 0.4 (get-in base [:snapshot :files owner :data :pages-index page-id :objects main :opacity])))
       (t/is (= "#ff0000" (get-in base [:snapshot :files owner :data :pages-index page-id :objects child :fills 0 :fill-color])))
       (t/is (empty? (uc/failures (await (ftm/finish-generated live)))))
       (ftm/uninstall!)))))

(t/deftest ^:async source-history-checkpoints-preserve-no-ops-and-older-entries
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (let [initial (cg/world [0 0 {:library true :library-depth 0 :stale-libraries true}])
           edit    (cg/resolve-op initial {} [:library-edit 0 0 1])
           owner   (:library-id edit)
           child   (get-in edit [:shape :id])
           main    (get-in edit [:shape :parent-id])
           copy    (get-in (cg/resolve-op initial {} [:edit 0 1 1 2]) [:shape :id])
           live    (ftm/start-generated initial)
           live    (await (ftm/run-generated-source-step live owner (:op edit)))
           live    (await (ftm/run-generated-source-step live owner
                                                         (n/change-property (constantly child) :fills "#00ff00")))
           editor  (get-in live [::ftm/source-editors owner])
           _       (t/is (= -1 (:depth (uc/baseline (:situation editor)))))
           entries (:items (:undo editor))
           live    (await (ftm/run-generated-step live (n/sync-from-library owner)))
           live    (await (ftm/run-generated-step live (n/change-property (constantly copy) :opacity 0.4)))
           live    (await (ftm/run-generated-step live (n/update-main (constantly copy))))
           base    (uc/baseline live)
           live    (await (ftm/run-generated-source-step live owner
                                                         (n/change-property (constantly child) :fills "#00ff00")))
           editor  (get-in live [::ftm/source-editors owner])]
       (t/is (= base (uc/baseline live)))
       (t/is (= 1 (:depth (uc/baseline (:situation editor)))))
       (t/is (= entries (:items (:undo editor))))
       (t/is (empty? (uc/failures (await (ftm/finish-generated live)))))
       (let [live (loop [live live steps [[(n/undo) "#ff0000"] [(n/undo) "#abcdef"]
                                          [(n/redo) "#ff0000"] [(n/redo) "#00ff00"]]]
                    (if-let [[op fill] (first steps)]
                      (let [live   (await (ftm/run-generated-source-step live owner op))
                            source (tm/with-file live (get (cf/all-files live) owner))]
                        (t/is (= fill (get-in (tm/shape-by-id source child) [:fills 0 :fill-color])))
                        (t/is (= 0.4 (:opacity (tm/shape-by-id source main))))
                        (recur live (rest steps)))
                      live))
             live (await (ftm/run-generated-step live (n/undo)))
             _    (t/is (nil? (:opacity (tm/shape-by-id (tm/with-file live (get (cf/all-files live) owner)) main))))
             live (await (ftm/run-generated-step live (n/redo)))
             file (tm/with-file live (get (cf/all-files live) owner))]
         (t/is (= 0.4 (:opacity (tm/shape-by-id file main))))
         (t/is (= "#00ff00" (get-in (tm/shape-by-id file child) [:fills 0 :fill-color])))
         (t/is (empty? (uc/failures (await (ftm/finish-generated live))))))
       (ftm/uninstall!)))))

(t/deftest ^:async source-handoff-reports-and-retains-a-broken-consumer-segment
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (let [initial (cg/world [0 0 {:library true :library-depth 0 :stale-libraries true}])
           edit    (cg/resolve-op initial {} [:library-edit 0 0 1])
           owner   (:library-id edit)
           copy    (get-in (cg/resolve-op initial {} [:edit 0 1 1 2]) [:shape :id])
           file-id (:id (tm/file initial))
           page-id (cthf/current-page-id (tm/file initial))
           live    (ftm/start-generated initial)
           live    (await (ftm/run-generated-step live (n/change-property (constantly copy) :opacity 0.4)))
           base    (uc/baseline live)
           ;; Corrupt the expected pre-edit state. Handoff must report this
           ;; whole-segment failure before a source edit advances the baseline.
           live    (uc/mark-baseline live (:depth base)
                                     (assoc-in (:snapshot base)
                                               [:files file-id :data :pages-index page-id :objects copy :opacity] 0.9))
           reports (atom [])
           live    (await (mock/with-mocks* {t/report #(swap! reports conj %)}
                            (ftm/run-generated-source-step live owner (:op edit))))
           failed  (filter #(= :fail (:type %)) @reports)
           errors  (filter #(= :error (:type %)) @reports)]
       (t/is (= 1 (count failed)))
       (t/is (empty? errors))
       (t/is (= [:variant-undo] (mapv :phase (uc/failures live))))
       (t/is (= #{[:file :file1 :object :random-library-copy-0-0 :opacity]}
                (:signature (first (uc/failures live)))))
       (t/is (= 0 (:depth (uc/baseline live))))
       (ftm/uninstall!)))))

(t/deftest ^:async source-editors-retain-their-own-undo-and-redo-histories
  (doseq [ops [[[:library-add-child 0] [:library-add-child 0] [:library-undo 0]
                [:edit 0 1 1 2] [:library-redo 0] [:undo] [:redo]]
               [[:edit 0 1 1 2] [:library-edit 0 0 1] [:undo]
                [:library-undo 0] [:redo] [:library-redo 0]]
               [[:library-transform 0 0 1 0] [:edit 0 1 1 2]
                [:library-undo 0] [:undo] [:library-redo 0] [:redo]]]]
    (await (replay {:world [1 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                    :ops ops}))))

(t/deftest ^:async two-source-editors-keep-their-histories-separate
  (await (replay {:world [1 0 {:library true :library-depth 0 :library-dependency true :stale-libraries true}]
                  :ops [[:library-edit 0 0 1] [:library-edit 1 0 2]
                        [:library-undo 0] [:library-undo 1]
                        [:library-redo 0] [:library-redo 1]]})))

(t/deftest ^:async source-grid-acceptance-preserves-authored-cells-and-undo
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (let [initial (cg/world [1 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}])
           edit    (cg/resolve-op initial {} [:grid-cell 0 0 0 2])
           id      (get-in edit [:shape :id])
           props   (get-in edit [:op :value :props])
           live    (ftm/start-generated initial)
           live    (await (ftm/run-generated-step live (:op edit)))
           live    (loop [live live tuples [[:library-add-child 0] [:library-add-child 0] [:edit 0 1 1 2]]]
                     (if-let [tuple (first tuples)]
                       (let [{:keys [op library-id]} (cg/resolve-op live {} tuple)
                             after (if library-id
                                     (await (ftm/run-generated-source-step live library-id op))
                                     (await (ftm/run-generated-step live op)))]
                         (recur after (rest tuples)))
                       live))
           live    (await (ftm/run-generated-step live (:op (cg/resolve-op live {} [:library-sync]))))
           shape   (tm/shape-by-id live id)
           cell    (first (filter #(= [1 1] [(:row %) (:column %)]) (vals (:layout-grid-cells shape))))]
       (t/is (some? (cthf/validate-file! (tm/file live) (cf/libraries live))))
       (t/is (= 5 (count (:shapes shape))))
       (t/is (= [2 3] (mapv #(count (get shape %)) [:layout-grid-rows :layout-grid-columns])))
       (t/is (= props (select-keys cell (keys props))))
       (t/is (empty? (uc/failures (await (ftm/finish-generated live)))))
       (ftm/uninstall!)))))

(t/deftest ^:async authored-grid-histories-use-independent-cell-expectations
  (doseq [ops [[[:grid-direction 0 0 1 1] [:grid-direction 0 0 1 0]
                [:grid-direction 0 0 0 0] [:update-main 0]
                [:grid-direction 0 0 0 1] [:reset 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:grid-cell 0 1 1 0] [:grid-cell 0 0 2 0]
                [:update-main 0] [:grid-cell 0 1 2 1] [:reset 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:duplicate 0] [:undo] [:redo] [:update-main 0]]
               [[:grid-direction 0 0 1 1] [:add-child 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:remove-child 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:detach 0] [:undo] [:redo]]]]
    (await (replay {:world [1 0 {:layout :grid :layout-children true :grid-square true}] :ops ops}))))

(t/deftest ^:async detaching-an-outer-copy-remaps-inherited-grid-cell-intent
  (await (replay {:world [1 1 {:layout :grid :layout-children true :grid-square true}]
                  :ops [[:grid-cell 0 0 0 1] [:detach 0] [:undo] [:redo]]})))

(t/deftest ^:async layout-container-histories-survive-component-operations
  (doseq [[property layouts] [[20 [:flex]] [21 [:flex]] [22 [:flex :grid]] [23 [:flex :grid]]
                              [24 [:flex :grid]] [25 [:flex :grid]] [26 [:grid]]]
          layout layouts]
    (await (replay {:world [1 0 {:layout layout :layout-children true}]
                    :ops [[:style 0 property 1 1] [:style 0 property 1 0]
                          [:style 0 property 0 0] [:undo] [:redo] [:update-main 0]
                          [:style 0 property 0 1] [:reset 0]
                          [:style 0 property 0 1] [:detach 0] [:undo] [:redo]]}))))

(t/deftest ^:async layout-item-histories-survive-component-operations
  (doseq [property (range 27 37)
          layout (if (= property 28) [:flex] [:flex :grid])]
    (let [authored (if (= property 27) 0 1)
          other    (if (= property 27) 1 0)]
      (await (replay {:world [1 0 {:layout layout :layout-children true :layout-item-margins true}]
                      :ops [[:style 0 property authored 1] [:style 0 property authored 0]
                            [:style 0 property other 0] [:undo] [:redo] [:update-main 0]
                            [:style 0 property other 1] [:reset 0]
                            [:style 0 property other 1] [:detach 0] [:undo] [:redo]]})))))

(t/deftest ^:async adding-a-flex-child-before-making-a-copy-child-absolute-stays-settled
  (await (replay {:world [1 0 {:layout :flex :layout-children true}]
                  :ops [[:add-child 0] [:style 2 35 1 1] [:undo] [:redo]]})))

(t/deftest ^:async nesting-a-copy-alongside-a-hidden-layout-child-stays-settled
  (await (replay {:world [3 0 {:text true :text-styles true :layout :flex}]
                  :ops [[:delete 9 9 1 2] [:text 5 9 1 3] [:style 3 1 1]
                        [:restore 5 8 1 6] [:nest 5 6 0 2] [:undo] [:redo]]})))

(t/deftest ^:async generated-effects-and-corners-survive-component-operations
  (doseq [property (range 14 20)]
    (await (replay {:world [2 1 {:variants true :rich-variants true}]
                    :ops [[:style 0 property 1 1] [:style 1 property 1 0]
                          [:undo] [:redo] [:update-main 0]
                          [:style 0 property 0 1] [:reset 0]
                          [:style 0 property 1 1] [:detach 0]
                          [:undo] [:redo]]}))))

(t/deftest ^:async swapping-a-copy-with-authored-position-constraints-stays-settled
  (await (replay {:world [2 1 {:library true :variants true :rich-variants true
                               :text true :text-styles true :layout :flex :layout-copies true}]
                  :ops [[:style 12 9 11 6] [:delete 10 14 10 12] [:swap 17 2 14 5]
                        [:undo] [:redo]]})))

(t/deftest ^:async accepting-a-library-does-not-record-equal-numeric-geometry
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:resize 10 2 2 10] [:library-sync 3 1 2 8]
                        [:undo] [:redo]]})))

(t/deftest ^:async nesting-a-swapped-copy-in-a-blocked-main-authors-its-lock
  (await (replay {:world [2 0 {:library true :variants true :rich-variants true
                               :text true :text-styles true :layout nil :layout-copies true}]
                  :ops [[:style 3 2 1] [:swap 0 0 0 0] [:nest 0 0 0 0]
                        [:undo] [:redo]]})))

(t/deftest ^:async detaching-a-rotated-switched-copy-keeps-nested-placement-stable
  (await (replay {:world [2 0 {:library true :variants true :rich-variants true
                               :text true :text-styles true :layout nil :layout-copies true}]
                  :ops [[:duplicate 1 0 0 0] [:duplicate 1 0 0 0] [:switch 3 0 0 0]
                        [:rotate 5 0 0 0] [:detach 5 0 0 0]
                        [:undo] [:undo] [:undo] [:undo] [:undo]
                        [:redo] [:redo] [:redo] [:redo] [:redo]]})))

(t/deftest ^:async geometry-actions-replay-on-mains-and-copy-descendants
  (doseq [action (range 6) scope (range 5)]
    (await (replay {:world [2 2]
                    :ops [[:transform 0 action 3 scope] [:undo] [:redo]]}))))

(t/deftest ^:async text-style-units-replay-through-editor-commits
  (doseq [unit (range 12)]
    (await (replay {:world [2 1 {:text true :text-styles true}]
                    :ops [[:text-style 1 unit 1] [:undo] [:redo]]}))))

(t/deftest ^:async reflecting-a-rotated-copy-settles-descendant-flips
  (await (replay {:world [2 1 {:text true :layout :grid}]
                  :ops [[:rotate 1] [:transform 2 16 0 9]]})))

(t/deftest ^:async swapping-after-a-resized-variant-keeps-child-placement
  (await (replay {:world [2 2 {:variants true :variant-diff false :rich-variants true
                               :text true :layout nil :layout-copies true}]
                  :ops [[:resize 0] [:switch 0] [:swap 0]]})))

(t/deftest ^:async switching-after-a-main-move-and-child-removal-stays-settled
  (await (replay {:world [2 0 {:library true :variants true :rich-variants true :text true
                               :layout :flex :layout-copies true}]
                  :ops [[:delete 0] [:restore 0] [:transform 4 0 0 0] [:remove-child 8]
                        [:style 5 7 0 0] [:switch 4] [:rotate 0 2 0 0]]})))

(t/deftest ^:async reflecting-a-rotated-nested-copy-settles-descendant-flips
  (await (replay {:world [2 1 {:text true :layout :grid}]
                  :ops [[:nest 11] [:rotate 1] [:transform 2 16 0 18]]})))

(t/deftest ^:async detach-keeps-an-authored-swap-inside-a-surviving-copy
  (await (replay {:world [2 2 {:variants true :variant-diff false}]
                  :ops [[:switch 10] [:switch 0] [:detach 2]]})))

(t/deftest ^:async seeded-programs-replay-through-the-frontend
  (doseq [seed [712 20261022 1792691]]
    (await (replay (gen/generate cg/program-gen 20 seed)))))

(t/deftest ^:async detach-undo-shrunk-regression
  (await (replay {:world [2 0] :ops [[:edit 0 0 1 1] [:detach 0] [:undo] [:redo]]})))

(t/deftest ^:async structure-programs-replay-through-the-frontend
  (doseq [seed [712 20261022]]
    (await (replay (gen/generate cg/structure-program-gen 10 seed)))))

(t/deftest ^:async moving-a-copy-child-uses-its-own-pre-edit-frame
  (await (replay {:world [2 1]
                  :ops [[:transform 0 3 0 2] [:transform 0 0 2 2] [:undo] [:redo]]})))

(t/deftest ^:async nested-text-rotation-shrunk-regression
  (await (replay {:world [2 0 {:text true}]
                  :ops [[:nest 0] [:detach 0] [:rotate 0]]})))

(t/deftest ^:async nesting-restores-default-constraints-on-undo
  (await (replay {:world [2 0] :ops [[:nest 0]]})))

(t/deftest ^:async swap-programs-replay-through-the-frontend
  (doseq [seed [712 20261022]]
    (await (replay (gen/generate cg/swap-program-gen 5 seed)))))

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

(t/deftest ^:async updating-main-then-swapping-a-child-restores-parent-state
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 5 0 0 0] [:update-main 0 0 0 0] [:swap 13 0 0 0]]})))

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

(t/deftest ^:async switching-a-copy-of-a-swap-keeps-position-overrides-inherited
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 13 0 0 0] [:rotate 0 0 0 0] [:switch 2 0 0 0]]})))

(t/deftest ^:async rotated-swapped-head-keeps-its-position-anchor-with-a-different-content-size
  (await (replay {:world [2 2 {:variants true :variant-diff false}]
                  :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                        [:undo] [:redo] [:reset 0] [:edit 0 1 1 1] [:update-main 0]
                        [:sync 1] [:swap 17 0 0 0] [:resize 0 0 0 0] [:rotate 0 0 0 0]]})))

(t/deftest ^:async pushing-a-swap-keeps-the-orientation-inherited-from-its-position
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:rotate 0 0 0 0] [:swap 13 0 0 0] [:update-main 1 0 0 0]]})))

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

(t/deftest ^:async pushing-a-switched-child-uses-its-parents-pushed-frame
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:switch 5 0 0 0] [:rotate 1 0 0 0] [:update-main 0 0 0 0]]})))

(t/deftest ^:async minimum-size-parent-placement-keeps-fixed-child-dimensions
  (await (replay {:world [2 0 {:text true :layout nil}]
                  :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                        [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                        [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                        [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                        [:detach 0] [:undo] [:redo] [:resize 1 0 0 0]
                        [:nest 2 0 0 0] [:rotate 0 0 3 0] [:edit 0 0 0 0]]})))

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

(t/deftest ^:async applying-main-typography-drops-a-mixed-copys-link-without-an-override
  (await (replay {:world [2 0 {:library true :variants true :rich-variants true
                               :text true :text-styles true :layout nil :layout-copies true}]
                  :ops [[:swap 0 0 0 0] [:text 23 3 1 0] [:text-style 22 8 1]
                        [:undo] [:undo] [:undo] [:redo] [:redo] [:redo]]})))

(t/deftest ^:async typography-ownership-stays-consistent-when-a-variant-changes-size
  (await (replay {:world [2 0 {:library true :variants true :rich-variants true
                               :text true :text-styles true :layout nil :layout-copies true}]
                  :ops [[:text-style 4 8 1] [:switch 1 0 0 0]
                        [:undo] [:undo] [:redo] [:redo]]})))

(t/deftest ^:async detaching-after-a-nested-switch-keeps-placement-stable-through-sync
  (await (replay {:world [2 2 {:library true :variants true :rich-variants true
                               :text true :text-styles true :layout :flex :layout-copies true}]
                  :ops [[:nest 7 0 0 0] [:switch 3 0 0 0] [:style 3 7 1] [:detach 6 0 0 0]
                        [:undo] [:undo] [:undo] [:undo] [:redo] [:redo] [:redo] [:redo]]})))

(t/deftest ^:async generated-library-switch-keeps-overrides-from-a-deleted-near-main
  (await (replay {:world [2 1 {:library true :variants true}]
                  :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                        [:undo] [:redo] [:reset 0] [:sync 1]
                        [:delete 0 0 0 0] [:delete 3 0 0 0] [:resize 0 0 0 0]
                        [:delete 9 0 0 0] [:switch 0 0 0 0]]})))

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

(t/deftest ^:async generated-update-main-settles-a-turned-swapped-position
  (await (replay {:world [2 2 {:variants true :variant-diff false}]
                  :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                        [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                        [:delete 8 0 0 0] [:swap 15 0 0 0] [:edit 14 0 0 0]
                        [:resize 4 0 0 0] [:resize 0 0 0 0] [:nest 4 0 0 0]
                        [:rotate 5 0 0 0] [:update-main 14 0 0 0] [:duplicate 0 0 0 0]]})))
