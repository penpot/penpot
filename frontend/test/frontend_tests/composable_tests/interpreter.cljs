;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.interpreter
  "FRONTEND interpreter + test-facing `check` for the composable test model.

   The generic engine lives in `app.common.test-helpers.composable.core` and the
   component instruments (operation records, setups, role accessors, inspection
   methods) behind the `comp` boundary. What lives HERE is:
     1. `op->events` — the event realisation of each EVENT-op: the real
        workspace event(s) it dispatches (it depends on
        `app.main.data.workspace.*`).
     2. an ASYNC interpreter that drives a sequence of operations through the REAL
        app: it installs the situation's file into the global store, starts the
        real component-change watcher, then for each operation dispatches its
        event(s) and AWAITS all pending work it caused (`await-step`: the
        watcher's sync wait and layout/text reflow, so the app's AUTOMATIC
        propagation — not a manual sync — is what runs), re-reading the file
        from the store after each. A step that does not settle in time fails.
     3. `check` — the test-facing entry: takes {:setup :operation} + an OPTIONAL
        asserter; enumerates the operation and runs each variant; assertions may
        be inline (`Test` ops) and/or in the asserter.

   IN-FILE PROPAGATION IS AUTOMATIC: there is no propagate operation. The watcher
   (`dwl/watch-component-changes`), running on the global store, detects
   main-instance changes in the CURRENT file and syncs that file's copies on its
   own. That automatic behaviour is precisely what the cases exercise. The one
   deliberate exception is CROSS-FILE propagation (case H): when the main lives
   in a linked LIBRARY and the copy in the consuming file, the watcher does NOT
   cross the file boundary — the real app propagates via the library-UPDATE
   action, so the `SyncFromLibrary` op explicitly dispatches `sync-file` (this is
   faithful: it is exactly the user action, not a test shortcut). See
   `mem:frontend/composable-component-tests`.

   NOTE on the store: this uses the GLOBAL `st/state` store (not the isolated
   `setup-store`), because the watcher reads `refs/workspace-data` which derives
   from `st/state`. State is re-installed per variant for isolation."
  (:require
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.reflow :as pwrf]
   [beicon.v2.core :as rx]
   [cljs.test :as t]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

;; --------------------------------------------------------------------------
;; (0) Thumbnail rendering — disabled in this headless suite
;;
;; The propagation watcher we start (`dwl/watch-component-changes`) ALSO schedules
;; THUMBNAIL renders on every component change (its `component-changed` event has a
;; thumbnail branch). Thumbnail rendering reaches `app.util.dom/get-css-variable`,
;; which calls `window.getComputedStyle` — and there is no `window` in the headless
;; runner, so a (queued, async) render fires LATER and crashes the run. Thumbnails
;; are pure UI side-effect, irrelevant to what we test, so we stub the public seam
;; `dwth/update-thumbnail` to a no-op event for the duration of OUR tests (installed
;; / restored by a `:each` fixture in the case namespace — the same `set!`-and-
;; restore pattern as `frontend_tests.helpers.wasm`, whose `:after` correctly runs
;; only after each async test's `done`). Scope is thus our suite only.
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

;; --------------------------------------------------------------------------
;; (1) Frontend realisation of operations: op record -> workspace event(s)
;;
;; The operation's "what" (its update-fn / target) is shared in common; this maps
;; it to the "how" on the frontend — the real event a user interaction dispatches.
;; Returns a vector of events to emit for that operation.
;; --------------------------------------------------------------------------

;; The edit a ChangeProperty performs is the SHARED `n/set-property`, so the
;; frontend applies the identical change the common node does (only the wrapping
;; differs: a real workspace event here vs. apply-changes there).

(defn- add-child->shape
  "Build the shape value AddChild introduces, parented under the target parent in
   the current store page (mirrors the common add-child's sample shape). Returns
   the shape with parent/frame set to the target parent."
  [parent-label new-label shape-params]
  (let [parent-id (cthi/id parent-label)
        shape     (cths/sample-shape new-label (or shape-params {}))]
    (assoc shape
           :parent-id parent-id
           :frame-id  parent-id)))

(defn op->events
  "Map a comp operation record to the real workspace event(s) it dispatches.
   `op` is one of the comp node records (app.common.test-helpers.composable.comp.nodes);
   `situation` provides cross-file context (e.g. the library id) for ops that need
   it.

   Each operation is realised with the SAME production change builder the common
   node uses, but wrapped in the real workspace event (so it commits to the store
   and the watcher auto-propagates):
     ChangeProperty  -> update-shapes   (generate-update-shapes; n/set-property)
     MoveChild       -> relocate-shapes (generate-relocate, existing shape)
     AddChild        -> add-shape       (create a shape under the parent)
     RemoveChild     -> delete-shapes   (generate-delete-shapes)
     SyncFromLibrary -> sync-file       (the cross-file library-update action, H)
     Undo            -> dwu/undo         (reverse the previous operation(s), I)
     Redo            -> dwu/redo         (apply the undone operation(s) again)
   (In-file propagation is automatic — the watcher — so no propagate op exists.)"
  [op situation]
  (cond
    (instance? n/ChangeProperty op)
    (let [{:keys [target property value]} op]
      ;; `target` may be a ROLE (resolved via the situation, so the edit follows
      ;; the role as make-nested-component re-points it) or a label — same dual resolution
      ;; as the common ChangeProperty. The edit uses the SHARED `n/set-property`.
      [(dwsh/update-shapes #{(tm/target-shape-id situation target)}
                           (fn [shape] (n/set-property shape property value)))])

    (instance? n/Rotate op)
    ;; The real sidebar rotation event, absolute mode: rotates the shape (and its
    ;; whole subtree) around its center. Its apply-modifiers step runs the
    ;; placement-vs-override classification for component copies (check-delta),
    ;; which is part of what a case using `rotate` exercises.
    (let [{:keys [target angle]} op]
      [(dwt/increase-rotation [(tm/target-shape-id situation target)] angle)])

    (instance? n/ChangeHeight op)
    ;; The real sidebar dimension event.
    (let [{:keys [target value]} op]
      [(dwt/update-dimensions [(tm/target-shape-id situation target)] :height value)])

    (instance? n/SwapComponent op)
    ;; Swap lineage `name`'s nesting level `level` for lineage `target`'s component
    ;; via the REAL swap event (dwl/component-swap), so it commits through the
    ;; normal path and the watcher AUTOMATICALLY propagates the swap to copies
    ;; (incl. the deeper nesting levels). This is the behaviour under test.
    (let [{:keys [name level target keep-touched?]} op
          file-id   (:id (tm/file situation))
          nested    (n/lineage-nesting situation name level)
          shape     (tm/shape-by-id situation (:nested-head nested))
          target-id (n/lineage-component-id situation target)]
      [(dwl/component-swap shape file-id target-id (boolean keep-touched?))])

    (instance? n/SwitchVariant op)
    ;; The variant-switch action: switch the resolved variant copy head to the
    ;; sibling member whose selector property (pos 0) has `value`, via the REAL
    ;; `variants-switch` event (which discovers the sibling in the variant container
    ;; and routes through component-swap keep-touched? true, so the watcher
    ;; auto-propagates the switch across nesting levels exactly like a swap). `target`
    ;; uses the standard resolution (role | label | (situation -> id) fn), so the op
    ;; is structure-blind.
    (let [{:keys [target value]} op
          head-id (tm/target-shape-id situation target)
          shape   (tm/shape-by-id situation head-id)]
      [(dwv/variants-switch {:shapes [shape] :pos 0 :val value})])

    (instance? n/MoveChild op)
    (let [{:keys [target parent to-index]} op]
      [(dwsh/relocate-shapes #{(cthi/id target)} (cthi/id parent) to-index)])

    (instance? n/RemoveChild op)
    (let [{:keys [target]} op]
      [(dwsh/delete-shapes #{(cthi/id target)})])

    (instance? n/AddChild op)
    (let [{:keys [parent new-label shape-params]} op]
      [(dwsh/add-shape (add-child->shape parent new-label shape-params)
                       {:no-select? true})])

    (instance? n/SyncFromLibrary op)
    ;; The cross-file library-update action: sync the current (consuming) file
    ;; from its linked library — exactly what the "library updated" dialog does.
    (let [file-id    (:current-file-id @st/state)
          library-id (first (keys (tm/aux-files situation)))]
      [(dwl/sync-file file-id library-id)])

    (instance? n/Undo op)
    ;; Reverse the previous operation(s) via the real undo event. The workspace
    ;; undo stack was maintained automatically by the prior ops' commits.
    [dwu/undo]

    (instance? n/Redo op)
    ;; Apply again the latest undone operation(s) via the real redo event.
    [dwu/redo]

    :else
    (throw (ex-info (str "op->events: no frontend realisation for " (pr-str (type op)))
                    {:op op}))))

;; --------------------------------------------------------------------------
;; (2) Async interpreter
;; --------------------------------------------------------------------------

;; Longest a step may take to settle; a step that takes longer fails.
(def ^:private step-timeout-ms 2000)

(defn- install-situation-event
  "An UpdateEvent installing the situation's files into the (global) store: the
   primary file as the current/workspace file, plus any AUXILIARY files (e.g. a
   linked library, for the cross-file case H) alongside in `:files`, each tagged
   `:library-of` the current file so the library-sync machinery treats them as
   linked libraries."
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
               :files (into {(:id file) file}
                            (map (fn [[lib-id lib]] [lib-id (assoc lib :library-of (:id file))]))
                            aux))))))

(defn current-file
  "Read the live workspace file out of the global store."
  []
  (let [st @st/state]
    (get-in st [:files (:current-file-id st)])))

(defn await-step
  "Emit `events` into the global store and return a promise that resolves with
   `:settled` once every pending work has drained: component sync (the
   watcher's wait for each commit and the sync it starts) and layout and text
   reflow, as tracked by `app.main.data.workspace.reflow`. It waits the way the
   plugin `waitForLayoutUpdate` does. When `timeout-ms` (default
   `step-timeout-ms`) passes first, it resolves with `{:timeout pending}`, the
   work still pending then. Relies on every producer opening its pending work
   synchronously while the events are processed."
  ([events] (await-step events step-timeout-ms))
  ([events timeout-ms]
   (doseq [e events] (st/emit! e))
   (-> (pwrf/wait-for-layout-update nil timeout-ms)
       (.then (constantly :settled))
       (.catch (fn [_] {:timeout (wrf/pending)})))))

(defn- describe-pending
  "The pending work map with the ids replaced by their test labels, if any."
  [pending]
  (update-keys pending #(or (cthi/label %) %)))

(defn- record-op
  "Record an operation's application onto the situation (after its effect settled),
   re-reading the file from the store. For a RecordedChoice (one-of), record the
   choice under the one-of identity (so the asserter's `get-choice` works) and
   then the chosen op's application, in the order `RecordedChoice`'s own
   `apply-to` uses."
  [situation op]
  (let [situation      (tm/with-file situation (current-file))
        [situation op] (if (tm/recorded-choice? op)
                         [(tm/record-choice situation op) (tm/choice-of op)]
                         [situation op])]
    (tm/record-application situation op (dissoc (into {} op) ::tm/id))))

(defn- op-events
  "The workspace events to dispatch for an op unit (a plain op or a one-of's
   RecordedChoice — for the latter, the chosen op's events). `situation` provides
   cross-file context to ops that need it."
  [op situation]
  (op->events (if (tm/recorded-choice? op) (tm/choice-of op) op) situation))

(defn- install-file-event
  "An UpdateEvent that replaces the current file in the (global) store with `file`
   (keeping aux files and everything else). Used to write back the result of a
   FILE-TRANSFORMING op (see `file-op?`)."
  [file]
  (ptk/reify ::install-file
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :current-file-id (:id file)
                 :current-page-id (cthf/current-page-id file))
          (assoc-in [:files (:id file)] file)))))

(defn- sync-op?
  "Whether `op` is a SYNCHRONOUS, `apply-to`-based operation rather than one that
   dispatches a workspace event and needs settling. Two kinds:
     - FILE-TRANSFORMING: the `:assembly` nodes (see `n/IComponentOperation`) and
       `skip`, which arrange the CONFIGURATION (create, nest, instantiate,
       re-point roles) — applied by running `apply-to` against the live store
       file and writing the result back. The property under test is still
       exercised by the SUBSEQUENT real-event ops.
     - `Test`: an inline assertion checkpoint — its `apply-to` runs the assertion
       against the current situation and returns it unchanged.
   Both are handled by `run-sync-op` (no async settle — any store write is a
   synchronous UpdateEvent)."
  [op]
  (let [op (if (tm/recorded-choice? op) (tm/choice-of op) op)]
    (or (instance? tm/Skip op)
        (instance? tm/Test op)
        (= :assembly (n/op-kind op))
        ;; A user operation, but the real reset event transitively reads browser
        ;; globals (CSS vars), so it cannot run headless; the shared apply-to runs
        ;; the production reset generator with validation off.
        (instance? n/ResetCopyInstance op))))

(defn- run-sync-op
  "Apply a synchronous (`sync-op?`) operation: run its shared `apply-to` against a
   situation whose `:file` is the live store file, write the resulting file back
   into the store, and return the updated situation (with any re-pointed roles).
   For `Test` this runs the inline assertion (against the live store state) and the
   file is unchanged; for `make-nested-component`/`skip` it writes back the transformed file.
   Synchronous."
  [situation op]
  (let [situation (tm/with-file situation (current-file))
        situation (tm/apply-to op situation)]
    (st/emit! (install-file-event (tm/file situation)))
    situation))

(defn- run-ops
  "Async fold over `ops` (concrete operation units, in order — plain ops and/or
   one-of RecordedChoice wrappers). Threads the situation. A SYNCHRONOUS op
   (`sync-op?` — file-transforming or an inline `Test`) is applied synchronously
   via `apply-to`; every other op dispatches its real workspace event(s) and
   awaits all pending work (`await-step`). The file is re-read from the store
   after each. Calls `k` with the final situation, or with nil when a step did
   not settle in time; that step fails the test, naming the work still
   pending."
  [situation ops k]
  (if (empty? ops)
    (k situation)
    (let [op (first ops)]
      (if (sync-op? op)
        (run-ops (run-sync-op situation op) (rest ops) k)
        (.then (await-step (op-events op situation))
               (fn [result]
                 (if (= :settled result)
                   (run-ops (record-op situation op) (rest ops) k)
                   (do
                     (t/is false (str "Step did not settle in " step-timeout-ms "ms: "
                                      (pr-str (type (if (tm/recorded-choice? op) (tm/choice-of op) op)))
                                      "; pending: " (pr-str (describe-pending (:timeout result)))))
                     (k nil)))))))))

(defonce ^:private original-store
  ;; Captured at namespace-LOAD time — i.e. before any test in the run executes.
  ;; Several test namespaces (the plugins suite) `set!` st/state / st/stream to
  ;; isolated stores and never restore them. That silently kills this harness:
  ;; the refs in `app.main.refs` (through which `watch-component-changes`
  ;; observes commits) are okulary lenses bound to THIS atom instance at load
  ;; time, so once the var points elsewhere, our events commit to a store the
  ;; watcher does not see and propagation dies with no error. Re-installing the
  ;; original per variant makes the harness immune to run order.
  {:state st/state :stream st/stream})

(defn- restore-global-store!
  "Point st/state / st/stream back at the load-time originals (see
   `original-store`)."
  []
  (set! st/state (:state original-store))
  (set! st/stream (:stream original-store)))

(defn install!
  "Install `situation` in the global store and start the watchers the workspace
   starts, replacing the previous variant's. Restores the global store first (a
   preceding namespace may have swapped it) and forgets any pending work left
   over, so a step that timed out earlier cannot hold up this variant's steps.
   Stopping the previous layout watcher clears it too, but on the first install
   of a run there is none. Returns the situation."
  [situation]
  (restore-global-store!)
  (wrf/reset-pending!)
  (st/emit! (install-situation-event situation)
            (dw/initialize-edit-watchers)
            (dwl/watch-library-changes))
  situation)

(defn- run-variant
  "Set up one variant on the global store and run its ops. `setup` returns a
   situation (file + roles). Calls `k` with the final situation."
  [setup ops k]
  ;; fresh label space per variant (mirrors the pure `tm/run-all`)
  (cthi/reset-idmap!)
  (run-ops (install! (setup)) ops k))

;; --------------------------------------------------------------------------
;; (3) Test-facing check
;; --------------------------------------------------------------------------

(defn check
  "Frontend `check`: run `case-map` ({:setup :operation}) through the REAL app and,
   if `asserter` is given, apply it (a situation -> any fn performing assertions)
   to the resulting situation of EACH enumerated variant. Async — `done` is the
   cljs.test async callback and MUST be called when finished.

   Assertions may be INLINE (via `Test` operations in the `:operation` sequence,
   firing as the op runs) and/or via the trailing `asserter`; `asserter` is
   optional (omit when all assertions are inline). The asserter closes over node
   references the test holds (e.g. `has-property-of` on a change node). In-file
   propagation is AUTOMATIC (the watcher) — no propagate op is added.

   Mocks are installed for the duration of the check: `rp/cmd!` returns
   success (recording calls) and `rx/timer` fires instantly, so the
   `SyncFromLibrary` op's delayed RPC does not produce network errors.

   Arities: `(check done case-map)`, which takes the asserter from the case
   map's `:asserter`, or `(check done case-map asserter)`."
  ([done case-map] (check done case-map (:asserter case-map)))
  ([done {:keys [setup operation]} asserter]
   (mock/with-mocks
     {rp/cmd!  mock/rpc-cmd-mock
      rx/timer mock/timer-mock}
     (fn [inner-done]
       (let [variants (tm/enumerate operation)]
         (letfn [(run-next [vs]
                   (if (empty? vs)
                     (inner-done)
                     (run-variant
                      setup
                      ;; a variant is a composed operation; flatten to its ordered leaf
                      ;; ops. `enumerate` already removed all one-of choices, so the
                      ;; variant is a Sequence (or a single op).
                      (tm/sequence-ops (first vs))
                      (fn [situation]
                        (when (and asserter situation)
                          (t/testing (str "operations:\n  " (tm/describe-applied situation))
                            (asserter situation)))
                        (run-next (rest vs))))))]
           (run-next variants))))
     done)))
