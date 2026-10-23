;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.gen
  "Seeded, shrinkable programs over the composable production-path nodes.
   Tuples resolve their natural-number targets against the current tree.
   Traces contain tree paths, never random UUIDs, and can be replayed."
  (:refer-clojure :exclude [flush])
  (:require
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.types.container :as ctn]
   [app.common.uuid :as uuid]
   [clojure.test.check.generators :as gen]))

(defn tree
  "Shapes in current page tree order, identified by child-index paths."
  [situation]
  (let [objects (:objects (thf/current-page (tm/file situation)))]
    (letfn [(walk [id path]
              (let [shape (get objects id)]
                (cons {:path path :shape shape}
                      (mapcat (fn [[index id]] (walk id (conj path index)))
                              (map-indexed vector (:shapes shape))))))]
      (vec (mapcat (fn [[index id]] (walk id [index]))
                   (map-indexed vector (:shapes (get objects uuid/zero))))))))

(defn world
  [[components depth]]
  (thi/reset-idmap!)
  (reduce (fn [s index]
            (let [name (str "random-" index)]
              (reduce #(tm/apply-to %2 %1) s
                      [(n/create-component name setup/labeled-fill :size 100)
                       (n/make-nested-component name depth)
                       (n/instantiate-copy name)
                       (n/instantiate-copy name)])))
          (setup/empty-situation)
          (range components)))

(defn candidates
  [situation model kind scope]
  (let [objects (:objects (thf/current-page (tm/file situation)))
        copy-head (fn [shape] (and (:shape-ref shape) (:component-id shape)))
        top-copy (fn [shape] (and (copy-head shape)
                                  (= 1 (count (ctn/get-parent-copy-heads objects shape)))))
        overridden (fn overridden [id]
                     (or (seq (get model id))
                         (some overridden (:shapes (get objects id)))))]
    (filterv (fn [{:keys [shape]}]
               (case kind
                 :edit (or (not= scope 1) (:shape-ref shape))
                 :reset (copy-head shape)
                 :update-main (and (top-copy shape) (overridden (:id shape)))
                 :detach (top-copy shape)
                 (:resize :rotate) (top-copy shape)
                 false))
             (tree situation))))

(defn resolve-op
  "Resolve one tuple. Empty target sets produce an explicit skip."
  [situation model [kind index property value scope :as tuple]]
  (let [index (or index 0)
        items (candidates situation model kind scope)
        selected (when (seq items) (nth items (mod index (count items))))
        target (when selected (constantly (get-in selected [:shape :id])))
        property (nth [:fills :opacity :name] (mod (or property 0) 3))
        value (case property
                :fills (nth [setup/labeled-fill "#ff0000" "#00ff00"] (mod (or value 0) 3))
                :opacity (nth [1 0.4 0.7] (mod (or value 0) 3))
                :name (str "Label " (mod (or value 0) 3)))
        op (case kind
             :sync nil
             :undo (when (pos? (r/undo-depth situation)) (n/undo))
             :redo (when (pos? (r/redo-depth situation)) (n/redo))
             (when target
               (case kind
                 :edit (n/change-property target property value)
                 :reset (n/reset target)
                 :update-main (n/update-main target)
                 :detach (n/detach target)
                 :resize (n/change-height target (+ 80 (* 20 (mod (or (nth tuple 3 nil) 0) 6))))
                 :rotate (n/rotate target (* 45 (inc (mod (or (nth tuple 3 nil) 0) 4))))
                 nil)))]
    {:op op :shape (:shape selected) :property property :value value
     :trace (cond-> {:kind kind :tuple tuple
                     :status (if (or op (= kind :sync)) :executed :skip)}
              selected (assoc :target (:path selected))
              (and (nil? op) (not= kind :sync))
              (assoc :reason (str "No eligible " (name kind) " target")))}))

(defn- assert-round-trip
  [before after]
  (let [undone (r/step after (n/undo))
        redone (r/step undone (n/redo))]
    (when-not (= before (oracle/snapshot undone))
      (oracle/fail :I7 {:phase :undo :diff (thn/diff before (oracle/snapshot undone))}))
    (when-not (= (oracle/snapshot after) (oracle/snapshot redone))
      (oracle/fail :I7 {:phase :redo}))))

(defn settle-state
  [{:keys [situation model checkpoint] :as state}]
  (let [settled (r/settle situation)
        all (keys (get-in (tm/file situation) [:data :components]))
        full-before (r/sync-cascade (tm/file situation) (tm/aux-files situation) all)
        full (r/sync-cascade (tm/file settled) (tm/aux-files settled)
                             all)]
    (oracle/check settled model)
    (when-not (= (oracle/design-snapshot settled)
                 (oracle/design-snapshot (tm/with-file situation (:file full-before))))
      (oracle/fail :I6 {}))
    (when-not (= (oracle/design-snapshot settled)
                 (oracle/design-snapshot (tm/with-file settled (:file full))))
      (oracle/fail :I5 {}))
    (when checkpoint (assert-round-trip checkpoint settled))
    (assoc state :situation settled :checkpoint nil)))

(defn- update-model
  [model before after {:keys [shape property value op]} kind]
  (let [objects (:objects (thf/current-page (tm/file before)))
        id (:id shape)]
    (case kind
      :edit (shadow/edit model shape property value)
      :reset (shadow/clear-subtree model objects id)
      :update-main (shadow/update-main model objects id)
      :detach (shadow/detach model objects
                             (:objects (thf/current-page (tm/file after))) id)
      (:resize :rotate)
      (let [shape-after (tm/shape-by-id after id)
            group :geometry-group]
        (if (= (shadow/values shape group) (shadow/values shape-after group))
          model
          (assoc-in model [id group] (shadow/values shape-after group))))
      model)))

(defn step
  [state tuple]
  (let [[kind index] tuple
        state (if (= kind :sync) state (settle-state state))
        {:keys [situation model]} state
        {:keys [op trace] :as resolved} (resolve-op situation model tuple)
        state (update state :trace conj trace)]
    (if (= :skip (:status trace))
      state
      (let [after (if (= kind :sync)
                    (let [pending (sort-by #(str (thi/label %)) (r/pending-components situation))
                          selected (if (and (odd? (or index 0)) (seq pending))
                                     [(nth (vec pending) (mod index (count pending)))]
                                     pending)]
                      (r/settle situation selected))
                    (r/step situation op))
            changed (not= (r/undo-depth situation) (r/undo-depth after))
            history (:undo state)
            redo (:redo state)
            next-model (case kind
                         :undo (:before (peek history))
                         :redo (:after (peek redo))
                         (update-model model situation after resolved kind))
            next-model (or next-model model)
            state (-> state
                      (assoc :situation after :model next-model)
                      (update-in [:executed kind] (fnil inc 0)))]
        (oracle/check after next-model (empty? (r/pending-components after)))
        (case kind
          :sync state
          :undo (-> state (assoc :undo (pop history)) (update :redo conj (peek history)))
          :redo (-> state (assoc :redo (pop redo)) (update :undo conj (peek redo)))
          (cond-> state
            changed (-> (assoc :checkpoint (oracle/snapshot situation) :redo [])
                        (update :undo conj {:before model :after next-model}))))))))

(defn initial-state
  [world-spec]
  (let [situation (world world-spec)]
    {:situation situation :baseline (oracle/snapshot situation)
     :model {} :trace [] :undo [] :redo [] :executed {}}))

(defn- check-sequence
  [{:keys [situation baseline]}]
  (let [depth (r/undo-depth situation)
        undone (r/step situation (n/undo depth))
        redone (nth (iterate #(r/step % (n/redo)) undone) depth)]
    (when-not (= baseline (oracle/snapshot undone))
      (oracle/fail :I7 {:phase :sequence-undo}))
    (when-not (= (oracle/snapshot situation) (oracle/snapshot redone))
      (oracle/fail :I7 {:phase :sequence-redo}))))

(defn run
  "Run a program. A supplied trace must replay the same targets and skips.
   Failures include the program and labelled trace for a regression."
  ([program] (run program nil))
  ([{:keys [ops] world-spec :world :as program} replay-trace]
   (let [progress (atom (initial-state world-spec))]
     (try
       (doseq [tuple ops]
         (swap! progress assoc :current tuple)
         (swap! progress step tuple))
       (swap! progress settle-state)
       (check-sequence @progress)
       (when-not (pos? (reduce + 0 (vals (dissoc (:executed @progress) :sync))))
         (throw (ex-info "No operations executed" {:type ::empty-program})))
       (when (and replay-trace (not= replay-trace (:trace @progress)))
         (throw (ex-info "Replay changed its targets or skips" {:type ::replay-mismatch})))
       @progress
       (catch #?(:clj Throwable :cljs :default) cause
         (throw (ex-info (str (ex-message cause) "\nReplay: (cg/run " (pr-str program) ")"
                              "\nTrace: " (pr-str (:trace @progress)))
                         (assoc (ex-data cause) :program program :trace (:trace @progress)
                                :failing-op (:current @progress))
                         cause)))))))

(defn check-program
  "Reject vacuous shrinks and changes of failure signature. test.check must
   minimize the original invariant failure, rather than a new harness error."
  [program coverage failure]
  (try
    (let [result (run program)]
      (swap! coverage #(merge-with + % (:executed result)))
      true)
    (catch #?(:clj Throwable :cljs :default) error
      (let [data (ex-data error)
            signature [(:invariant data) (:type data) (:phase data)
                       (first (:failing-op data))]]
        (cond
          (= ::empty-program (:type data)) true
          (or (= signature @failure) (compare-and-set! failure nil signature))
          (throw error)
          :else true)))))

(def tuple-gen
  (gen/tuple (gen/elements [:edit :edit :edit :reset :update-main :detach :sync :undo :redo])
             gen/nat gen/nat gen/nat gen/nat))

(def program-gen
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 0 2)
            ops (gen/vector tuple-gen 0 30)]
    {:world [components depth]
     ;; This executable prefix survives shrinking and guarantees the CI
     ;; coverage floor even at size zero. The random tail changes ordering.
     :ops (into [[:edit 0 0 1 1] [:sync 0] [:update-main 0]
                 [:edit 0 1 1 1] [:reset 0] [:detach 0] [:undo] [:redo]] ops)}))
