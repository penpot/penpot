;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.interpreter
  "Runs composable component cases through the frontend (see
   `mem:frontend/composable-component-tests`). It installs the situation's
   files in the global store (the component watcher reads it through
   `app.main.refs`), starts the workspace watchers, maps each user operation
   to the workspace events a user action dispatches (`op->events`) and waits
   for the work they cause (`await-step`). The app's own watcher syncs the
   copies; only the library update of case H is an explicit `sync-file`, as
   in the app. `check` is the test entry point."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwtx]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.reflow :as pwrf]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [cljs.test :as t]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [potok.v2.core :as ptk]))

;; --------------------------------------------------------------------------
;; Thumbnails: the component watcher renders them, which reaches `window`,
;; absent headless, so tests stub `dwth/update-thumbnail` (see `fixture`).
;; --------------------------------------------------------------------------

(defonce ^:private original-update-thumbnail (atom nil))

(defn- noop-thumbnail-event []
  (ptk/reify ::noop-thumbnail
    ptk/WatchEvent
    (watch [_ _ _] (rx/empty))))

(defn install-thumbnail-noop!
  "Replace `dwth/update-thumbnail` with a no-op event so no thumbnail rendering
   runs (it would reach `window`, absent headless). Restore with
   `restore-thumbnail!`. Idempotent."
  []
  (when (nil? @original-update-thumbnail)
    (reset! original-update-thumbnail dwth/update-thumbnail)
    (set! dwth/update-thumbnail (fn [& _] (noop-thumbnail-event)))))

(defn restore-thumbnail!
  "Restore the real `dwth/update-thumbnail` saved by `install-thumbnail-noop!`."
  []
  (when-let [orig @original-update-thumbnail]
    (set! dwth/update-thumbnail orig)
    (reset! original-update-thumbnail nil)))

(def fixture
  "An `:each` fixture for tests that run the component watcher: a fresh label
   map and no thumbnail rendering. Its `:after` runs after an async test's
   `done`, so it covers the whole test."
  {:before (fn []
             (cthi/reset-idmap!)
             (install-thumbnail-noop!))
   :after  restore-thumbnail!})

;; --------------------------------------------------------------------------
;; Operations as workspace events
;; --------------------------------------------------------------------------

(declare current-file)

(defn- variant-action-events
  "The workspace events of the `n/VariantAction` `op`."
  [{:keys [action params]} situation]
  (let [id     #(tm/target-shape-id situation %)
        member #(n/member-component situation (:set-name params) (:member params))
        select #(dws/select-shapes (into (d/ordered-set) (map id) %))]
    (case action
      :transform
      [(dwv/transform-in-variant (id (:target params)))]

      :combine
      (if (:selected? params)
        [(select (:targets params))
         (dwv/combine-selected-as-variants {:trigger "workspace:context-menu"})]
        [(dwv/combine-as-variants (mapv id (:targets params)) {:trigger "workspace:context-menu"})])

      :rename
      [(dwv/rename-comp-or-variant-and-main (:id (n/component-of situation (:target params)))
                                            (:name params))]

      :rename-in-layers
      (let [{:keys [id variant-id variant-properties]} (member)]
        [(dwv/update-properties-names-and-values
          id variant-id variant-properties
          (mapv (fn [[name value]] {:name name :value value}) (:properties params)))])

      :remove-empty
      [(dwv/remove-empty-properties (n/variant-set-id situation (:set-name params)))]

      :error
      [(if-let [value (:value params)]
         (dwv/update-error (:id (member)) value)
         (dwv/update-error (:id (member))))]

      :shortcut
      [(select (:targets params))
       (case (:kind params)
         :create-component (dwv/add-component-or-variant)
         :duplicate        (dwv/duplicate-or-add-variant))])))

(defn op->events
  "The workspace events a user action dispatches for the comp operation `op`,
   built from `situation` and from the same helpers the common node uses."
  [op situation]
  (cond
    (instance? n/ChangeProperty op)
    (let [{:keys [target property value]} op
          id (tm/target-shape-id situation target)]
      (if (= :margin property)
        ;; the sidebar's layout child margin
        (let [shape (n/set-property {} property value)]
          [(dwsl/update-layout-child [id] (select-keys shape [:layout-item-margin
                                                              :layout-item-margin-type]))])
        [(dwsh/update-shapes #{id} (fn [shape] (n/set-property shape property value)))]))

    (instance? n/EditText op)
    ;; the text editor's commit on blur, SVG renderer path
    (let [{:keys [target edit value]} op
          id                     (tm/target-shape-id situation target)
          shape                  (tm/shape-by-id situation id)
          {:keys [content name]} (n/text-edit-commit shape edit value)]
      [(dwtx/v2-update-text-shape-content id content
                                          :update-name? (some? name)
                                          :name name
                                          :finalize? true
                                          :save-undo? true
                                          :original-content (:content shape))])

    (instance? n/Rotate op)
    ;; the sidebar rotation, which decides what it touches in copies (check-delta)
    (let [{:keys [target angle]} op]
      [(dwt/increase-rotation [(tm/target-shape-id situation target)] angle)])

    (instance? n/MoveShape op)
    ;; the sidebar position, in page coordinates
    (let [{:keys [target dx dy]} op
          id                     (tm/target-shape-id situation target)
          {:keys [x y]}          (:selrect (tm/shape-by-id situation id))]
      [(dwt/update-position id {:x (+ x dx) :y (+ y dy)} {:absolute? true})])

    (instance? n/ChangeHeight op)
    ;; the sidebar height
    (let [{:keys [target value]} op]
      [(dwt/update-dimensions [(tm/target-shape-id situation target)] :height value)])

    (instance? n/SwapShape op)
    (let [{:keys [target to keep-touched?]} op]
      [(dwl/component-swap (tm/shape-by-id situation (tm/target-shape-id situation target))
                           (:id (tm/file situation))
                           (n/swap-target-id situation to)
                           (boolean keep-touched?))])

    (instance? n/MoveToCell op)
    ;; the board's new cells, as a canvas drop commits them
    (let [{:keys [board child row column]} op]
      [(dwsh/update-shapes #{(tm/target-shape-id situation board)}
                           (n/move-to-cell-fn (tm/target-shape-id situation child) row column)
                           {:with-objects? true})])

    (instance? n/SwitchVariant op)
    (let [head-id (tm/target-shape-id situation (:target op))
          shape   (tm/shape-by-id situation head-id)]
      [(dwv/variants-switch {:shapes [shape]
                             :pos    (n/switch-pos situation op)
                             :val    (n/switch-value situation op)})])

    (instance? n/ApplyToken op)
    (let [data  (:data (current-file))
          token (-> (cfo/get-tokens-in-active-sets (cfo/get-tokens-status data)
                                                   (cfo/get-tokens-lib data))
                    (get (:token-name op)))]
      [(dwta/apply-token {:shape-ids       [(tm/target-shape-id situation (:target op))]
                          :attributes      n/radius-attrs
                          :token           token
                          :on-update-shape dwta/update-shape-radius})])

    (instance? n/UnapplyToken op)
    [(dwta/unapply-token {:shape-ids  [(tm/target-shape-id situation (:target op))]
                          :attributes n/radius-attrs
                          :token-name (:token-name op)})]

    (instance? n/VariantAction op)
    (variant-action-events op situation)

    (instance? n/AddVariant op)
    [(dwv/add-new-variant (:id (n/member-root situation (:set-name op) (:member op))))]

    (instance? n/EditVariantProperty op)
    ;; the design tab's variant property edits
    (let [[edit & args] (n/variant-property-call situation op)]
      [(case edit
         :add       (let [[variant-id opts] args]
                      (dwv/add-new-property variant-id opts))
         :remove    (apply dwv/remove-property args)
         :rename    (let [[variant-id pos name] args]
                      (dwv/update-property-name variant-id pos name {:trigger "workspace:design-tab"}))
         :reorder   (apply dwv/reorder-variant-poperties args)
         :set-value (apply dwv/update-property-value args))])

    (instance? n/Detach op)
    [(dwl/detach-component (tm/target-shape-id situation (:target op)))]

    (instance? n/ResetOverrides op)
    [(dwl/reset-component (tm/target-shape-id situation (:target op)))]

    (instance? n/UpdateMain op)
    [(dwl/update-component-sync (tm/target-shape-id situation (:target op))
                                (:id (tm/file situation)))]

    (instance? n/DeleteComponent op)
    (let [root (tm/shape-by-id situation (tm/target-shape-id situation (:target op)))]
      [(dwl/delete-component {:id (:component-id root)})])

    (instance? n/Duplicate op)
    [(dws/duplicate-shapes #{(tm/target-shape-id situation (:target op))}
                           :change-selection? false)]

    (instance? n/PasteToRoot op)
    ;; the clipboard's copy of the shape, pasted with the root selected
    (let [file (current-file)
          page (get-in file [:data :pages-index (:current-page-id @st/state)])
          data (thp/simulate-copy-shape #{(tm/target-shape-id situation (:target op))}
                                        (:objects page) {(:id file) file} page file
                                        #{"components/v2"} 46)]
      [(dws/select-shape uuid/zero)
       (dw/paste-shapes data)])

    (instance? n/NestCopy op)
    (let [parent-id (tm/target-shape-id situation (:into op))
          copy-id   (tm/target-shape-id situation (:copy op))
          index     (n/nest-target-index situation parent-id)]
      (if-let [cell (:cell op)]
        ;; the canvas drop into a grid cell
        [(dwt/move-shapes-to-frame #{copy-id} parent-id index cell)]
        [(dwsh/relocate-shapes #{copy-id} parent-id index)]))

    (instance? n/RestoreComponent op)
    [(dwl/restore-component (:id (tm/file situation))
                            (n/deleted-component-id situation (:deletion op)))]

    (instance? n/MoveChild op)
    (let [{:keys [target parent to-index]} op]
      [(dwsh/relocate-shapes #{(tm/resolve-shape-id situation target)}
                             (tm/resolve-shape-id situation parent)
                             to-index)])

    (instance? n/RemoveChild op)
    [(dwsh/delete-shapes #{(tm/resolve-shape-id situation (:target op))})]

    (instance? n/AddChild op)
    (let [{:keys [parent new-label shape-params]} op
          parent (tm/resolve-shape situation parent)]
      [(dwsh/add-shape (n/child-shape parent new-label shape-params)
                       {:no-select? true})])

    (instance? n/SyncFromLibrary op)
    ;; the library update the "library updated" dialog runs
    [(dwl/sync-file (:current-file-id @st/state) (n/library-id situation))]

    (instance? n/Undo op)
    (vec (repeat (:times op) dwu/undo))

    (instance? n/UndoBeforeSync op)
    ;; emitted together, so the undo commits before the watcher inspects the edit
    (let [{:keys [target property value]} op]
      [(dwsh/update-shapes #{(tm/target-shape-id situation target)}
                           (fn [shape] (n/set-property shape property value)))
       dwu/undo])

    (instance? n/Redo op)
    [dwu/redo]

    :else
    (throw (ex-info (str "op->events: no frontend realisation for " (pr-str (type op)))
                    {:op op}))))

;; --------------------------------------------------------------------------
;; Interpreter
;; --------------------------------------------------------------------------

;; Longest a step may take to settle; a step that takes longer fails.
(def ^:private step-timeout-ms 2000)

(defn- install-situation-event
  "An event that installs the situation's file as the current one, its
   auxiliary files as linked libraries, and an empty undo stack, so an undo
   never reaches the previous variant's entries."
  [situation]
  (let [file (tm/file situation)
        aux  (tm/aux-files situation)]
    (ptk/reify ::install-file-event
      ptk/UpdateEvent
      (update [_ state]
        (assoc state
               :current-file-id (:id file)
               :current-page-id (cthf/current-page-id file)
               :permissions {:can-edit true}
               :workspace-undo {}
               :files (into {(:id file) file}
                            (map (fn [[lib-id lib]] [lib-id (assoc lib :library-of (:id file))]))
                            aux))))))

(defn current-file
  "The current file in the global store."
  []
  (let [st @st/state]
    (get-in st [:files (:current-file-id st)])))

(defn fill-of
  "The fill color of the shape `label` in the live file."
  [label]
  (-> (cths/get-shape (current-file) label) :fills first :fill-color))

(defn fill-commit
  "A commit that fills the shape `label` of the live file with `color`, with
   `opts` merged into the commit params."
  [label color opts]
  (let [page    (cthf/current-page (current-file))
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{(cthi/id label)}
                 #(assoc % :fills (cths/sample-fills-color :fill-color color))
                 (:objects page)
                 {})]
    (dch/commit-changes (merge {:redo-changes (:redo-changes changes)
                                :undo-changes (:undo-changes changes)}
                               opts))))

(defn- await-ready
  "A promise that resolves with true once `ready?` holds, polling, or with
   false after `timeout-ms`."
  [ready? timeout-ms]
  (let [start (js/Date.now)]
    (js/Promise.
     (fn [resolve]
       (letfn [(poll []
                 (cond
                   (ready?)                                  (resolve true)
                   (> (- (js/Date.now) start) timeout-ms)   (resolve false)
                   :else                                     (js/setTimeout poll 5)))]
         (poll))))))

(defn await-step
  "Emit `events` and return a promise of `:settled` once the pending work of
   `app.main.data.workspace.reflow` drains (component sync, layout and text
   reflow), as the plugin `waitForLayoutUpdate` waits; or of
   `{:timeout pending}` after `timeout-ms` (default `step-timeout-ms`). Work
   must be opened while the events are processed; for events that commit
   only after asynchronous work that opens none (token resolution), pass
   `ready?`, which must hold before the pending work is awaited."
  ([events] (await-step events step-timeout-ms))
  ([events timeout-ms] (await-step events timeout-ms nil))
  ([events timeout-ms ready?]
   (doseq [e events] (st/emit! e))
   (-> (if ready? (await-ready ready? timeout-ms) (js/Promise.resolve true))
       (.then (fn [ready]
                (if ready
                  (-> (pwrf/wait-for-layout-update nil timeout-ms)
                      (.then (constantly :settled))
                      (.catch (fn [_] {:timeout (wrf/pending)})))
                  {:timeout {:not-ready (wrf/pending)}}))))))

(defn- describe-pending
  "The pending work map with the ids replaced by their test labels, if any."
  [pending]
  (update-keys pending #(or (cthi/label %) %)))

(defn- unwrap
  "The concrete operation of an op unit: the op chosen by a RecordedChoice,
   through nested choices (e.g. an optional one-of)."
  [op]
  (if (tm/recorded-choice? op) (recur (tm/choice-of op)) op))

(defn- record-choices
  "Record in `situation` each choice on the way from the op unit `op` to its
   concrete op, outermost first, as nested `RecordedChoice`s' own `apply-to`
   does. Returns the situation."
  [situation op]
  (if (tm/recorded-choice? op)
    (recur (tm/record-choice situation op) (tm/choice-of op))
    situation))

(defn- record-op
  "`situation` with the live file and the application of the settled op unit
   `op`: its choices first, then the op's own fields and its
   `n/applied-result`, if any."
  [situation op]
  (let [before    (tm/file situation)
        situation (-> situation
                      (tm/with-file (current-file))
                      (record-choices op))
        op        (unwrap op)
        record    (cond-> (dissoc (into {} op) ::tm/id)
                    (satisfies? n/IAppliedResult op)
                    (merge (n/applied-result op before (tm/file situation))))]
    (tm/record-application situation op record)))

(defn- op-events
  "The workspace events of the op unit `op` (for a choice, of the chosen op)."
  [op situation]
  (op->events (unwrap op) situation))

(defn- live-shape
  "The shape `id` of the current page of the live file."
  [id]
  (get-in (current-file) [:data :pages-index (:current-page-id @st/state) :objects id]))

(defn- op-ready
  "For the op unit `op` whose events commit only after asynchronous work that
   opens no pending work, a predicate of when they have committed; else nil."
  [op situation]
  (let [op (unwrap op)]
    (when (instance? n/ApplyToken op)
      (let [id (tm/target-shape-id situation (:target op))]
        #(= (:token-name op) (get-in (live-shape id) [:applied-tokens :r1]))))))

(defn- install-file-event
  "An event that makes `file` the current file in the store, the result of a
   `sync-op?` operation."
  [file]
  (ptk/reify ::install-file
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :current-file-id (:id file)
                 :current-page-id (cthf/current-page-id file))
          (assoc-in [:files (:id file)] file)))))

;; --------------------------------------------------------------------------
;; Undo/redo round trip (see `app.common.test-helpers.composable.comp.undo-check`)
;;
;; Run on every user step that dispatches events: undo gives back the file
;; and the undo index before the step, redo those after it, so a step adds
;; exactly one undo entry or group. File installs (`sync-op?`) add none and
;; are the baseline the end-of-variant round trip undoes back to.
;; --------------------------------------------------------------------------

(defn- undo-index
  "The workspace undo index: the entry the next undo reverts, -1 when none."
  []
  (let [{:keys [index items]} (:workspace-undo @st/state)]
    (or index (dec (count items)))))

(defn- snapshot
  "The normalized live file the round trip compares. WASM regenerates
   `:position-data` by measuring, so it is left out there."
  []
  (uc/snapshot (current-file)
               {:position-data? (not (features/active-feature? @st/state "render-wasm/v1"))}))

(defn- mark-baseline
  [situation]
  (uc/mark-baseline situation (undo-index) (snapshot)))

(defn- settle!
  "Emit `event` and wait for it to settle. Resolves with nil, or with the
   reason it did not settle."
  [event]
  (.then (await-step [event])
         (fn [result]
           (when-not (= :settled result)
             (str "did not settle; pending: " (pr-str (describe-pending (:timeout result))))))))

(defn- round-trip-move
  "Emit `event` (undo or redo) and check that the file equals the snapshot
   `expected` and the undo index `expected-index`, recording failures of
   `phase` at `step`. Resolves with `[situation settled?]`."
  [situation event step phase expected expected-index]
  (.then (settle! event)
         (fn [error]
           (if error
             [(uc/add-error situation step phase error) false]
             [(-> situation
                  (uc/compare-states step phase expected (snapshot))
                  (uc/compare-index step (keyword (str (name phase) "-index"))
                                    expected-index (undo-index)))
              true]))))

(defn- check-step
  "The round trip of user step `op`, given the snapshot `before` and the undo
   index `index-before` taken before it. Resolves with the situation."
  [situation op before index-before]
  (let [step        (uc/step situation op)
        after       (snapshot)
        index-after (undo-index)]
    (if (= index-before index-after)
      (js/Promise.resolve (uc/compare-states situation step :no-undo-entry before after))
      (.then (round-trip-move situation dwu/undo step :undo before index-before)
             (fn [[situation settled?]]
               (if settled?
                 (.then (round-trip-move situation dwu/redo step :redo after index-after)
                        first)
                 situation))))))

(defn- repeat-move
  "Emit `event` (undo or redo) until `done?` holds, at most `limit` times.
   Resolves with `[situation times]`, `situation` holding a failure of
   `phase` when a move did not settle."
  [situation event phase done? limit]
  (letfn [(step [times]
            (if (or (done?) (>= times limit))
              (js/Promise.resolve [situation times])
              (.then (settle! event)
                     (fn [error]
                       (if error
                         [(uc/add-error situation nil phase error) times]
                         (step (inc times)))))))]
    (step 0)))

(defn- check-variant
  "The end-of-variant round trip: undo back to the baseline, then redo as many
   times. Resolves with the situation."
  [situation]
  (let [{:keys [depth] :as baseline} (uc/baseline situation)
        final       (snapshot)
        final-index (undo-index)
        limit       (- final-index depth)]
    (if (pos? limit)
      (.then (repeat-move situation dwu/undo :variant-undo #(<= (undo-index) depth) limit)
             (fn [[situation times]]
               (let [situation (uc/compare-states situation nil :variant-undo
                                                  (:snapshot baseline) (snapshot))]
                 (.then (repeat-move situation dwu/redo :variant-redo
                                     #(>= (undo-index) final-index) times)
                        (fn [[situation _]]
                          (uc/compare-states situation nil :variant-redo final (snapshot)))))))
      (js/Promise.resolve situation))))

(defn- sync-op?
  "Whether the op unit `op` runs its `apply-to` on the live file instead of
   dispatching events: assembly nodes, `skip` and inline tests."
  [op]
  (let [op (unwrap op)]
    (or (instance? tm/Skip op)
        (instance? tm/Test op)
        (= :assembly (n/op-kind op)))))

(defn- run-sync-op
  "Run the `apply-to` of the `sync-op?` op unit `op` on the live file and
   install the result. Returns the situation."
  [situation op]
  (let [situation (tm/with-file situation (current-file))
        situation (tm/apply-to op situation)]
    (st/emit! (install-file-event (tm/file situation)))
    ;; a file install is the round trip's new baseline; a skipped optional
    ;; and an inline test install nothing new
    (cond-> situation
      (and (::undo-check? situation)
           (not (instance? tm/Test (unwrap op)))
           (not (instance? tm/Skip (unwrap op))))
      (mark-baseline))))

(defn- run-ops
  "Run the op units `ops` in order, each `sync-op?` one on the live file and
   the others as events awaited with `await-step`. Calls `k` with the final
   situation, or with nil after failing the test when a step did not
   settle."
  [situation ops k]
  (if (empty? ops)
    (k situation)
    (let [op           (first ops)
          kind         (let [op (unwrap op)]
                         (when (satisfies? n/IComponentOperation op)
                           (n/op-kind op)))
          check-step?  (and (::undo-check? situation)
                            (contains? #{:user :frontend-only} kind))
          before       (when check-step? (snapshot))
          index-before (undo-index)]
      (if (sync-op? op)
        (run-ops (run-sync-op situation op) (rest ops) k)
        (.then (await-step (op-events op situation) step-timeout-ms (op-ready op situation))
               (fn [result]
                 (if (= :settled result)
                   (let [situation (record-op situation op)]
                     (cond
                       check-step?
                       (.then (check-step situation (unwrap op) before index-before)
                              #(run-ops % (rest ops) k))

                       ;; never undo past the state a case's own undo went back to
                       (and (::undo-check? situation)
                            (= :undo kind)
                            (< (undo-index) (:depth (uc/baseline situation))))
                       (run-ops (mark-baseline situation) (rest ops) k)

                       :else
                       (run-ops situation (rest ops) k)))
                   (do
                     (t/is false (str "Step did not settle in " step-timeout-ms "ms: "
                                      (pr-str (type (unwrap op)))
                                      "; pending: " (pr-str (describe-pending (:timeout result)))))
                     (k nil)))))))))

(defonce ^:private original-store
  ;; The store at load time. Some test namespaces `set!` st/state and
  ;; st/stream and never restore them, while the `app.main.refs` lenses the
  ;; component watcher reads stay bound to these atoms.
  {:state st/state :stream st/stream})

(defn- restore-global-store!
  "Point st/state / st/stream back at the load-time originals (see
   `original-store`)."
  []
  (set! st/state (:state original-store))
  (set! st/stream (:stream original-store)))

(defn- headless-text-measurement
  "Stands in for the DOM text measurement, which needs a browser. A text whose
   content changes stays pending until the renderer starts measuring it (see
   `app.main.data.workspace.texts`); this starts and ends that measurement
   for each text a `:text/reflow` names, leaving its geometry and
   `:position-data` as they are. Replaces itself when started again."
  []
  (ptk/reify ::headless-text-measurement
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? :text/reflow))
           (rx/map deref)
           ;; after the text watcher opened its wait for the measurement
           (rx/observe-on :async)
           (rx/tap (fn [{:keys [ids]}]
                     (wrf/finish! (wrf/start! :text-measure ids))))
           (rx/ignore)
           (rx/take-until (rx/filter (ptk/type? ::headless-text-measurement) stream))))))

(defn install!
  "Install `situation` in the global store and start the watchers the workspace
   starts, replacing the previous variant's, plus the headless stand-in for
   the DOM text measurement. Restores the global store first (a preceding
   namespace may have swapped it) and forgets any pending work left over, so a
   step that timed out earlier cannot hold up this variant's steps. Stopping
   the previous layout watcher clears it too, but on the first install of a run
   there is none. Returns the situation."
  [situation]
  (restore-global-store!)
  (wrf/reset-pending!)
  (st/emit! (install-situation-event situation)
            (dw/initialize-edit-watchers)
            (dwl/watch-library-changes)
            (headless-text-measurement))
  situation)

(defn- run-variant
  "Set up one variant on the global store and run its ops. `setup` returns a
   situation (file + roles). With `undo-check?`, runs the undo/redo round
   trip of each user step and of the variant. Calls `k` with the final
   situation, or nil when a step did not settle."
  [setup ops undo-check? k]
  ;; a fresh label map per variant, as `tm/run-all` does
  (cthi/reset-idmap!)
  (let [situation (-> (setup)
                      (assoc ::undo-check? undo-check?)
                      (uc/with-runner :frontend)
                      (install!))]
    (run-ops (cond-> situation undo-check? (mark-baseline))
             ops
             (fn [situation]
               (if (and situation undo-check?)
                 (.then (check-variant situation) k)
                 (k situation))))))

;; --------------------------------------------------------------------------
;; Test entry point
;; --------------------------------------------------------------------------

(defn check
  "Run every variant of `case-map` through the frontend, apply `asserter`
   (default the case map's `:asserter`) to each result and check its undo/redo
   round trip (`app.common.test-helpers.composable.comp.undo-check`). Calls the
   async test's `done` at the end. RPC calls are mocked, `rx/timer` fires at
   once, for the delayed RPC of the library update, and DOM lookups find
   nothing, for the delayed focus after adding a variant."
  ([done case-map] (check done case-map (:asserter case-map)))
  ([done {:keys [setup operation] :as case-map} asserter]
   (mock/with-mocks
     {rp/cmd!           mock/rpc-cmd-mock
      rx/timer          mock/timer-mock
      dom/get-element   (constantly nil)}
     (fn [inner-done]
       (let [variants (tm/enumerate operation)]
         (letfn [(run-next [vs]
                   (if (empty? vs)
                     (inner-done)
                     (run-variant
                      setup
                      (tm/sequence-ops (first vs))
                      (uc/enabled? case-map)
                      (fn [situation]
                        (when situation
                          (t/testing (str "operations:\n  " (tm/describe-applied situation))
                            (when asserter
                              (asserter situation))
                            (uc/check! situation case-map)))
                        (run-next (rest vs))))))]
           (run-next variants))))
     done)))
