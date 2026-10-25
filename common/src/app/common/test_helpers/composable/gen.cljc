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
   [app.common.files.helpers :as cfh]
   [app.common.logic.duplicate :as cld]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.component :as ctk]
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

(defn- with-layout
  [situation name layout]
  (if-not layout
    situation
    (let [root (keyword (str "sync-" name "-head"))
          child (keyword (str "sync-" name "-rect"))
          defaults (case layout :flex cls/initial-flex-layout :grid cls/initial-grid-layout)
          file (reduce-kv #(ths/update-shape %1 root %2 %3) (tm/file situation) defaults)
          file (cond-> file (= layout :grid) (tho/place-in-grid-columns root 2 {1 child}))]
      (tm/with-file situation file))))



(defn forest
  "All live page trees, with stable file/page prefixes for model replay."
  [situation]
  (vec (for [[file-index [_ file]] (map-indexed vector (sort-by (comp str key) (cf/all-files situation)))
             [page-index page-id] (map-indexed vector (get-in file [:data :pages]))
             entry (tree (tm/with-file situation (with-meta file {:current-page-id page-id})))]
         (update entry :path #(into [:file file-index :page page-index] %)))))

(defn- all-objects
  [situation]
  (reduce (fn [objects component] (merge (:objects component) objects))
          (into {} (map (juxt (comp :id :shape) :shape)) (forest situation))
          (mapcat #(vals (get-in % [:data :components])) (vals (cf/all-files situation)))))

(defn world
  [[components depth opts]]
  (thi/reset-idmap!)
  (reduce (fn [s index]
            (let [name (str "random-" index)]
              (-> (tm/apply-to (n/create-component name setup/labeled-fill :size 100) s)
                  (with-layout name (when (zero? index) (:layout opts)))
                  (as-> s (reduce #(tm/apply-to %2 %1) s
                                  [(n/make-nested-component name depth)
                                   (n/instantiate-copy name)
                                   (n/instantiate-copy name)])))))
          (let [s (setup/empty-situation)
                s (if (:variants opts)
                    (reduce #(tm/apply-to %2 %1) s
                            [(n/create-component "variant" setup/labeled-fill :size 100)
                             (n/make-variant-container "random-variants"
                                                       [["a" setup/labeled-fill]
                                                        ["b" (if (:variant-diff opts) "#ff0000" setup/labeled-fill)]]
                                                       :size 100)
                             (n/make-nested-component-with-variant "variant" "random-variants" "a")
                             (n/make-nested-component "variant" 1)
                             (n/instantiate-copy "variant")
                             (n/instantiate-copy "variant")])
                    s)]
            (cond-> s
              (:text opts)
              (tm/with-file (-> (thtx/add-text-component-with-copy (tm/file s) [[["Hello world"]]])
                                (ths/update-shape :main-child :grow-type :auto-height)
                                (ths/update-shape :copy-child :grow-type :auto-height)
                                (ths/update-shape :copy-child :touched nil)))))
          (range components)))

(defn candidates
  [situation model kind scope]
  (let [objects (:objects (thf/current-page (tm/file situation)))
        file (tm/file situation)
        libraries (assoc (tm/aux-files situation) (:id file) file)
        copy-head (fn [shape] (and (:shape-ref shape) (:component-id shape)))
        live-component (fn [shape]
                         (let [component (get-in libraries [(:component-file shape) :data :components (:component-id shape)])]
                           (and component (not (:deleted component)))))
        top-copy (fn [shape] (and (copy-head shape)
                                  (= 1 (count (ctn/get-parent-copy-heads objects shape)))))
        overridden (fn overridden [id]
                     (or (seq (get model id))
                         (some overridden (:shapes (get objects id)))))
        eligible (filterv (fn [{:keys [shape]}]
                            (case kind
                              :edit (and (not= :text (:type shape))
                                         (case scope
                                           1 (:shape-ref shape)
                                           2 (and (:shape-ref shape)
                                                  (some #(contains? (tm/aux-files situation) (:component-file %))
                                                        (cfh/get-parents-with-self objects (:id shape))))
                                           true))
                              :reset (and (copy-head shape) (live-component shape))
                              :update-main (and (top-copy shape) (live-component shape) (overridden (:id shape)))
                              :detach (top-copy shape)
                              (:resize :rotate) (top-copy shape)
                              :text (= :text (:type shape))
                              :add-child (ctk/main-instance? shape)
                              :remove-child (and (= :rect (:type shape)) (nil? (:shape-ref shape))
                                                 (some ctk/main-instance? (cfh/get-parents-with-self objects (:id shape))))
                              :duplicate (and (top-copy shape) (< (count objects) 120)
                                              (cld/allow-duplicate? objects shape))
                              :nest (and (top-copy shape) (not (overridden (:id shape))))
                              :delete (ctk/main-instance? shape)
                              (:swap :switch) (and (copy-head shape) (live-component shape))
                              false))
                          (tree situation))]
    (case kind
      (:swap :switch)
      (vec (for [entry eligible
                 destination (tree situation)
                 :let [shape (:shape entry)
                       main (:shape destination)
                       origin (get-in file [:data :components (:component-id shape)])
                       component (get-in file [:data :components (:component-id main)])]
                 :when (and (ctk/main-instance? main)
                            (not= (:component-id shape) (:component-id main))
                            (or (= kind :swap)
                                (and (:variant-id origin)
                                     (= (:variant-id origin) (:variant-id component))))
                            (not (clv/swap-nesting-loop? objects shape (:data file) (:id component))))]
             (assoc entry :destination destination
                    :variant-value (get-in component [:variant-properties 0 :value]))))
      :restore (->> (vals (get-in file [:data :components]))
                    (filter :deleted)
                    (sort-by (comp str :id))
                    (map-indexed (fn [index component]
                                   {:path [:deleted index]
                                    :shape {:id (:main-instance-id component)
                                            :component-id (:id component)}}))
                    vec)
      :nest (vec (for [entry eligible
                       parent (tree situation)
                       :let [parent-shape (:shape parent)]
                       :when (and (ctk/main-instance? parent-shape)
                                  (not= (:parent-id (:shape entry)) (:id parent-shape))
                                  (not (ctn/invalid-structure-for-component?
                                        objects parent-shape [(:shape entry)] false libraries)))]
                   (assoc entry :into (:path parent) :parent (:shape parent))))
      eligible)))

(defn resolve-op
  "Resolve one tuple. Empty target sets produce an explicit skip."
  [situation model [kind index property value scope :as tuple]]
  (let [index (or index 0)
        items (cond->> (candidates situation model kind scope)
                (and (= kind :edit) (= 2 (mod (or property 0) 3)))
                (remove (fn [{:keys [shape]}]
                          ;; Variant names encode properties or rename the set.
                          ;; Those actions have their own nodes, not scalar edits.
                          (or (ctk/is-variant-container? shape)
                              (:variant-id shape)
                              (get-in (tm/file situation)
                                      [:data :components (:component-id shape) :variant-id])))))
        selected (when (seq items) (nth items (mod index (count items))))
        target (when selected (constantly (get-in selected [:shape :id])))
        property (nth [:fills :opacity :name] (mod (or property 0) 3))
        value (case property
                :fills (nth [setup/labeled-fill "#ff0000" "#00ff00"] (mod (or value 0) 3))
                :opacity (nth [1 0.4 0.7] (mod (or value 0) 3))
                :name (str "Label " (mod (or value 0) 3)))
        op (case kind
             :sync nil
             :library-sync (when (seq (tm/aux-files situation)) (n/sync-from-library))
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
                 :text (let [text (thtx/view (:content (:shape selected)))
                             text (if (even? (or (nth tuple 2 nil) 0))
                                    (thtx/set-letters text (nth ["Hello world" "Hello there" "Hello\nworld"] (mod (or (nth tuple 3 nil) 0) 3)))
                                    (thtx/restyle text :size (nth [14 18 24] (mod (or (nth tuple 3 nil) 0) 3))))]
                         (n/edit-text target :content text))
                 :add-child (n/add-child target (keyword (str "generated-" (count (:applied situation))))
                                         :width 20 :height 20 :x 10 :y 10)
                 :remove-child (n/remove-child target)
                 :duplicate (n/duplicate target)
                 :nest (n/nest-copy target (constantly (get-in selected [:parent :id])))
                 :delete (n/delete-component target)
                 :swap (n/swap-shape target (constantly (get-in selected [:destination :shape :component-id])))
                 :switch (n/switch-variant target (:variant-value selected))
                 :restore (n/restore-component (n/delete-component target)))))]
    {:op op :shape (:shape selected) :property property :value value
     :trace (cond-> {:kind kind :tuple tuple
                     :status (if (or op (= kind :sync)) :executed :skip)}
              selected (assoc :target (:path selected))
              (:into selected) (assoc :into (:into selected))
              (:destination selected) (assoc :to (get-in selected [:destination :path]))
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
        model (-> model
                  (shadow/parent-orientation (all-objects situation) (all-objects settled))
                  (shadow/settle (all-objects settled)))
        all (mapcat #(keys (get-in % [:data :components])) (vals (cf/all-files situation)))
        full-before (:situation (cf/sync-world situation all))
        full (:situation (cf/sync-world settled all))
        settled-design (oracle/canonical settled)
        full-before-design (oracle/canonical full-before)
        full-design (oracle/canonical full)]
    (oracle/check settled model)
    (when-not (oracle/equivalent? settled-design full-before-design)
      (oracle/fail :I6 {:diff (thn/diff settled-design full-before-design)}))
    (when-not (oracle/equivalent? settled-design full-design)
      (oracle/fail :I5 {}))
    (when checkpoint (assert-round-trip checkpoint settled))
    (cond-> (assoc state :situation settled :model model :checkpoint nil)
      (and (not= model (:model state)) (seq (:undo state)))
      (assoc-in [:undo (dec (count (:undo state))) :after] model))))

(defn- update-model
  [model before after {:keys [shape property value op]} kind]
  (let [objects (all-objects before)
        id (:id shape)]
    (case kind
      :edit (shadow/edit model shape property value)
      :reset (shadow/clear-subtree model objects id)
      :update-main (shadow/update-main model objects (all-objects after) id)
      :detach (shadow/detach model objects (all-objects after) id)
      :text (shadow/edit-text model shape (:content (n/text-edit-commit shape (:edit op) (:value op))))
      :duplicate (shadow/duplicate model objects id
                                   (:objects (thf/current-page (tm/file after)))
                                   (:new-head (n/applied-result op (tm/file before) (tm/file after))))
      (:swap :switch)
      (let [after-objects (all-objects after)
            old-main (get-in (cf/all-files before) [(:component-file shape) :data :components
                                                    (:component-id shape) :main-instance-id])
            head (get after-objects id)
            new-main (get-in (cf/all-files after) [(:component-file head) :data :components
                                                   (:component-id head) :main-instance-id])]
        (shadow/swap model objects after-objects id old-main new-main (= kind :switch)))
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
            next-model (cond-> (or next-model model)
                         (not (#{:undo :redo} kind))
                         (shadow/parent-orientation (all-objects situation) (all-objects after)))
            next-model (shadow/settle next-model (all-objects after))
            state (-> state
                      (assoc :situation after :model next-model)
                      (update-in [:executed kind] (fnil inc 0)))]
        (oracle/check after next-model (empty? (r/pending-components after)))
        (case kind
          :sync (cond-> state
                  (seq history) (assoc-in [:undo (dec (count history)) :after] next-model))
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

(def structure-kinds
  [:edit :reset :update-main :detach :sync :undo :redo :text :add-child
   :remove-child :duplicate :nest :delete :restore :resize :rotate])

(def structure-program-gen
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 0 2)
            layout (gen/elements [nil :flex :grid])
            ops (gen/vector (gen/tuple (gen/elements structure-kinds)
                                       gen/nat gen/nat gen/nat gen/nat) 0 20)]
    {:world [components depth {:text true :layout layout}]
     :ops (into [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                 [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                 [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                 [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                 [:detach 0] [:undo] [:redo]] ops)}))

(def swap-program-gen
  (gen/let [components (gen/choose 2 3)
            differ? gen/boolean
            ops (gen/vector (gen/tuple (gen/elements (into structure-kinds [:swap :switch]))
                                       gen/nat gen/nat gen/nat gen/nat) 0 20)]
    {:world [components 2 {:variants true :variant-diff differ?}]
     :ops (into [[:edit 0 0 1 1] [:switch 0] [:switch 0]
                 [:swap 0] [:undo] [:redo] [:reset 0]
                 [:edit 0 1 1 1] [:update-main 0] [:sync 1]] ops)}))
