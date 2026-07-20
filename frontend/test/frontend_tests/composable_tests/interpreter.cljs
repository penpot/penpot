;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.composable-tests.interpreter
  "FRONTEND interpreter + test-facing `check` for the composable test model.

   The generic engine lives in `frontend-tests.composable-tests.core` and the
   component instruments (operation records, setups, role accessors, inspection
   methods) behind the `comp` boundary. What lives HERE is:
     1. `op->events` — the event realisation of each EVENT-op: the real
        workspace event(s) it dispatches (it depends on
        `app.main.data.workspace.*`).
     2. an ASYNC interpreter that drives a sequence of operations through the REAL
        app: it installs the situation's file into the global store, starts the
        real component-change watcher, then for each operation dispatches its
        event(s) and AWAITS settlement (so the app's AUTOMATIC propagation — not a
        manual sync — is what runs), re-reading the file from the store after each.
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
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t]
   [frontend-tests.composable-tests.comp.nodes :as n]
   [frontend-tests.composable-tests.core :as tm]
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
   `op` is one of the comp node records (frontend-tests.composable-tests.comp.nodes);
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

    :else
    (throw (ex-info (str "op->events: no frontend realisation for " (pr-str (type op)))
                    {:op op}))))

;; --------------------------------------------------------------------------
;; (2) Async interpreter
;; --------------------------------------------------------------------------

(def ^:private settle-debounce-ms
  "Resolve a step once the store's commit stream has been idle this long. This
   captures the edit commit AND the watcher's follow-up sync commit, without a
   fixed total delay. (Provisional: a fully deterministic per-op stopper would
   await that op's specific component-changed/sync; debounce-idle is robust enough
   for now.)"
  60)

(def ^:private settle-timeout-ms 2000)

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

(defn- current-file
  "Read the live workspace file out of the global store."
  []
  (let [st @st/state]
    (get-in st [:files (:current-file-id st)])))

(defn- await-settle
  "Dispatch `events` into the global store, then call `k` once the commit stream
   has gone idle (debounced). A hard timeout guarantees progress."
  [events k]
  (let [stream  (ptk/input-stream st/state)
        commits (->> stream (rx/filter dch/commit?))
        ;; resolve on first idle gap after a commit, or on timeout
        settled (->> commits
                     (rx/debounce settle-debounce-ms)
                     (rx/take 1)
                     (rx/timeout settle-timeout-ms (rx/of :settle/timeout)))]
    (rx/subscribe settled (fn [_] (k)))
    (doseq [e events] (st/emit! e))))

(defn- record-op
  "Record an operation's application onto the situation (after its effect settled),
   re-reading the file from the store. For a RecordedChoice (one-of), record the
   chosen op's application AND the choice under the one-of identity (so the
   asserter's `get-choice` works), mirroring `RecordedChoice`'s own `apply-to`."
  [situation op]
  (let [situation (tm/with-file situation (current-file))]
    (if (tm/recorded-choice? op)
      (let [chosen     (tm/choice-of op)
            descriptor (dissoc (into {} chosen) :frontend-tests.composable-tests.core/id)]
        (-> situation
            ;; record the chosen op under its own identity …
            (tm/record-application chosen descriptor)
            ;; … and the choice under the one-of's identity, keyed for get-choice
            (update :node-data assoc (tm/choice-one-of-id op) {:chosen chosen})
            (update :applied conj (tm/choice-one-of-id op))))
      (let [descriptor (dissoc (into {} op) :frontend-tests.composable-tests.core/id)]
        (tm/record-application situation op descriptor)))))

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
     - FILE-TRANSFORMING (`make-nested-component`, `skip`): a pure file-value transformation
       that arranges the CONFIGURATION (deepens it, re-points roles) — applied by
       running `apply-to` against the live store file and writing the result back.
       The property under test is still exercised by the SUBSEQUENT real-event ops.
     - `Test`: an inline assertion checkpoint — its `apply-to` runs the assertion
       against the current situation and returns it unchanged.
   Both are handled by `run-sync-op` (no async settle — any store write is a
   synchronous UpdateEvent)."
  [op]
  (let [op (if (tm/recorded-choice? op) (tm/choice-of op) op)]
    (or (instance? n/MakeNestedComponent op)
        ;; the structural building blocks are also file-transforming: they arrange
        ;; the configuration via the shared `apply-to`. ResetCopyInstance is also a
        ;; file-op: the real reset event transitively reads browser globals (CSS
        ;; vars), so it cannot run headless; the shared apply-to runs the production
        ;; reset generator with validation off.
        (instance? n/CreateComponent op)
        (instance? n/InstantiateCopy op)
        (instance? n/ResetCopyInstance op)
        ;; the variant container (test-helper assembly) and variant nesting (the
        ;; shared nesting helper) are file-transforming sync-ops; the SWITCH is the
        ;; real `variants-switch` workspace event (handled by op->events), not here.
        (instance? n/MakeVariantContainer op)
        (instance? n/MakeNestedComponentWithVariant op)
        (instance? tm/Skip op)
        (instance? tm/Test op))))

(defn- run-sync-op
  "Apply a synchronous (`sync-op?`) operation: run its shared `apply-to` against a
   situation whose `:file` is the live store file, write the resulting file back
   into the store, and return the updated situation (with any re-pointed roles).
   For `Test` this runs the inline assertion (against the live store state) and the
   file is unchanged; for `make-nested-component`/`skip` it writes back the transformed file.
   Synchronous."
  [situation op]
  (let [op'       (if (tm/recorded-choice? op) (tm/choice-of op) op)
        situation (tm/with-file situation (current-file))
        situation (tm/apply-to op' situation)]
    (st/emit! (install-file-event (tm/file situation)))
    ;; record the choice too, if this came wrapped in a one-of
    (if (tm/recorded-choice? op)
      (-> situation
          (update :node-data assoc (tm/choice-one-of-id op) {:chosen op'})
          (update :applied conj (tm/choice-one-of-id op)))
      situation)))

(defn- op-grace-ms
  "Extra wait AFTER an event-op has settled, before proceeding. Zero for all ops
   except `SyncFromLibrary`: the production `sync-file` event additionally
   schedules `rx/timer 3000` + an `:update-file-library-sync-status` RPC. There is
   no backend in the headless runner, so that delayed call fails (benignly) — but
   3s after the sync it would land INSIDE whatever test is then running, leaking
   an error trace across test boundaries (and historically destabilising
   whole-suite runs). Waiting it out here absorbs the failure within the test that
   caused it."
  [op]
  (let [op (if (tm/recorded-choice? op) (tm/choice-of op) op)]
    (if (instance? n/SyncFromLibrary op) 3200 0)))

(defn- run-ops
  "Async fold over `ops` (concrete operation units, in order — plain ops and/or
   one-of RecordedChoice wrappers). Threads the situation. A SYNCHRONOUS op
   (`sync-op?` — file-transforming or an inline `Test`) is applied synchronously
   via `apply-to`; every other op dispatches its real workspace event(s) and awaits
   settlement (plus a per-op grace period, see `op-grace-ms`). The file is re-read
   from the store after each. Calls `k` with the final situation."
  [situation ops k]
  (if (empty? ops)
    (k situation)
    (let [op (first ops)]
      (if (sync-op? op)
        (run-ops (run-sync-op situation op) (rest ops) k)
        (await-settle
         (op-events op situation)
         (fn []
           (let [continue #(run-ops (record-op situation op) (rest ops) k)
                 grace    (op-grace-ms op)]
             (if (pos? grace)
               (js/setTimeout continue grace)
               (continue)))))))))

(defn- watch-undo-stack
  "Maintain the workspace UNDO STACK in the (global) store, mirroring the
   production subscription in `app.main.data.workspace/initialize-workspace`:
   for every local commit with `save-undo?` and non-empty `:undo-changes`, dispatch
   `dwu/append-undo`. We start this in the harness because the minimal store setup
   does not run the full `initialize-workspace` (which is where the real app wires
   it). Re-emitting this event stops any previous instance (so re-install per
   variant does not stack watchers)."
  []
  (ptk/reify ::watch-undo-stack
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper-s (->> stream (rx/filter (ptk/type? ::watch-undo-stack)))]
        (->> stream
             (rx/filter dch/commit?)
             (rx/map deref)
             (rx/filter #(= :local (:source %)))
             (rx/mapcat
              (fn [{:keys [save-undo? undo-changes redo-changes undo-group tags stack-undo? selected-before]}]
                (if (and save-undo? (seq undo-changes))
                  (rx/of (dwu/append-undo
                          {:undo-changes undo-changes
                           :redo-changes redo-changes
                           :undo-group undo-group
                           :tags tags
                           :selected-before selected-before}
                          stack-undo?))
                  (rx/empty))))
             (rx/take-until stopper-s))))))

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

(defn- run-variant
  "Set up one variant on the global store and run its ops. `setup` returns a
   situation (file + roles). Restores the global store (a preceding namespace may
   have swapped it), installs the file + starts the watcher, then folds the ops.
   Calls `k` with the final situation."
  [setup ops k]
  ;; fresh label space per variant (mirrors the pure `tm/run-all`)
  (cthi/reset-idmap!)
  (restore-global-store!)
  (let [situation (setup)]
    (st/emit! (install-situation-event situation))
    (st/emit! (dwl/watch-component-changes))
    (st/emit! (watch-undo-stack))
    (run-ops situation ops k)))

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

   Arities: `(check done case-map)` or `(check done case-map asserter)`."
  ([done case-map] (check done case-map nil))
  ([done {:keys [setup operation]} asserter]
   (let [variants (tm/enumerate operation)]
     (letfn [(run-next [vs]
               (if (empty? vs)
                 (done)
                 (run-variant
                  setup
                  ;; a variant is a composed operation; flatten to its ordered leaf
                  ;; ops. `enumerate` already removed all one-of choices, so the
                  ;; variant is a Sequence (or a single op).
                  (tm/sequence-ops (first vs))
                  (fn [situation]
                    (when asserter
                      (t/testing (str "operations:\n  " (tm/describe-applied situation))
                        (asserter situation)))
                    (run-next (rest vs))))))]
       (run-next variants)))))
