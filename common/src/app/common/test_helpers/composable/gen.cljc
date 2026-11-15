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
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.duplicate :as cld]
   [app.common.logic.libraries :as cll]
   [app.common.logic.placement :as clp]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.math :as mth]
   [app.common.schema.test :as smt]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.grid :as grid]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.composable.variants :as variants]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [clojure.string :as str]
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

(defn- with-empty-grid
  [situation name]
  (let [situation (-> situation (r/step (n/remove-child (n/remote-rect-of name))) r/settle
                      (r/with-history {}))
        file      (tm/file situation)
        page-id   (:id (thf/current-page file))
        ;; Only grids left without children can lose their tracks and cells.
        ids       (keep #(when (and (= :grid (get-in % [:shape :layout])) (empty? (get-in % [:shape :shapes])))
                           (get-in % [:shape :id]))
                        (tree situation))
        changes   (cls/generate-update-shapes
                   (pcb/empty-changes nil page-id) ids #(merge % cls/initial-grid-layout)
                   (:objects (thf/current-page file)) {:ignore-touched true :skip-grid-reassignment? true})]
    (tm/with-file situation (thf/apply-changes file changes))))

(defn- with-settled-layout
  [situation name]
  (let [file      (tm/file situation)
        page      (thf/current-page file)
        objects   (:objects page)
        id        ((n/main-head-of name) situation)
        tree      (gm/set-objects-modifiers {id {:modifiers (ctm/reflow-modifiers)}} objects)
        transform (clp/modifiers-transform tree)
        changes   (cls/generate-update-shapes
                   (pcb/empty-changes nil (:id page)) (keys tree) transform objects
                   {:ignore-touched true :attrs clp/transform-attrs})]
    (tm/with-file situation (thf/apply-changes file changes))))

(defn- with-layout-children
  [situation name]
  (let [parent (n/main-head-of name)]
    (->> situation
         (tm/apply-to (n/add-child parent (keyword (str name "-layout-item-1"))
                                   :width 60 :height 30 :x 60 :y 0))
         (tm/apply-to (n/add-child parent (keyword (str name "-layout-item-2"))
                                   :width 60 :height 30 :x 120 :y 0))
         (tm/apply-to (n/change-property parent :layout-gap {:row-gap 5 :column-gap 9}))
         (tm/apply-to (n/change-property parent :layout-padding {:p1 3 :p2 6 :p3 9 :p4 12}))
         (#(with-settled-layout % name)))))

(defn- with-layout-item-margins
  [situation name]
  (let [ids (:shapes (tm/shape-by-id situation ((n/main-head-of name) situation)))]
    (-> (reduce (fn [s id]
                  (->> s
                       (tm/apply-to (n/change-property (constantly id) :layout-item-margin
                                                       {:m1 2 :m2 4 :m3 6 :m4 8}))
                       (tm/apply-to (n/change-property (constantly id) :layout-item-margin-type :multiple))))
                situation ids)
        (with-settled-layout name))))

(defn- with-square-grid
  [situation name]
  (let [id       ((n/main-head-of name) situation)
        shape    (tm/shape-by-id situation id)
        children (vec (reverse (:shapes shape)))
        cells    (into {} (map-indexed (fn [index [row column]]
                                         (let [id (uuid/next)]
                                           [id {:id id :row row :column column :row-span 1 :column-span 1
                                                :position :auto :align-self :auto :justify-self :auto
                                                :shapes (if-let [child (get children index)] [child] [])}]))
                                       [[1 1] [1 2] [2 1] [2 2]]))
        file     (-> (tm/file situation)
                     (ths/update-shape-by-id (:id shape) :layout-grid-rows [{:type :fixed :value 60} {:type :fixed :value 60}])
                     (ths/update-shape-by-id (:id shape) :layout-grid-columns [{:type :fixed :value 80} {:type :fixed :value 80}])
                     (ths/update-shape-by-id (:id shape) :layout-grid-cells cells))]
    (with-settled-layout (tm/with-file situation file) name)))

(defn- with-outer-layout
  [situation name layout]
  (if-not layout
    situation
    (let [id ((n/main-head-of name) situation)
          root (thi/label id)
          child (thi/label (first (:shapes (tm/shape-by-id situation id))))
          defaults (case layout :flex cls/initial-flex-layout :grid cls/initial-grid-layout)
          file (reduce-kv #(ths/update-shape %1 root %2 %3) (tm/file situation) defaults)]
      (tm/with-file situation (cond-> file (= layout :grid) (tho/place-in-grid-columns root 2 {1 child}))))))

(defn- ordered-files
  [files]
  (sort-by (fn [[id file]] [(:name file) (str (thi/label id))]) files))

(defn- with-source-text
  [file]
  (-> file
      (tho/add-frame-with-text :library-text-root :library-text-child "Library text"
                               :child-params {:grow-type :auto-height})
      (thc/make-component :library-text-component :library-text-root)
      (thc/instantiate-component :library-text-component :library-text-copy)
      (ths/add-sample-typography :generated-typography
                                 :font-id "gfont-roboto" :font-family "Roboto"
                                 :font-variant-id "700italic" :font-weight "700"
                                 :font-style "italic" :font-size "22"
                                 :line-height "1.6" :letter-spacing "2"
                                 :text-transform "uppercase")))

(defn- with-library
  [situation opts]
  (let [library (-> (tm/make-situation (thf/sample-file :random-library :name "random-library" :is-shared true))
                    (as-> s (tm/apply-to (n/create-component "library-random" setup/labeled-fill :size 100) s))
                    (as-> s (if (:library-grid opts)
                              (-> s
                                  (with-layout "library-random" :grid)
                                  (with-layout-children "library-random")
                                  (with-square-grid "library-random"))
                              s))
                    (as-> s (tm/apply-to (n/make-nested-component "library-random" (or (:library-depth opts) 1)) s))
                    (as-> s (if (:library-variants opts)
                              (tm/apply-to (n/make-variant-container "library-variants"
                                                                     [["a" setup/labeled-fill] ["b" "#ff0000"]]
                                                                     :size 100) s)
                              s))
                    (as-> s (if (:library-variants opts)
                              (variants/size-container s "library-variants" 200 200) s))
                    (as-> s (if (and (:library-variants opts) (:rich-variants opts))
                              (variants/diversify s "library-variants") s))
                    tm/file
                    (cond-> (:library-text opts) with-source-text))
        dependent (when (:library-dependency opts)
                    (-> (tm/make-situation (thf/sample-file :dependent-library :name "dependent-library" :is-shared true))
                        (as-> s (tm/apply-to (n/create-component "library-dependent" setup/labeled-fill :size 100) s))
                        tm/file
                        (thc/instantiate-component :sync-library-random-component :library-dependency-copy
                                                   :parent-label :sync-library-dependent-head :library library)
                        (cond-> (:library-text opts)
                          (thc/instantiate-component :library-text-component :library-dependency-text-copy
                                                     :parent-label :sync-library-dependent-head :library library))))
        libraries (cond-> {(:id library) library} dependent (assoc (:id dependent) dependent))
        components (for [[_ source] (ordered-files libraries)
                         entry (tree (tm/with-file situation source))
                         :when (ctk/main-instance? (:shape entry))]
                     [source (get-in source [:data :components (get-in entry [:shape :component-id])])])
        file (reduce (fn [file [index source component copy]]
                       (thc/instantiate-component
                        file (thi/label (:id component))
                        (keyword (str "random-library-copy-" index "-" copy))
                        :library source))
                     (tm/file situation)
                     (for [[index [source component]] (map-indexed vector components) copy (range 2)]
                       [index source component copy]))]
    (-> situation (tm/with-file file) (tm/with-aux-files libraries))))

(defn get-library-text-chain
  "Participant ids from the initial three-file text dependency fixture.
  Capture these before editing, so later results cannot choose expectations."
  [situation]
  (let [source-id         (thi/id :random-library)
        dependent-id      (thi/id :dependent-library)
        source-text-id    (thi/id :library-text-child)
        dependent-head-id (thi/id :library-dependency-text-copy)
        dependent         (get (tm/aux-files situation) dependent-id)
        dependent-text-id (first (:shapes (tm/shape-by-id (tm/with-file situation dependent) dependent-head-id)))
        copies            (tree situation)]
    {:source-id source-id
     :dependent-id dependent-id
     :source-text-id source-text-id
     :dependent-head-id dependent-head-id
     :dependent-text-id dependent-text-id
     :consumer-root-ids (into [] (keep (fn [{:keys [shape]}]
                                         (when (= (thi/id :sync-library-dependent-head) (:shape-ref shape))
                                           (:id shape)))) copies)
     :consumer-text-ids (into [] (keep (fn [{:keys [shape]}]
                                         (when (= dependent-text-id (:shape-ref shape)) (:id shape)))) copies)
     :direct-text-ids (into [] (keep (fn [{:keys [shape]}]
                                       (when (= source-text-id (:shape-ref shape)) (:id shape)))) copies)}))

(defn forest
  "All live page trees, with stable file/page prefixes for model replay."
  [situation]
  (vec (for [[file-index [_ file]] (map-indexed vector (ordered-files (cf/all-files situation)))
             [page-index page-id] (map-indexed vector (get-in file [:data :pages]))
             entry (tree (tm/with-file situation (with-meta file {:current-page-id page-id})))]
         (update entry :path #(into [:file file-index :page page-index] %)))))

(defn- all-objects
  [situation]
  (reduce (fn [objects component] (merge (:objects component) objects))
          (into {} (map (juxt (comp :id :shape) :shape)) (forest situation))
          (mapcat #(vals (get-in % [:data :components])) (vals (cf/all-files situation)))))

(defn- keep-stored-entries
  "The model `before` an undo, with the entries of `model` for the shapes the
   undo takes off the pages of `situation` into a deleted component, which
   keeps them for a restore to bring back."
  [before model situation]
  (let [live   (into #{} (map (comp :id :shape)) (forest situation))
        stored (all-objects situation)]
    (reduce-kv (fn [result id groups]
                 (cond-> result
                   (and (contains? stored id) (not (contains? live id)) (not (contains? before id)))
                   (assoc id groups)))
               before model)))

(defn- settle-model
  [model situation synced?]
  (let [references (when (some :layout-grid-cells (vals model))
                     (into {}
                           (for [[file-id file] (cf/all-files situation)
                                 container (concat (map #(ctn/make-container % :page) (vals (get-in file [:data :pages-index])))
                                                   (map #(ctn/make-container % :component) (filter :deleted (vals (get-in file [:data :components])))))
                                 shape (vals (:objects container))
                                 :when (get-in model [(:id shape) :layout-grid-cells])
                                 :let [libraries (cf/reference-libraries situation file-id (:id shape))
                                       source (ctf/find-content-ref-shape file container libraries shape :include-deleted? true)]
                                 :when source]
                             [(:id shape) source])))]
    (shadow/settle model (all-objects situation) references synced?)))

(def token-names
  "The border radius tokens of the token programs."
  ["radius.s" "radius.m" "radius.l"])

(def token-values
  "The first values of `token-names`, and the values a token edit gives."
  [4 12 24 8 16 32])

(defn world
  [[components depth opts]]
  (thi/reset-idmap!)
  (cond-> (reduce (fn [s index]
                    (let [name (str "random-" index)]
                      (-> (tm/apply-to (n/create-component name setup/labeled-fill :size 100) s)
                          (with-layout name (when (zero? index) (:layout opts)))
                          (cond-> (and (zero? index) (:layout opts) (:layout-children opts))
                            (with-layout-children name))
                          (cond-> (and (zero? index) (:layout opts) (:layout-item-margins opts))
                            (with-layout-item-margins name))
                          (cond-> (and (zero? index) (= :grid (:layout opts)) (:grid-square opts))
                            (with-square-grid name))
                          (as-> s (reduce #(tm/apply-to %2 %1) s
                                          [(n/make-nested-component name depth)]))
                          (with-outer-layout name (when (and (zero? index) (pos? depth) (:layout-copies opts)) (:layout opts)))
                          (cond-> (and (zero? index) (pos? depth) (:layout opts)
                                       (:layout-copies opts) (:layout-children opts))
                            (with-settled-layout name))
                          (as-> s (reduce #(tm/apply-to %2 %1) s
                                          (repeatedly (:copies opts 2) #(n/instantiate-copy name)))))))
                  (let [s (setup/empty-situation)
                        s (if (:variants opts)
                            (let [s (reduce #(tm/apply-to %2 %1) s
                                            [(n/create-component "variant" setup/labeled-fill :size 100)
                                             (n/make-variant-container "random-variants"
                                                                       (variants/members setup/labeled-fill (:rich-variants opts)
                                                                                         (:variant-diff opts))
                                                                       :size 100)])
                                  s (variants/size-container s "random-variants" 200 200)
                                  s (cond-> s (:rich-variants opts) (variants/diversify "random-variants"))]
                              (reduce #(tm/apply-to %2 %1) s
                                      [(n/make-nested-component-with-variant "variant" "random-variants" "a")
                                       (n/make-nested-component "variant" 1)
                                       (n/instantiate-copy "variant")
                                       (n/instantiate-copy "variant")]))
                            s)]
                    (cond-> s
                      (:text opts)
                      ;; a board and a text of a measurable size: the turns and moves
                      ;; of a hundredth of a pixel are float noise
                      (tm/with-file (-> (thtx/add-text-component-with-copy (tm/file s) [[["Hello world"]]]
                                                                           :frame-params {:width 100 :height 100}
                                                                           :child-params {:width 100 :height 20})
                                        (cond-> (:text-styles opts)
                                          (ths/add-sample-typography :generated-typography
                                                                     :font-id "gfont-roboto" :font-family "Roboto"
                                                                     :font-variant-id "700italic" :font-weight "700"
                                                                     :font-style "italic" :font-size "22"
                                                                     :line-height "1.6" :letter-spacing "2"
                                                                     :text-transform "uppercase"))
                                        (ths/update-shape :main-child :grow-type :auto-height)
                                        (ths/update-shape :copy-child :grow-type :auto-height)
                                        (ths/update-shape :copy-child :touched nil)))))
                  (range components))
    (and (= :grid (:layout opts)) (:grid-empty opts)) (with-empty-grid "random-0")
    ;; Files open with their layouts already computed.
    (:layout opts) (r/settle-layout)
    (:tokens opts) (as-> s (tm/apply-to (n/add-tokens (mapv vector token-names token-values)) s))
    (:library opts) (with-library opts)
    (:stale-libraries opts) (cf/freeze-libraries)
    (:rich-variants opts) (as-> s (assoc s :shape-budget (+ 80 (count (tree s)))))
    (:scale opts) (as-> s (assoc s :shape-budget (+ 120 (count (tree s)))))))

(defn candidates
  [situation model kind scope]
  (let [objects (:objects (thf/current-page (tm/file situation)))
        file (tm/file situation)
        libraries (cf/libraries situation)
        nearest (memoize (fn [file-id component-id pos value]
                           (variants/nearest (get libraries file-id)
                                             {:component-id component-id} pos value)))
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
                                           :copy-head (and (copy-head shape) (live-component shape))
                                           1 (:shape-ref shape)
                                           2 (and (:shape-ref shape)
                                                  (some #(contains? (tm/aux-files situation) (:component-file %))
                                                        (cfh/get-parents-with-self objects (:id shape))))
                                           true))
                              :reset (and (copy-head shape) (live-component shape))
                              :update-main (and (top-copy shape) (live-component shape) (overridden (:id shape))
                                                (not (cll/update-main-nesting-loop? libraries {:objects objects} (:id shape))))
                              :detach (top-copy shape)
                              (:resize :rotate) (top-copy shape)
                              :transform (and (not= :text (:type shape))
                                              (case (mod (or scope 0) 5)
                                                0 (ctk/main-instance? shape)
                                                1 (and (nil? (:shape-ref shape))
                                                       (not (ctk/main-instance? shape))
                                                       (some ctk/main-instance? (cfh/get-parents objects (:id shape))))
                                                2 (and (:shape-ref shape) (nil? (:component-id shape)))
                                                3 (and (copy-head shape) (not (top-copy shape)))
                                                4 (top-copy shape)))
                              (:text :text-style) (= :text (:type shape))
                              :add-child (ctk/main-instance? shape)
                              :apply-token (and (#{:rect :frame} (:type shape))
                                                (not (ctk/is-variant-container? shape)))
                              :unapply-token (some #(get-in shape [:applied-tokens %]) n/radius-attrs)
                              ;; a shape a main holds, other than its root
                              :structure (and (nil? (:shape-ref shape)) (not (ctk/main-instance? shape))
                                              (some ctk/main-instance? (cfh/get-parents objects (:id shape))))
                              :remove-child (and (= :rect (:type shape)) (nil? (:shape-ref shape))
                                                 (some ctk/main-instance? (cfh/get-parents-with-self objects (:id shape))))
                              :duplicate (and (top-copy shape) (< (count objects) (get situation :shape-budget 120))
                                              (cld/allow-duplicate? objects shape))
                              :nest (and (top-copy shape) (not (overridden (:id shape))))
                              ;; a shape a main holds, other than its root: a plain shape
                              ;; or a nested copy head, which no copy around it holds
                              ;; a top copy, or a plain shape a main holds: nothing in it
                              ;; leaves a copy level, so the clipboard advances no ref
                              :paste (and (< (count objects) (get situation :shape-budget 120))
                                          (or (top-copy shape)
                                              (and (nil? (:shape-ref shape))
                                                   (not (ctk/main-instance? shape))
                                                   (some ctk/main-instance? (cfh/get-parents objects (:id shape))))))
                              :rename (and (ctk/main-instance? shape) (not (:variant-id shape))
                                           (live-component shape))
                              :annotate (and (ctk/main-instance? shape) (live-component shape))
                              :move (and (not (ctk/main-instance? shape))
                                         (or (nil? (:shape-ref shape)) (ctk/instance-head? shape))
                                         (not (ctn/has-any-copy-parent? objects shape))
                                         (some ctk/main-instance? (cfh/get-parents objects (:id shape))))
                              :delete (ctk/main-instance? shape)
                              (:swap :switch) (and (copy-head shape) (live-component shape))
                              :variant-property (ctk/is-variant-container? shape)
                              :add-variant (and (ctk/main-instance? shape) (:variant-id shape) (live-component shape)
                                                (< (count objects) (get situation :shape-budget 120)))
                              (:make-variant :combine)
                              (and (ctk/main-instance? shape) (not (:variant-id shape)) (live-component shape)
                                   (= (:component-file shape) (:id file))
                                   (nil? (get-in file [:data :components (:component-id shape) :variant-id]))
                                   (< (count objects) (get situation :shape-budget 120)))
                              false))
                          (tree situation))]
    (case kind
      (:swap :switch)
      (vec (for [entry eligible
                 destination (if (:accepted-libraries situation)
                               (mapcat (fn [[index [id library]]]
                                         (map #(cond-> %
                                                 (not= id (:id file))
                                                 (update :path (fn [path] (into [:library index] path))))
                                              (tree (tm/with-file situation library))))
                                       (map-indexed vector (ordered-files libraries)))
                               (tree situation))
                 :let [shape (:shape entry)
                       main (:shape destination)
                       origin (get-in libraries [(:component-file shape) :data :components (:component-id shape)])
                       component (get-in libraries [(:component-file main) :data :components (:component-id main)])]
                 pos (if (= kind :switch) (range (count (:variant-properties origin))) [nil])
                 :let [value (get-in component [:variant-properties pos :value])
                       expected (when (= kind :switch)
                                  (nearest (:component-file shape) (:component-id shape) pos value))]
                 :when (and (ctk/main-instance? main)
                            (not= (:component-id shape) (:component-id main))
                            (or (= kind :swap)
                                (and (:variant-id origin)
                                     (= (:component-file shape) (:component-file main))
                                     (= (:variant-id origin) (:variant-id component))
                                     (= (:id component) (:id expected))))
                            (not (clv/swap-nesting-loop? objects shape
                                                         (:data (get libraries (:component-file main)))
                                                         (:id component))))]
             (assoc entry :destination destination
                    :variant-value value :variant-pos pos)))
      :restore (->> (vals (get-in file [:data :components]))
                    (filter :deleted)
                    (sort-by (comp str :id))
                    (map-indexed (fn [index component]
                                   {:path [:deleted index]
                                    :shape {:id (:main-instance-id component)
                                            :component-id (:id component)}}))
                    vec)
      :combine (vec (for [[index entry] (map-indexed vector eligible)
                          other (subvec eligible (inc index))]
                      (assoc entry :with other)))
      :nest (vec (for [entry eligible
                       parent (tree situation)
                       :let [parent-shape (:shape parent)]
                       :when (and (ctk/main-instance? parent-shape)
                                  (not= (:parent-id (:shape entry)) (:id parent-shape))
                                  (not (ctn/invalid-structure-for-component?
                                        objects parent-shape [(:shape entry)] false libraries)))]
                   (assoc entry :into (:path parent) :parent (:shape parent))))
      ;; into a board or group of the same main, of another, or of the page,
      ;; or to the page itself
      :move (vec (for [entry eligible
                       :let [shape (:shape entry)
                             own (set (cfh/get-children-ids-with-self objects (:id shape)))]
                       parent (cons {:path [:root] :shape (get objects uuid/zero)} (tree situation))
                       :let [parent-shape (:shape parent)]
                       :when (and (#{:frame :group} (:type parent-shape))
                                  (not (contains? own (:id parent-shape)))
                                  (not= (:parent-id shape) (:id parent-shape))
                                  (not (ctk/in-component-copy? parent-shape))
                                  (not (ctn/has-any-copy-parent? objects parent-shape))
                                  (not (ctk/is-variant-container? parent-shape))
                                  (not (ctn/invalid-structure-for-component?
                                        objects parent-shape [shape] false libraries)))]
                   (assoc entry :into (:path parent) :parent parent-shape)))
      eligible)))

(def library-source-kinds
  #{:library-edit :library-add-child :library-remove-child :library-delete :library-restore
    :library-undo :library-redo :library-accept :library-transform :library-text :library-text-style
    :library-variant-property :library-add-variant :library-make-variant :library-combine})

(def ^:private local-variant-kind
  "The local kind of each library variant kind."
  {:library-variant-property :variant-property :library-add-variant :add-variant
   :library-make-variant :make-variant :library-combine :combine})

(defn- library-candidates
  [situation kind scope]
  (vec
   (for [[library-index [library-id library]]
         (map-indexed vector (ordered-files (tm/aux-files situation)))
         entry (cond
                 (= kind :library-accept)
                 (for [[source-id _] (ordered-files (tm/aux-files situation))
                       :when (and (not= source-id library-id)
                                  (some #(= source-id (:component-file %))
                                        (mapcat vals (cf/object-trees library))))]
                   {:path [:accept (:name (get (tm/aux-files situation) source-id))]
                    :source-id source-id})
                 (#{:library-undo :library-redo} kind)
                 (let [history (get-in situation [:library-histories library-id :runner])
                       editor (r/with-history situation history)]
                   (when (pos? ((if (= kind :library-undo) r/undo-depth r/redo-depth) editor))
                     [{:path []}]))
                 (= kind :library-restore)
                 (map-indexed (fn [index component]
                                {:path [:deleted index]
                                 :shape {:id (:main-instance-id component) :component-id (:id component)}})
                              (sort-by (comp str :id) (filter :deleted (vals (get-in library [:data :components])))))
                 :else
                 (cond->>
                  (filter (fn [{:keys [shape]}]
                            (case kind
                              :library-variant-property (ctk/is-variant-container? shape)
                              :library-add-variant
                              (and (ctk/main-instance? shape) (:variant-id shape)
                                   (not (get-in library [:data :components (:component-id shape) :deleted])))
                              (:library-make-variant :library-combine)
                              (and (ctk/main-instance? shape) (not (:variant-id shape))
                                   (= (:component-file shape) library-id)
                                   (let [component (get-in library [:data :components (:component-id shape)])]
                                     (and component (not (:deleted component)) (nil? (:variant-id component)))))
                              (:library-edit :library-remove-child)
                              (and (= :rect (:type shape)) (nil? (:shape-ref shape)))
                              :library-transform
                              (and (not= :text (:type shape)) (nil? (:shape-ref shape))
                                   (if (even? (or scope 0))
                                     (ctk/main-instance? shape)
                                     (and (not (ctk/main-instance? shape))
                                          (some ctk/main-instance?
                                                (cfh/get-parents (:objects (thf/current-page library)) (:id shape))))))
                              (:library-text :library-text-style)
                              (and (= :text (:type shape)) (nil? (:shape-ref shape)))
                              (:library-add-child :library-delete) (ctk/main-instance? shape)))
                          (tree (tm/with-file situation library)))
                   (= kind :library-combine)
                   ((fn [eligible]
                      (let [eligible (vec eligible)]
                        (for [[index entry] (map-indexed vector eligible)
                              other (subvec eligible (inc index))]
                          (assoc entry :with (update other :path #(into [:library library-index] %)))))))))]
     (-> entry
         (assoc :library-id library-id)
         (update :path #(into [:library library-index] %))))))

(def style-edits
  "Scalar edit inputs. Geometry, text units and grid cell identity use
   dedicated operations; this table does not read production sync groups."
  [[:strokes [[] [{:stroke-color "#ff0000" :stroke-opacity 1 :stroke-width 2
                   :stroke-style :solid :stroke-alignment :center}]]]
   [:hidden [false true]]
   [:blocked [false true]]
   [:r1 [0 8 16]]
   [:blend-mode [:normal :multiply :screen]]
   [:hide-fill-on-export [false true]]
   [:show-content [false true]]
   [:fixed-scroll [false true]]
   [:constraints-h [:left :scale :leftright :center :right]]
   [:constraints-v [:top :scale :topbottom :center :bottom]]
   [:layout-item-margin [{:m1 0 :m2 0 :m3 0 :m4 0} {:m1 4 :m2 4 :m3 4 :m4 4}]]
   [:layout-gap [{:row-gap 0 :column-gap 0} {:row-gap 8 :column-gap 8}]]
   [:layout-padding [{:p1 0 :p2 0 :p3 0 :p4 0} {:p1 8 :p2 8 :p3 8 :p4 8}]]
   [:layout-align-items [:start :center :end]]
   [:shadow [[] [{:id #uuid "10000000-0000-0000-0000-000000000001"
                  :style :drop-shadow :color {:color "#000000" :opacity 0.5}
                  :offset-x 2 :offset-y 3 :blur 4 :spread 1 :hidden false}]
             [{:id #uuid "10000000-0000-0000-0000-000000000001"
               :style :inner-shadow :color {:color "#ff0000" :opacity 0.75}
               :offset-x -2 :offset-y 4 :blur 8 :spread 2 :hidden false}]]]
   [:blur [{:id #uuid "10000000-0000-0000-0000-000000000002"
            :type :layer-blur :value 0 :hidden true}
           {:id #uuid "10000000-0000-0000-0000-000000000002"
            :type :layer-blur :value 4 :hidden false}
           {:id #uuid "10000000-0000-0000-0000-000000000002"
            :type :layer-blur :value 12 :hidden false}]]
   [:background-blur [{:id #uuid "10000000-0000-0000-0000-000000000003"
                       :type :background-blur :value 0 :hidden true}
                      {:id #uuid "10000000-0000-0000-0000-000000000003"
                       :type :background-blur :value 6 :hidden false}
                      {:id #uuid "10000000-0000-0000-0000-000000000003"
                       :type :background-blur :value 16 :hidden false}]]
   [:r2 [0 8 16]]
   [:r3 [0 8 16]]
   [:r4 [0 8 16]]
   [:layout-flex-dir [:row :column :row-reverse :column-reverse]]
   [:layout-wrap-type [:nowrap :wrap]]
   [:layout-justify-content [:start :space-between :center :end :space-around :space-evenly]]
   [:layout-align-content [:stretch :center :end :start :space-between :space-around :space-evenly]]
   [:layout-gap-type [:multiple :simple]]
   [:layout-padding-type [:simple :multiple]]
   [:layout-justify-items [:start :center :end :stretch]]
   [:layout-item-margin-type [:simple :multiple]]
   [:layout-item-align-self [:start :center :end :stretch]]
   [:layout-item-h-sizing [:fix :fill :auto]]
   [:layout-item-v-sizing [:fix :fill :auto]]
   [:layout-item-min-w [0 24 64]]
   [:layout-item-max-w [256 96 64]]
   [:layout-item-min-h [0 24 64]]
   [:layout-item-max-h [256 96 64]]
   [:layout-item-absolute [false true]]
   [:layout-item-z-index [0 2 -1]]])

(def text-style-edits
  "All twelve style units, independent of the production text group table."
  [[:family ["sourcesanspro" "Roboto"]]
   [:weight [400 700]]
   [:size [14 18]]
   [:line-height [1.2 1.5]]
   [:spacing [0 2]]
   [:decoration ["none" "underline"]]
   [:case ["none" "uppercase"]]
   [:color [:black :red]]
   [:typography [nil :generated-typography]]
   [:align ["left" "center"]]
   [:direction ["ltr" "rtl"]]
   [:valign ["top" "bottom"]]])

(def grid-cell-edits
  [{:align-self :end} {:justify-self :center} {:align-self :center :justify-self :end}
   {:align-self :auto :justify-self :auto}])

(def rename-values
  "Names the assets panel gives a component: the name typed, the main root's
   name, and the component's path and name."
  [["Group / Label" "Group / Label" "Group" "Label"]
   ["Label" "Label" "" "Label"]
   [" Other/Label " "Other / Label" "Other" "Label"]
   ["Group/Sub / Label" "Group / Sub / Label" "Group / Sub" "Label"]])

(def annotations
  "Annotations a component is given; nil removes it."
  ["Note" "Another note" nil])

(def structure-edits
  "Structure actions: the action and, for a bool, its type."
  [[:group] [:mask] [:ungroup] [:unmask] [:bool :union] [:bool :difference]
   [:bool :intersection] [:bool :exclude] [:path]])

(defn- structure-edit
  "The structure edit `property` names on the shape of `entry`, and the
   shapes it takes: with an odd `value`, its next sibling too when that is
   one a main holds as well. Nil when the shape cannot take it."
  [objects entry eligible property value]
  (let [[action bool-type] (nth structure-edits (mod (or property 0) (count structure-edits)))
        shape   (:shape entry)
        parent  (get objects (:parent-id shape))
        sibling (let [shapes (:shapes parent)
                      index  (d/index-of (vec shapes) (:id shape))]
                  (get shapes (inc index)))
        pair    (cond-> [(:id shape)]
                  (and (odd? (or value 0)) sibling (contains? eligible sibling)) (conj sibling))
        ;; a grouping that takes every child out of a group removes it
        ;; and leaves its copies referring to it until sync
        empties? (and (#{:group :bool} (:type parent)) (= (set pair) (set (:shapes parent))))
        targets (case action
                  (:group :mask) (when-not empties? pair)
                  :bool    (when (not-any? #(= :text (:type (get objects %))) pair) pair)
                  :ungroup (when (#{:group :bool :frame} (:type shape)) [(:id shape)])
                  :unmask  (when (:masked-group shape) [(:id shape)])
                  :path    (when (#{:rect :circle} (:type shape)) [(:id shape)]))]
    (when targets
      {:action action :targets targets :bool-type bool-type})))

(def grid-track-edits
  "Track editor actions: the action and the kind of track it edits."
  [[:add :row] [:add :column] [:remove :row] [:remove :column] [:resize :row] [:resize :column]
   [:reorder :row] [:reorder :column] [:merge nil]])

(def grid-track-values
  [{:type :flex :value 1} {:type :fixed :value 40} {:type :auto} {:type :percent :value 50}])

(defn- grid-track-edit
  "The track edit `property` names on the grid `shape`, with `value` and
   `scope` choosing its track, its new value and where it goes; nil when the
   grid has no track it can take."
  [shape property value scope]
  (let [[action type] (nth grid-track-edits (mod (or property 0) (count grid-track-edits)))
        value  (or value 0)
        scope  (or scope 0)
        count* (count (get shape (if (= type :column) :layout-grid-columns :layout-grid-rows)))
        track  (nth grid-track-values (mod scope (count grid-track-values)))]
    (case action
      :add     {:action :add :value {:type type :index (mod value (inc count*)) :track track}}
      :remove  (when (> count* 1) {:action :remove :value {:type type :index (mod value count*)}})
      :resize  (when (pos? count*) {:action :resize :value {:type type :index (mod value count*) :track track}})
      :reorder (when (> count* 1) {:action :reorder
                                   :value {:type type :index (mod value count*) :to (mod scope count*)
                                           :move-content? (odd? (quot value count*))}})
      :merge   (let [rows    (count (:layout-grid-rows shape))
                     columns (count (:layout-grid-columns shape))
                     cells   (filterv #(= 1 (:row-span %) (:column-span %)) (grid/get-cells shape))]
                 (when (seq cells)
                   (let [{:keys [row column]} (nth cells (mod value (count cells)))
                         [row-span column-span] (nth [[1 2] [2 1] [2 2]] (mod scope 3))]
                     {:action :merge
                      :value {:row row :column column
                              :row-span (min row-span (inc (- rows row)))
                              :column-span (min column-span (inc (- columns column)))}}))))))

(defn- edit-text-style
  [shape unit value]
  (let [[style values] (nth text-style-edits (mod (or unit 0) (count text-style-edits)))
        value (nth values (mod (or value 0) (count values)))
        text (thtx/view (:content shape))]
    (case style
      (:align :direction :line-height) (thtx/restyle-paragraphs text style value)
      :valign (thtx/set-valign text value)
      :typography (if value
                    (thtx/apply-typography text value
                                           {:family "Roboto" :weight 700 :italic true :size 22
                                            :line-height 1.6 :spacing 2 :case "uppercase"})
                    (thtx/restyle text style nil))
      (thtx/restyle text style value))))

(defn resolve-op
  "Resolve one tuple. Empty target sets produce an explicit skip."
  [situation model [kind index property value scope :as tuple]]
  (let [index (or index 0)
        [style-property style-values] (nth style-edits (mod (or property 0) (count style-edits)))
        items (cond->> (if (contains? library-source-kinds kind)
                         (library-candidates situation kind scope)
                         (candidates situation model (if (#{:style :grid-cell :grid-direction :grid-track} kind) :edit kind)
                                     (if (= kind :grid-track) true scope)))
                (#{:grid-cell :grid-direction :grid-track} kind)
                (filter #(= :grid (get-in % [:shape :layout])))
                ;; a copy edits its tracks only by size; the edits that move its
                ;; cells reach it from its main
                (= :grid-track kind)
                (keep (fn [{:keys [shape] :as entry}]
                        (when-let [edit (grid-track-edit shape property value scope)]
                          (when (or (nil? (:shape-ref shape)) (= :resize (:action edit)))
                            (assoc entry :grid-track edit)))))
                (= :structure kind)
                ((fn [items]
                   (let [objects  (:objects (thf/current-page (tm/file situation)))
                         eligible (into #{} (map (comp :id :shape)) items)]
                     (keep (fn [entry]
                             (when-let [edit (structure-edit objects entry eligible property value)]
                               (assoc entry :structure edit)))
                           items))))
                (= :grid-cell kind)
                (filter #(seq (get-in % [:shape :layout-grid-cells])))
                (= kind :style)
                (filter (fn [{:keys [shape]}]
                          (and (shadow/attribute-applicable? shape style-property)
                               (case style-property
                                 (:layout-gap :layout-padding :layout-align-items
                                              :layout-gap-type :layout-padding-type
                                              :layout-justify-content :layout-align-content) (some? (:layout shape))
                                 (:layout-flex-dir :layout-wrap-type) (= :flex (:layout shape))
                                 :layout-justify-items (= :grid (:layout shape))
                                 :layout-item-align-self (= :flex (:layout (tm/shape-by-id situation (:parent-id shape))))
                                 ;; Hug sizes a board to its own layout; fix and fill
                                 ;; are sizes in the parent's layout.
                                 (:layout-item-h-sizing :layout-item-v-sizing)
                                 (if (= :auto (nth style-values (mod (or value 0) (count style-values))))
                                   (some? (:layout shape))
                                   (some? (:layout (tm/shape-by-id situation (:parent-id shape)))))
                                 (:layout-item-margin :layout-item-margin-type
                                                      :layout-item-min-w :layout-item-max-w
                                                      :layout-item-min-h :layout-item-max-h
                                                      :layout-item-absolute :layout-item-z-index)
                                 (some? (:layout (tm/shape-by-id situation (:parent-id shape))))
                                 true))))
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
        set-edit (when (and selected (#{:variant-property :library-variant-property} kind))
                   (variants/property-edit (variants/set-components (if-let [library-id (:library-id selected)]
                                                                      (get (cf/all-files situation) library-id)
                                                                      (tm/file situation))
                                                                    (get-in selected [:shape :id]))
                                           (or property 0) (or value 0) (or scope 0)))
        token-name (case kind
                     :unapply-token (when selected
                                      (let [names (->> (select-keys (get-in selected [:shape :applied-tokens]) n/radius-attrs)
                                                       vals distinct sort vec)]
                                        (nth names (mod (or property 0) (count names)))))
                     (:apply-token :token-value) (nth token-names (mod (or property 0) (count token-names)))
                     nil)
        token-value (case kind
                      :apply-token (n/token-value (tm/file situation) token-name)
                      :token-value (nth token-values (mod (or value 0) (count token-values)))
                      nil)
        property (if (= kind :style) style-property
                     (nth [:fills :opacity :name] (mod (or property 0) 3)))
        value (if (= kind :style) (nth style-values (mod (or value 0) (count style-values)))
                  (case property
                    :fills (nth [setup/labeled-fill "#ff0000" "#00ff00"] (mod (or value 0) 3))
                    :opacity (nth [1 0.4 0.7] (mod (or value 0) 3))
                    :name (str "Label " (mod (or value 0) 3))))
        op (case kind
             :sync nil
             :library-sync (when (seq (tm/aux-files situation))
                             (n/sync-from-library (key (nth (vec (ordered-files (tm/aux-files situation)))
                                                            (mod index (count (tm/aux-files situation)))))))
             :undo (when (pos? (r/undo-depth situation)) (n/undo))
             :redo (when (pos? (r/redo-depth situation)) (n/redo))
             :library-accept (when selected (n/sync-from-library (:source-id selected)))
             :library-undo (when selected (n/undo))
             :library-redo (when selected (n/redo))
             ;; a token edit does not reach the shapes deleted components store
             ;; (F506), so none is made while one of them applies the token
             :token-value (when-not (some #(seq (n/token-corners % token-name))
                                          (for [component (vals (get-in (tm/file situation) [:data :components]))
                                                :when (:deleted component)
                                                shape (vals (:objects component))]
                                            shape))
                            (n/edit-token-value token-name token-value))
             (when target
               (case kind
                 (:edit :style) (n/change-property target property value)
                 :grid-direction (n/edit-grid target :direction (nth [:row :column] (mod (or (nth tuple 3 nil) 0) 2)))
                 :structure (let [{:keys [action targets bool-type]} (:structure selected)]
                              (n/edit-structure action (mapv constantly targets) :bool-type bool-type))
                 :grid-track (n/edit-grid-track target (get-in selected [:grid-track :action])
                                                (get-in selected [:grid-track :value]))
                 :grid-cell (let [cells (grid/get-cells (:shape selected))
                                  cell  (nth cells (mod (or (nth tuple 2 nil) 0) (count cells)))
                                  props (nth grid-cell-edits (mod (or (nth tuple 3 nil) 0) (count grid-cell-edits)))]
                              (n/edit-grid target :cell (assoc (select-keys cell [:row :column]) :props props)))
                 (:transform :library-transform) (let [action (nth [:move :width :height :rotate :flip-x :flip-y]
                                                                   (mod (or (nth tuple 2 nil) 0) 6))
                                                       value (or (nth tuple 3 nil) 0)]
                                                   (n/transform-shape target action
                                                                      (case action
                                                                        :move [(* 5 (inc (mod value 4))) (* -3 (inc (mod value 3)))]
                                                                        (:width :height) (+ 80 (* 20 (mod value 6)))
                                                                        :rotate (* 45 (inc (mod value 4)))
                                                                        nil)))
                 :library-edit (n/change-property target property value)
                 :library-add-child (n/add-child target (keyword (str "library-generated-" (count (:applied situation))))
                                                 :width 20 :height 20 :x 10 :y 10)
                 :library-remove-child (n/remove-child target)
                 :library-delete (n/delete-component target)
                 :library-restore (n/restore-component (n/delete-component target))
                 :reset (n/reset target)
                 :update-main (n/update-main target)
                 :detach (n/detach target)
                 :resize (n/change-height target (+ 80 (* 20 (mod (or (nth tuple 3 nil) 0) 6))))
                 :rotate (n/rotate target (* 45 (inc (mod (or (nth tuple 3 nil) 0) 4))))
                 (:text :library-text) (let [text (thtx/view (:content (:shape selected)))
                                             text (if (even? (or (nth tuple 2 nil) 0))
                                                    (thtx/set-letters text (nth ["Hello world" "Hello there" "Hello\nworld"] (mod (or (nth tuple 3 nil) 0) 3)))
                                                    (thtx/restyle text :size (nth [14 18 24] (mod (or (nth tuple 3 nil) 0) 3))))]
                                         (n/edit-text target :content text))
                 (:text-style :library-text-style)
                 (let [file-id (or (:library-id selected) (:id (tm/file situation)))
                       typography-edit? (= 8 (mod (or (nth tuple 2 nil) 0) (count text-style-edits)))
                       ;; a typography applies only when the file holds it;
                       ;; without one the edit clears the typography
                       held?   (contains? (get-in (cf/all-files situation) [file-id :data :typographies])
                                          (thi/id :generated-typography))
                       value   (if (and typography-edit? (not held?)) 0 (nth tuple 3 nil))
                       typography? (and typography-edit? held? (odd? (or value 0)))
                       content (cond-> (edit-text-style (:shape selected) (nth tuple 2 nil) value)
                                 (and typography? (:library-id selected))
                                 (thtx/restyle :typography-ref-file file-id))]
                   (n/edit-text target :content content
                                (when typography?
                                  {:typography {:id (thi/id :generated-typography) :file-id file-id}})))
                 :add-child (n/add-child target (keyword (str "generated-" (count (:applied situation))))
                                         :width 20 :height 20 :x 10 :y 10)
                 :apply-token (n/apply-token target token-name token-value)
                 :unapply-token (n/unapply-token target token-name)
                 :remove-child (n/remove-child target)
                 :duplicate (n/duplicate target)
                 (:nest :move) (n/nest-copy target (constantly (get-in selected [:parent :id])))
                 :paste (n/paste-to-root target)
                 :rename (n/rename-component target (first (nth rename-values (mod (or (nth tuple 3 nil) 0)
                                                                                   (count rename-values)))))
                 :annotate (n/annotate-component target (nth annotations (mod (or (nth tuple 3 nil) 0)
                                                                              (count annotations))))
                 :delete (n/delete-component target)
                 :swap (n/swap-shape target (constantly (get-in selected [:destination :shape :component-id])))
                 :switch (n/switch-variant target (:variant-value selected) :pos (:variant-pos selected))
                 :restore (n/restore-component (n/delete-component target))
                 (:variant-property :library-variant-property) (when set-edit
                                                                 (n/edit-variant-set (get-in selected [:shape :id]) (:edit set-edit) (:params set-edit)))
                 (:add-variant :library-add-variant) (n/add-variant nil (get-in selected [:shape :component-id]))
                 (:make-variant :library-make-variant) (n/transform-in-variant target)
                 (:combine :library-combine) (n/combine-as-variants [target (constantly (get-in selected [:with :shape :id]))]))))]
    {:op op :library-id (:library-id selected) :shape (:shape selected)
     :destination (:shape (:destination selected)) :property property :value value :set-edit set-edit
     :with (:shape (:with selected)) :token-name token-name :token-value token-value
     :opportunities (if (seq items) (count items) (if (or op (= kind :sync)) 1 0))
     :trace (cond-> {:kind kind :tuple tuple
                     :status (if (or op (= kind :sync)) :executed :skip)}
              selected (assoc :target (:path selected))
              (:into selected) (assoc :into (:into selected))
              (:destination selected) (assoc :to (get-in selected [:destination :path]))
              (= kind :switch) (assoc :property-pos (:variant-pos selected))
              set-edit (assoc :edit (:edit set-edit))
              (:with selected) (assoc :with (get-in selected [:with :path]))
              token-name (assoc :token token-name)
              (and (nil? op) (not= kind :sync))
              (assoc :reason (str "No eligible " (name kind) " target")))}))

(defn- reference-snapshot
  [situation]
  (when (:accepted-libraries situation)
    (into {} (for [[file-id file] (cf/all-files situation)
                   objects (cf/object-trees file)
                   [id shape] objects
                   :when (:shape-ref shape)]
               [[file-id id] {:shape shape
                              :sources (dissoc (cf/reference-libraries situation file-id id) file-id)}]))))

(defn- reference-patches
  [before after]
  (reduce (fn [patches key]
            (if (= (get before key) (get after key))
              patches
              (-> patches
                  (assoc-in [:reference-before key] (get-in before [key :sources]))
                  (assoc-in [:reference-after key] (get-in after [key :sources])))))
          {} (into (set (keys before)) (keys after))))

(defn- restore-references
  [situation patch]
  (reduce-kv (fn [situation [file-id id] sources]
               (if sources
                 (assoc-in situation [:shape-libraries file-id id] sources)
                 (update-in situation [:shape-libraries file-id] dissoc id)))
             situation (or patch {})))

(defn- assert-round-trip
  [before after journal]
  (let [undone (r/step (restore-references after (:reference-before journal)) (n/undo))
        redone (r/step (restore-references undone (:reference-after journal)) (n/redo))]
    ;; geometry a group derives from its children may differ in its last bits
    (when-not (uc/same-state? before (oracle/history-snapshot undone))
      (oracle/fail :I7 {:phase :undo :diff (thn/diff before (oracle/history-snapshot undone))}))
    (when-not (uc/same-state? (oracle/history-snapshot after) (oracle/history-snapshot redone))
      (oracle/fail :I7 {:phase :redo}))))

(defn- synchronizable-components
  "Full-sync equivalence applies to components whose external inputs have
   been accepted. Pulling an unaccepted source is a user action, not I5/I6."
  [situation]
  (let [files (cf/all-files situation)
        fingerprint (memoize
                     (fn [file component-id]
                       (when-let [component (get-in file [:data :components component-id])]
                         [(dissoc component :modified-at :objects)
                          (mapv #(if (= (:id %) (:main-instance-id component))
                                   (dissoc % :x :y :points :selrect) %)
                                (cf/component-shapes file component))])))]
    (for [[file-id file] files
          [component-id component] (get-in file [:data :components])
          :when (or (nil? (:accepted-libraries situation))
                    (every? (fn [shape]
                              (let [source-id (:component-file shape)
                                    source-component (:component-id shape)]
                                (or (nil? (:shape-ref shape)) (nil? source-component) (= source-id file-id)
                                    (= (fingerprint (get (cf/reference-libraries situation file-id (:id shape)) source-id)
                                                    source-component)
                                       (fingerprint (get files source-id) source-component)))))
                            (cf/component-shapes file component)))]
      component-id)))

(defn settle-state
  [{:keys [situation model checkpoint] :as state}]
  (let [settled (r/settle situation)
        _ (when-let [{:keys [before model root-id]} (:update-main-intent state)]
            (oracle/check-requested-update-main before settled model root-id))
        model (-> model
                  (shadow/parent-orientation (all-objects situation) (all-objects settled))
                  (shadow/layout-released (all-objects situation) (all-objects settled))
                  (shadow/sync-scaled-heads (all-objects situation) (all-objects settled))
                  (settle-model settled true))
        all (into (set (synchronizable-components situation)) (r/pending-components situation))
        full-before (r/settle situation all)
        full (:situation (cf/sync-world settled all))
        ;; an undone creation keeps its stored copies as they were when it was undone
        opts {:free-head-placement false :unreferenced-deleted-components false}
        settled-design (oracle/canonical settled opts)
        full-before-design (oracle/canonical full-before opts)
        full-design (oracle/canonical full opts)
        journal (when checkpoint
                  (merge (peek (:undo state))
                         (reference-patches (:reference-start (peek (:undo state)))
                                            (reference-snapshot settled))
                         {:after model}))]
    (oracle/check settled model)
    ;; sync writes layout-owned sizes and their reflow restores them
    (when-not (or (oracle/equivalent? settled-design full-before-design)
                  (oracle/equivalent? (oracle/canonical (r/settle-layout settled) opts)
                                      (oracle/canonical (r/settle-layout full-before) opts)))
      (oracle/fail :I6 {:diff (thn/diff settled-design full-before-design)}))
    ;; sync writes layout-owned sizes and their reflow restores them
    (when-not (or (oracle/equivalent? settled-design full-design)
                  (oracle/equivalent? (oracle/canonical (r/settle-layout settled) opts)
                                      (oracle/canonical (r/settle-layout full) opts)))
      (oracle/fail :I5 {:diff (thn/diff settled-design full-design)}))
    (when checkpoint (assert-round-trip checkpoint settled journal))
    (cond-> (dissoc (assoc state :situation settled :model model :checkpoint nil) :update-main-intent)
      journal (assoc-in [:undo (dec (count (:undo state)))] (dissoc journal :reference-start)))))

(defn- source-context
  [situation owner]
  (let [files (cf/all-files situation)]
    (-> (tm/make-situation (get files owner))
        (tm/with-aux-files (dissoc files owner))
        (merge (select-keys situation [:accepted-libraries :shape-libraries]))
        (r/with-history (get-in situation [:library-histories owner :runner])))))

(defn- apply-source
  "A remote editor owns its own undo history. Its notification changes only
   the loaded source file; consumer copies wait for their own user action."
  [situation owner op kind]
  (let [before (source-context situation owner)
        history (get-in situation [:library-histories owner]
                        {:undo [] :redo [] :baseline (get (oracle/history-snapshot before) owner)})
        undo? (= kind :library-undo)
        redo? (= kind :library-redo)
        journal (cond undo? (peek (:undo history)) redo? (peek (:redo history)))
        after (-> (cond-> before
                    undo? (restore-references (:reference-before journal))
                    redo? (restore-references (:reference-after journal)))
                  (r/step op) r/settle)
        changed (> (r/undo-depth after) (r/undo-depth before))
        entry (when (and changed (not undo?) (not redo?))
                (reference-patches (reference-snapshot before) (reference-snapshot after)))
        history (cond
                  undo? (-> history (update :undo pop) (update :redo conj journal))
                  redo? (-> history (update :redo pop) (update :undo conj journal))
                  entry (-> history (update :undo conj entry) (assoc :redo []))
                  :else history)
        files (cf/all-files after)
        current-id (:id (tm/file situation))]
    (when entry (assert-round-trip (oracle/history-snapshot before) after entry))
    (when-not (= (dissoc (oracle/history-snapshot before) owner) (dissoc (oracle/history-snapshot after) owner))
      (oracle/fail :I7 {:phase :source-editor-wrote-another-file}))
    (-> situation
        (tm/with-file (get files current-id))
        (tm/with-aux-files (dissoc files current-id))
        (merge (select-keys after [:accepted-libraries :shape-libraries]))
        (assoc-in [:library-histories owner] (assoc history :runner (r/history after)))
        (tm/record-application op {:library-id owner}))))

(defn requested-geometry
  "Predict a transform's result from this backend's before-state and intent."
  [situation {:keys [shape op]} kind]
  (let [objects (:objects (thf/current-page (tm/file situation)))
        modifiers (case kind
                    :resize (ctm/change-size shape nil (:value op))
                    :rotate (n/geometry-modifiers shape :rotate (:angle op))
                    (:transform :library-transform) (n/geometry-modifiers shape (:action op) (:value op)))
        parent (get objects (:parent-id shape))
        ;; the mask of a group passes its change to the group and back, so
        ;; the tree would apply it twice; only its group places it
        mask? (and (:masked-group parent) (= (:id shape) (first (:shapes parent))))
        tree (when-not mask? (gm/set-objects-modifiers {(:id shape) {:modifiers modifiers}} objects))]
    (gsh/transform-shape shape (if mask? modifiers (get-in tree [(:id shape) :modifiers])))))

(defn- kind-file
  "The file an operation edits: the library `library-id`, or the current one."
  [situation library-id]
  (if library-id (get (cf/all-files situation) library-id) (tm/file situation)))

(defn- update-model
  [model before after {:keys [shape property value op library-id token-name token-value]} kind]
  (let [objects (all-objects before)
        id (:id shape)
        kind (get local-variant-kind kind kind)]
    (case kind
      (:edit :style)
      (cond-> (shadow/edit model shape property value)
        ;; a copy child leaving its layout's flow keeps the place the
        ;; layout gave it, which no parent placement authored
        (and (= property :layout-item-absolute) (true? value)
             (:shape-ref shape) (not (:layout-item-absolute shape)))
        (assoc-in [id :geometry-group] (shadow/author-geometry (get (all-objects after) id shape) nil))

        ;; a copy shape keeps the place its old constraint gave it in a board
        ;; whose size differs from its main's along that axis
        (and (#{:constraints-h :constraints-v} property)
             (:shape-ref shape)
             (not (get-in model [id :geometry-group]))
             (not= (get shape property) (get (get (all-objects after) id) property))
             (not (and (ctl/any-layout-immediate-child? objects shape)
                       (not (ctl/position-absolute? shape))))
             (let [frame      (get objects (:frame-id shape))
                   main-frame (get objects (:shape-ref frame))
                   size       (if (= property :constraints-h) :width :height)]
               (and (:shape-ref frame) main-frame
                    (not (mth/close? (get frame size) (get main-frame size) 0.01)))))
        (assoc-in [id :geometry-group]
                  (shadow/author-geometry (get (all-objects after) id shape)
                                          (get (all-objects after) (:frame-id shape))))

        ;; a constraint resizes the shape only with its parent's later resizes
        (and (#{:constraints-h :constraints-v} property) (get-in model [id :geometry-group]))
        (update-in [id :geometry-group] shadow/anchor-position (get (all-objects after) id shape)
                   (get (all-objects after) (:parent-id shape))))
      (:grid-cell :grid-direction) (grid/edit model shape (:action op) (:value op))
      :grid-track (grid/edit-tracks model shape (:action op) (:value op))
      :reset (shadow/clear-subtree model objects id)
      :apply-token (shadow/apply-token model shape token-name token-value)
      :unapply-token (shadow/apply-token model shape token-name nil)
      :token-value (shadow/edit-token-value model objects token-name token-value)
      :update-main (shadow/update-main model objects (all-objects after) id)
      :detach (shadow/detach model objects (all-objects after) id)
      ;; a move resets the shape's constraints to its new parent's defaults,
      ;; which an override of them now holds; a move keeps where the shape
      ;; shows, so the geometry it authored is now that, in its new parent
      :move (let [moved (get (all-objects after) id)]
              (cond-> model
                (get-in model [id :constraints-group])
                (assoc-in [id :constraints-group] (shadow/values moved :constraints-group))

                (get-in model [id :geometry-group])
                (assoc-in [id :geometry-group]
                          (shadow/author-geometry moved (get (all-objects after) (:frame-id moved))))))
      :nest (let [parent (get objects (tm/target-shape-id before (:into op)))]
              (if (:blocked parent)
                (shadow/edit model shape :blocked true)
                model))
      (:text :text-style) (shadow/edit-text model shape (:content (n/text-edit-commit shape (:edit op) (:value op)))
                                            (some? (:typography op)))
      (:duplicate :paste)
      (let [after-objects (all-objects after)
            new-head (:new-head (n/applied-result op (tm/file before) (tm/file after)))
            head (get after-objects new-head)
            main (get after-objects (:shape-ref head))
            model (shadow/duplicate model objects id (:objects (thf/current-page (tm/file after))) new-head)]
        ;; a copy pasted on the page keeps the geometry it showed: what it
        ;; authored, now where it lands, and the orientation and size another
        ;; parent gave it, which its main's need not be
        (cond-> model
          (and (= kind :paste) head main (:shape-ref head)
               (or (get-in model [new-head :geometry-group])
                   (and (not (cfh/root? (get objects (:parent-id shape))))
                        (or (not (gmt/close? (or (:transform head) (gmt/matrix)) (or (:transform main) (gmt/matrix))))
                            (not (mth/close? (:width (:selrect head)) (:width (:selrect main)) 0.01))
                            (not (mth/close? (:height (:selrect head)) (:height (:selrect main)) 0.01))))))
          (assoc-in [new-head :geometry-group] (shadow/author-geometry head nil))))
      (:swap :switch)
      (let [after-objects (all-objects after)
            old-main (get-in (cf/libraries before) [(:component-file shape) :data :components
                                                    (:component-id shape) :main-instance-id])
            head (get after-objects id)
            new-main (get-in (cf/libraries after) [(:component-file head) :data :components
                                                   (:component-id head) :main-instance-id])]
        (shadow/swap model objects after-objects id old-main new-main (= kind :switch)))
      (:make-variant :add-variant)
      (let [file-before (kind-file before library-id)
            file (kind-file after library-id)
            result (n/applied-result op file-before file)
            added (or (:component-id result) (first (:component-ids result)))]
        (shadow/duplicate model objects id (all-objects after)
                          (get-in file [:data :components added :main-instance-id])))
      (:resize :rotate :transform)
      (let [shape-after (requested-geometry before {:shape shape :op op} kind)
            group :geometry-group
            parent (get objects (:parent-id shape))
            placed? (not (contains? (oracle/derived-attributes objects shape) :x))
            ;; a copy parent makes the shape's place an override
            position? (and (:shape-ref parent) placed?)
            ;; a parent board's later resizes move and size the shape through its
            ;; constraints; a group or bool takes its bounds from its children, so its
            ;; children follow the board around it
            board  (if (#{:group :bool} (:type parent))
                     (let [frame (get objects (:frame-id shape))]
                       (when-not (ctl/any-layout? frame) frame))
                     parent)
            origin (when (and board (not (cfh/root? board)) placed?) board)
            changed? (or (not (shadow/same-value? shape-after group (shadow/values shape group)))
                         ;; a stored turn may read the same under one flip while the transform turns
                         (not (gmt/close? (or (:transform shape) (gmt/matrix)) (or (:transform shape-after) (gmt/matrix))))
                         (and position? (not (gsh/close-attrs? :points (:points shape) (:points shape-after) 1e-10)))
                         ;; a path stores no width or height: its size is that of its bounds
                         (and (nil? (:width shape))
                              (not (and (mth/close? (:width (:selrect shape)) (:width (:selrect shape-after)) 1e-10)
                                        (mth/close? (:height (:selrect shape)) (:height (:selrect shape-after)) 1e-10)))))
            ;; the outermost group or bool around the shape that a layout places
            laid (loop [parent parent found nil]
                   (if (#{:group :bool} (:type parent))
                     (recur (get objects (:parent-id parent))
                            (if (and (ctl/any-layout-immediate-child? objects parent)
                                     (not (ctl/position-absolute? parent)))
                              parent
                              found))
                     found))
            after-objects (all-objects after)]
        (cond
          ;; moving some shapes of a group a layout places authors all its shapes
          (and (:shape-ref shape) laid
               ;; the group holds shapes the move leaves where they are
               (let [moved (set (cfh/get-children-ids-with-self objects id))]
                 (some #(and (not (contains? moved %))
                             (not (#{:group :bool} (:type (get objects %)))))
                       (cfh/get-children-ids objects (:id laid))))
               (let [modifiers (case kind
                                 :resize (ctm/change-size shape nil (:value op))
                                 :rotate (n/geometry-modifiers shape :rotate (:angle op))
                                 (n/geometry-modifiers shape (:action op) (:value op)))]
                 (not (gsh/close-attrs? :points (:points shape)
                                        (:points (gsh/transform-shape shape modifiers)) 1e-10))))
          (reduce (fn [model child-id]
                    (let [child (get after-objects child-id)]
                      (if (and (:shape-ref child) (not (#{:group :bool} (:type child)))
                               (or (= child-id id) (not (get-in model [child-id group]))))
                        (assoc-in model [child-id group] (shadow/author-geometry child nil))
                        model)))
                  model
                  (cfh/get-children-ids objects (:id laid)))

          (and (:shape-ref shape) changed?)
          (assoc-in model [id group] (shadow/author-geometry shape-after origin (not position?)))

          :else
          model))
      model)))

(defn- check-requested-variant-properties
  "The properties of every member of the edited set are those the edit
   requested."
  [file {:keys [shape set-edit]}]
  (let [expected (:expected set-edit)
        actual (into {} (map (juxt :id #(vec (:variant-properties %))))
                     (variants/set-components file (:id shape)))]
    (when-not (and (= (set (keys expected)) (set (keys actual)))
                   (every? (fn [[id props]] (variants/properties-match? props (get actual id))) expected))
      (oracle/fail :I9 {:phase :requested-variant-properties :edit (:edit set-edit)
                        :params (:params set-edit) :expected expected :actual actual}))))

(defn- check-requested-variant
  "The added member is in the set of its source, with the source's
   properties but a new value of the last one."
  [file-before file {:keys [shape op]}]
  (let [source (get-in file-before [:data :components (:component-id shape)])
        added-id (:component-id (n/applied-result op file-before file))
        added (some #(when (= added-id (:id %)) %) (variants/set-components file (:variant-id source)))
        source-props (vec (:variant-properties source))
        props (vec (:variant-properties added))
        taken (set (map #(:value (peek (vec (:variant-properties %))))
                        (variants/set-components file-before (:variant-id source))))]
    (when-not (and added
                   (= (mapv :name source-props) (mapv :name props))
                   (= (pop source-props) (pop props))
                   (not (str/blank? (:value (peek props))))
                   (not (contains? taken (:value (peek props)))))
      (oracle/fail :I9 {:phase :requested-variant :source source-props :added props :taken taken}))))

(defn- check-requested-variant-set
  "The components of the mains a transform or a combine names are the
   members of one new set, whose board holds their mains, with the same
   property names; a transform adds a member with a value of its own."
  [file-before file {:keys [shape with op]} kind]
  (let [added (:component-ids (n/applied-result op file-before file))
        ids (cond-> [(:component-id shape)]
              (= kind :combine) (conj (:component-id with))
              (= kind :make-variant) (into added))
        components (mapv #(get-in file [:data :components %]) ids)
        variant-id (:variant-id (first components))
        members (variants/set-components file variant-id)
        values (map #(:value (peek (vec (:variant-properties %)))) members)]
    (when-not (and (some? variant-id)
                   (some #(get-in % [:objects variant-id :is-variant-container]) (vals (get-in file [:data :pages-index])))
                   (every? #(= variant-id (:variant-id %)) components)
                   (= (set ids) (set (map :id members)))
                   (seq (:variant-properties (first members)))
                   (apply = (map #(mapv :name (:variant-properties %)) members))
                   (or (= kind :combine) (apply distinct? values)))
      (oracle/fail :I9 {:phase :requested-variant-set :kind kind :ids ids
                        :members (mapv (juxt :id :variant-id :variant-properties) members)}))))

(defn step
  [state tuple]
  (let [[kind index] tuple
        state (if (= kind :sync) state
                  (try (settle-state state)
                       (catch #?(:clj Throwable :cljs :default) cause
                         ;; Settlement finishes the prior action before resolving this tuple.
                         (let [previous (some #(when (= :executed (:status %)) (:tuple %))
                                              (rseq (:trace state)))]
                           (throw (ex-info (ex-message cause)
                                           (merge {:failing-op previous} (ex-data cause))
                                           cause))))))
        {:keys [situation model]} state
        {:keys [op trace opportunities library-id] :as resolved} (resolve-op situation model tuple)
        phase (or (:phase state) :random)
        coverage-path [:coverage phase kind]
        state (-> state
                  (update :trace conj trace)
                  (update-in (conj coverage-path :attempted) (fnil inc 0))
                  (update-in (conj coverage-path :eligible) (fnil + 0) (if (pos? opportunities) 1 0))
                  (update-in (conj coverage-path :opportunities) (fnil + 0) opportunities))]
    (if (= :skip (:status trace))
      (update-in state (conj coverage-path :skipped) (fnil inc 0))
      (let [operation-situation
            (case kind
              :undo (restore-references situation (:reference-before (peek (:undo state))))
              :redo (restore-references situation (:reference-after (peek (:redo state))))
              situation)
            ;; Retain file inputs, without runner history, through deferred sync.
            intent (when (= kind :update-main)
                     {:before (-> (tm/make-situation (tm/file situation))
                                  (tm/with-aux-files (tm/aux-files situation)))
                      :model model :root-id (get-in resolved [:shape :id])})
            after (cond
                    library-id (apply-source situation library-id op kind)
                    (= kind :sync)
                    (let [pending (sort-by #(str (thi/label %)) (r/pending-components situation))
                          selected (if (and (odd? (or index 0)) (seq pending))
                                     [(nth (vec pending) (mod index (count pending)))]
                                     pending)]
                      (r/settle situation selected))
                    :else (r/step operation-situation op))
            after (if library-id after
                      (reduce (fn [after owner]
                                (cond-> after
                                  (not= (get (oracle/history-snapshot situation) owner)
                                        (get (oracle/history-snapshot after) owner))
                                  (assoc-in [:library-histories owner :foreign-write] true)))
                              after (keys (:library-histories situation))))
            _ (when (= kind :update-main)
                (oracle/check-requested-update-main (:before intent) after model (:root-id intent)))
            changed (not= (r/undo-depth situation) (r/undo-depth after))
            history (:undo state)
            redo (:redo state)
            next-model (case kind
                         :undo (keep-stored-entries (:before (peek history)) model after)
                         :redo (:after (peek redo))
                         (update-model model situation after resolved kind))
            next-model (cond-> (or next-model model)
                         (not (#{:undo :redo} kind))
                         (-> (shadow/parent-orientation (all-objects situation) (all-objects after))
                             (shadow/layout-released (all-objects situation) (all-objects after))
                             (shadow/sync-scaled-heads (all-objects situation) (all-objects after))))
            next-model (settle-model next-model after (empty? (r/pending-components after)))
            meaningful (not (oracle/equivalent? (oracle/design-snapshot situation {:free-head-placement false})
                                                (oracle/design-snapshot after {:free-head-placement false})))
            state (-> state
                      (assoc :situation after :model next-model)
                      (cond-> intent (assoc :update-main-intent intent))
                      (update-in (conj coverage-path (if meaningful :changed :no-op)) (fnil inc 0))
                      (cond-> (and (not meaningful)
                                   (> (r/undo-depth after) (r/undo-depth situation)))
                        (update-in (conj coverage-path :undo-only) (fnil inc 0)))
                      (update-in [:executed kind] (fnil inc 0)))]
        (when (#{:resize :rotate :transform :library-transform} kind)
          (let [before (if library-id (source-context situation library-id) situation)
                after (if library-id (source-context after library-id) after)]
            (oracle/check-requested-geometry (:objects (thf/current-page (tm/file after)))
                                             (tm/shape-by-id after (get-in resolved [:shape :id]))
                                             (requested-geometry before resolved kind))))
        (when (#{:grid-cell :grid-direction} kind)
          (oracle/check-requested-grid (tm/shape-by-id after (get-in resolved [:shape :id]))
                                       (:shape resolved) (:action op) (:value op)))
        (when (= :grid-track kind)
          (oracle/check-requested-tracks (tm/shape-by-id after (get-in resolved [:shape :id]))
                                         (:shape resolved) (:action op) (:value op)))
        (let [variant-kind (get local-variant-kind kind kind)
              file-before (kind-file situation library-id)
              file (kind-file after library-id)]
          (case variant-kind
            :variant-property (check-requested-variant-properties file resolved)
            :add-variant (check-requested-variant file-before file resolved)
            (:make-variant :combine) (check-requested-variant-set file-before file resolved variant-kind)
            nil))
        (case kind
          :apply-token (when-not (n/has-token? (tm/shape-by-id after (get-in resolved [:shape :id]))
                                               (:token-name resolved) (:token-value resolved))
                         (oracle/fail :I9 {:phase :requested-token :token (:token-name resolved)}))
          :unapply-token (when (seq (n/token-corners (tm/shape-by-id after (get-in resolved [:shape :id]))
                                                     (:token-name resolved)))
                           (oracle/fail :I9 {:phase :requested-token-removal :token (:token-name resolved)}))
          :token-value (when-not (= (:token-value resolved) (n/token-value (tm/file after) (:token-name resolved)))
                         (oracle/fail :I9 {:phase :requested-token-value :token (:token-name resolved)}))
          nil)
        (when (#{:swap :switch} kind)
          (let [head (tm/shape-by-id after (get-in resolved [:shape :id]))]
            (when-not (= (get-in resolved [:destination :component-id]) (:component-id head))
              (oracle/fail :I9 {:phase :requested-component :kind kind}))))
        (when (#{:rename :annotate} kind)
          (let [head (tm/shape-by-id after (get-in resolved [:shape :id]))
                component (get-in (tm/file after) [:data :components (:component-id head)])]
            (if (= kind :rename)
              (let [[_ shape-name path name] (d/seek #(= (first %) (get-in resolved [:op :value])) rename-values)]
                (when-not (= [shape-name path name] [(:name head) (:path component) (:name component)])
                  (oracle/fail :I9 {:phase :requested-rename :expected [shape-name path name]
                                    :actual [(:name head) (:path component) (:name component)]})))
              (when-not (= (get-in resolved [:op :annotation]) (:annotation component))
                (oracle/fail :I9 {:phase :requested-annotation :expected (get-in resolved [:op :annotation])
                                  :actual (:annotation component)})))))
        ;; only an annotation edit, or its undo or redo, changes an annotation
        (when-not (#{:annotate :undo :redo :library-undo :library-redo} kind)
          (let [annotations (fn [situation]
                              (into {} (for [file (cons (tm/file situation) (vals (tm/aux-files situation)))
                                             [id component] (get-in file [:data :components])]
                                         [[(:id file) id] (:annotation component)])))
                before (annotations situation)
                kept (annotations after)
                changed (filterv #(and (contains? kept %) (not= (get before %) (get kept %))) (keys before))]
            (when (seq changed)
              (oracle/fail :I9 {:phase :kept-annotation :components changed
                                :before (select-keys before changed) :after (select-keys kept changed)}))))
        (when (#{:text :text-style :library-text :library-text-style} kind)
          (let [after (if library-id (source-context after library-id) after)
                actual (tm/shape-by-id after (get-in resolved [:shape :id]))
                expected (thtx/normalize (:value op))]
            (when-not (= expected (thtx/view (:content actual)))
              (oracle/fail :I9 {:phase :requested-text :expected expected
                                :actual (thtx/view (:content actual))}))))
        (when (#{:edit :style :library-edit} kind)
          (let [file (if library-id (get (cf/all-files after) library-id) (tm/file after))
                actual (tm/shape-by-id (tm/with-file after file) (get-in resolved [:shape :id]))
                expected (n/set-property (:shape resolved) (:property resolved) (:value resolved))]
            (when-not (= (get expected (:property resolved)) (get actual (:property resolved)))
              (oracle/fail :I9 {:phase :requested-scalar :property (:property resolved)}))))
        (oracle/check after next-model (empty? (r/pending-components after)))
        (case kind
          :sync (cond-> state
                  (seq history) (assoc-in [:undo (dec (count history)) :after] next-model))
          :undo (-> state (assoc :undo (pop history)) (update :redo conj (peek history)))
          :redo (-> state (assoc :redo (pop redo)) (update :undo conj (peek redo)))
          (cond-> state
            changed (-> (assoc :checkpoint (oracle/history-snapshot situation) :redo [])
                        (update :undo conj {:before model :after next-model
                                            :reference-start (reference-snapshot situation)}))))))))

(defn initial-state
  [world-spec]
  (let [situation (world world-spec)]
    {:situation situation :baseline (oracle/history-snapshot situation)
     :model {} :trace [] :undo [] :redo [] :executed {} :coverage {}}))

(defn- check-sequence
  [{:keys [situation baseline undo]}]
  (let [depth (r/undo-depth situation)
        undone (reduce (fn [situation entry]
                         (r/step (restore-references situation (:reference-before entry)) (n/undo)))
                       situation (rseq undo))
        redone (reduce (fn [situation entry]
                         (r/step (restore-references situation (:reference-after entry)) (n/redo)))
                       undone undo)]
    (when-not (= depth (count undo))
      (oracle/fail :I7 {:phase :history-depth :runner depth :model (count undo)}))
    (when-not (if (:accepted-libraries situation)
                (uc/same-state? (get baseline (:id (tm/file situation)))
                                (get (oracle/history-snapshot undone) (:id (tm/file situation))))
                (uc/same-state? baseline (oracle/history-snapshot undone)))
      (oracle/fail :I7 {:phase :sequence-undo}))
    (when-not (uc/same-state? (oracle/history-snapshot situation) (oracle/history-snapshot redone))
      (oracle/fail :I7 {:phase :sequence-redo}))))

(defn- check-source-sequences
  [situation]
  (doseq [[owner {:keys [undo baseline foreign-write]}] (:library-histories situation)]
    (let [editor (source-context situation owner)
          undone (reduce (fn [editor entry]
                           (r/step (restore-references editor (:reference-before entry)) (n/undo)))
                         editor (rseq undo))
          redone (reduce (fn [editor entry]
                           (r/step (restore-references editor (:reference-after entry)) (n/redo)))
                         undone undo)]
      (when-not (= (count undo) (r/undo-depth editor))
        (oracle/fail :I7 {:phase :source-history-depth}))
      (when (and (not foreign-write) (not= baseline (get (oracle/history-snapshot undone) owner)))
        (oracle/fail :I7 {:phase :source-sequence-undo}))
      (when-not (= (oracle/history-snapshot editor) (oracle/history-snapshot redone))
        (oracle/fail :I7 {:phase :source-sequence-redo})))))

(defn run
  "Run a program. A supplied trace must replay the same targets and skips.
   Failures include the program and labelled trace for a regression.
   Whole-program checks have no single failing operation."
  ([program] (run program nil))
  ([{:keys [prefix ops] world-spec :world :as program} replay-trace]
   (let [progress (atom (initial-state world-spec))]
     (try
       (doseq [[phase tuple] (concat (map #(vector :setup %) prefix)
                                     (map #(vector :random %) ops))]
         (swap! progress assoc :current tuple :phase phase)
         (swap! progress step tuple))
       (swap! progress settle-state)
       (swap! progress dissoc :current)
       (check-sequence @progress)
       (check-source-sequences (:situation @progress))
       (when-not (pos? (reduce + 0 (vals (dissoc (:executed @progress) :sync))))
         (throw (ex-info "No operations executed" {:type ::empty-program})))
       (when (and replay-trace (not= replay-trace (:trace @progress)))
         (throw (ex-info "Replay changed its targets or skips" {:type ::replay-mismatch})))
       @progress
       (catch #?(:clj Throwable :cljs :default) cause
         (throw (ex-info (str (ex-message cause) "\nReplay: (cg/run " (pr-str program) ")"
                              "\nTrace: " (pr-str (:trace @progress)))
                         (merge {:failing-op (:current @progress)} (ex-data cause)
                                {:program program :trace (:trace @progress)
                                 :coverage (:coverage @progress)})
                         cause)))))))

(defn check-program
  "Reject vacuous shrinks and changes of failure signature. test.check must
   minimize the original invariant failure, rather than a new harness error."
  [program coverage failure]
  (try
    (let [result (run program)]
      (swap! coverage #(merge-with (partial merge-with (partial merge-with +)) % (:coverage result)))
      true)
    (catch #?(:clj Throwable :cljs :default) error
      (let [data (ex-data error)
            signature [(:invariant data) (:type data) (:phase data)
                       (first (:failing-op data))]]
        (cond
          (= ::empty-program (:type data))
          (do
            (swap! coverage #(merge-with (partial merge-with (partial merge-with +)) % (:coverage data)))
            true)
          (or (= signature @failure) (compare-and-set! failure nil signature))
          (throw error)
          :else true)))))

(def ^:private world-dependent-kinds
  "Kinds that only grid worlds offer, a share of the programs: they must
   change state, but need no share of the changes."
  #{:grid-cell :grid-direction})

(defn coverage-errors
  "Coverage comes only from random steps. Stable syncs count as checks;
   mutations must change state and each required kind must supply at least
   0.5% of meaningful changes, but those only some worlds offer. Setup,
   skips and no-ops cannot satisfy it."
  ([coverage kinds] (coverage-errors coverage kinds #{:sync :library-sync}))
  ([coverage kinds checks]
   (let [random (:random coverage)
         mutations (remove checks kinds)
         total-changed (reduce + 0 (map #(get-in random [% :changed] 0) mutations))
         attempted (reduce + 0 (map #(get-in random [% :attempted] 0) mutations))]
     (cond-> (into []
                   (keep (fn [kind]
                           (let [{:keys [changed no-op] :or {changed 0 no-op 0}} (get random kind)]
                             (when (if (contains? checks kind)
                                     (zero? (+ changed no-op))
                                     (or (zero? changed)
                                         (and (not (contains? world-dependent-kinds kind))
                                              (< changed (* 0.005 total-changed)))))
                               {:kind kind :reason :insufficient-random-coverage
                                :counts (get random kind)}))))
                   kinds)
       (or (zero? total-changed) (< total-changed (* 0.1 attempted)))
       (conj {:reason :insufficient-random-mutations :changed total-changed :attempted attempted})))))

(defn operation-gen
  "Random single actions and short user histories. Every chunk can shrink
   away; none is a required prefix. Pairs expose operations whose targets
   otherwise rarely exist, while standalone actions still exercise skips.
   A program has up to PENPOT_GEN_HISTORIES chunks, 40 by default."
  [kinds]
  (let [tuple (gen/tuple (gen/elements kinds) gen/nat gen/nat gen/nat gen/nat)
        histories (cond-> [[24 (gen/fmap vector tuple)]]
                    (contains? (set kinds) :style)
                    (conj [2 (gen/fmap (fn [[index property]] [[:style index property 1]])
                                       (gen/tuple gen/nat (gen/choose 0 (dec (count style-edits)))))])
                    (contains? (set kinds) :text-style)
                    (conj [2 (gen/fmap (fn [[index unit]] [[:text-style index unit 1]])
                                       (gen/tuple gen/nat (gen/choose 0 (dec (count text-style-edits)))))])
                    (contains? (set kinds) :library-transform)
                    (conj [2 (gen/fmap (fn [[index action scope]] [[:library-transform index action 4 scope]])
                                       (gen/tuple gen/nat (gen/choose 0 5) (gen/choose 0 1)))])
                    (contains? (set kinds) :library-text-style)
                    (conj [2 (gen/fmap (fn [[index unit]] [[:library-text-style index unit 1]])
                                       (gen/tuple gen/nat (gen/choose 0 (dec (count text-style-edits)))))])
                    (contains? (set kinds) :library-text)
                    (conj [2 (gen/fmap (fn [index] [[:library-text index 0 1]]) gen/nat)])
                    (every? (set kinds) [:undo :redo])
                    (conj [1 (gen/return [[:undo] [:redo]])])
                    (contains? (set kinds) :text)
                    (conj [1 (gen/fmap (fn [[index unit value]] [[:text index unit value]])
                                       (gen/tuple gen/nat gen/nat (gen/choose 1 2)))])
                    (every? (set kinds) [:library-undo :library-redo])
                    (conj [1 (gen/return [[:library-undo] [:library-redo]])])
                    (every? (set kinds) [:delete :restore])
                    (conj [1 (gen/fmap (fn [index] [[:delete index] [:restore index]]) gen/nat)])
                    (every? (set kinds) [:edit :update-main])
                    (conj [1 (gen/fmap (fn [index] [[:edit index 0 1 1] [:update-main index]]) gen/nat)])
                    (every? (set kinds) [:edit :reset])
                    (conj [2 (gen/fmap (fn [index] [[:edit index 1 1 :copy-head] [:reset index]]) gen/nat)])
                    (every? (set kinds) [:library-edit :library-sync])
                    (conj [2 (gen/fmap (fn [[index value]]
                                         [[:library-edit index 0 value]
                                          [:edit index 1 1 2] [:library-sync]])
                                       (gen/tuple gen/nat (gen/choose 0 2)))])
                    (every? (set kinds) [:library-edit :library-accept :library-sync])
                    (conj [2 (gen/fmap (fn [[index value]]
                                         [[:library-edit index 0 value] [:library-accept index] [:library-sync index]])
                                       (gen/tuple gen/nat (gen/choose 1 2)))])
                    (every? (set kinds) [:library-delete :library-restore])
                    (conj [1 (gen/fmap (fn [index]
                                         [[:library-delete index] [:library-sync]
                                          [:library-restore index] [:library-sync]]) gen/nat)])
                    (every? (set kinds) [:structure :add-child])
                    (conj [6 (gen/fmap (fn [[parent index edit value]]
                                         [[:add-child parent] [:structure index edit value]])
                                       (gen/tuple gen/nat gen/nat (gen/choose 0 (dec (count structure-edits))) gen/nat))])
                    (every? (set kinds) [:apply-token :token-value])
                    (conj [6 (gen/fmap (fn [[index token value]]
                                         [[:apply-token index token] [:token-value 0 token value]])
                                       (gen/tuple gen/nat gen/nat gen/nat))])
                    (every? (set kinds) [:apply-token :style])
                    (conj [3 (gen/fmap (fn [[index token value]]
                                         ;; a copy that applies a token and then sets a radius by hand
                                         [[:apply-token index token] [:style index 3 value 1]])
                                       (gen/tuple gen/nat gen/nat gen/nat))])
                    (contains? (set kinds) :grid-track)
                    (conj [6 (gen/fmap (fn [[index edit value scope]] [[:grid-track index edit value scope]])
                                       (gen/tuple gen/nat (gen/choose 0 (dec (count grid-track-edits))) gen/nat gen/nat))])
                    (every? (set kinds) [:grid-direction :grid-cell])
                    (conj [2 (gen/fmap (fn [[index cell props direction]]
                                         [[:grid-direction index 0 direction 1] [:grid-cell index cell props 1]])
                                       (gen/tuple gen/nat gen/nat gen/nat (gen/choose 0 1)))])
                    (every? (set kinds) [:variant-property :switch])
                    (conj [3 (gen/fmap (fn [[index selector choice other target]]
                                         [[:variant-property index selector choice other] [:switch target]])
                                       (gen/tuple gen/nat gen/nat gen/nat gen/nat gen/nat))])
                    (every? (set kinds) [:library-variant-property :library-sync :switch])
                    (conj [3 (gen/fmap (fn [[index selector choice other target]]
                                         [[:library-variant-property index selector choice other]
                                          [:library-sync] [:switch target]])
                                       (gen/tuple gen/nat gen/nat gen/nat gen/nat gen/nat))])
                    (every? (set kinds) [:library-add-variant :library-make-variant :library-combine
                                         :library-sync :switch])
                    (conj [2 (gen/fmap (fn [[kind index target]] [[kind index] [:library-sync] [:switch target]])
                                       (gen/tuple (gen/elements [:library-add-variant :library-make-variant
                                                                 :library-combine])
                                                  gen/nat gen/nat))])
                    (every? (set kinds) [:make-variant :switch])
                    (conj [1 (gen/fmap (fn [[index target]] [[:make-variant index] [:switch target]])
                                       (gen/tuple gen/nat gen/nat))])
                    (every? (set kinds) [:combine :switch])
                    (conj [1 (gen/fmap (fn [[index target]] [[:combine index] [:switch target]])
                                       (gen/tuple gen/nat gen/nat))])
                    (every? (set kinds) [:add-variant :switch])
                    (conj [1 (gen/fmap (fn [[index target]] [[:add-variant index] [:switch target]])
                                       (gen/tuple gen/nat gen/nat))])
                    (every? (set kinds) [:library-add-child :library-sync])
                    (conj [1 (gen/fmap (fn [index] [[:library-add-child index] [:library-sync]]) gen/nat)]))]
    (gen/fmap #(vec (mapcat identity %))
              (gen/vector (gen/frequency histories) 0 (or (smt/environment-number "PENPOT_GEN_HISTORIES") 40)))))

(def tuple-gen
  (gen/tuple (gen/elements [:edit :edit :edit :reset :update-main :detach :sync :undo :redo])
             gen/nat gen/nat gen/nat gen/nat))

(def program-gen
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 0 2)
            ops (operation-gen [:edit :reset :update-main :detach :sync :undo :redo])]
    {:world [components depth]
     :ops ops}))

(def structure-kinds
  [:edit :reset :update-main :detach :sync :undo :redo :text :text-style :add-child
   :remove-child :duplicate :nest :delete :restore :resize :rotate :style :transform
   :grid-direction :grid-cell :move :rename :annotate :paste])

(def structure-program-gen
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 0 2)
            layout (gen/elements [nil :flex :grid])
            layout-children gen/boolean
            layout-item-margins gen/boolean
            grid (gen/elements [nil :square :empty])
            ops (operation-gen structure-kinds)]
    {:world [components depth {:text true :text-styles true :layout layout :layout-children layout-children :layout-item-margins layout-item-margins
                               :grid-square (= grid :square) :grid-empty (= grid :empty)}]
     :ops ops}))

(def swap-kinds (into structure-kinds [:swap :switch]))

(def swap-program-gen
  (gen/let [components (gen/choose 2 3)
            differ? gen/boolean
            layout (gen/elements [nil :flex :grid])
            layout-children gen/boolean
            layout-item-margins gen/boolean
            grid (gen/elements [nil :square :empty])
            ops (operation-gen swap-kinds)]
    {:world [components 2 {:variants true :variant-diff differ? :rich-variants true
                           :text true :text-styles true :layout layout :layout-copies true
                           :layout-children layout-children :layout-item-margins layout-item-margins
                           :grid-square (= grid :square) :grid-empty (= grid :empty)}]
     :ops ops}))

(def variant-kinds (into swap-kinds [:variant-property :add-variant :make-variant :combine]))

(def variant-coverage-kinds
  "The kinds the variant programs must cover; the swap programs cover the rest."
  [:variant-property :add-variant :make-variant :combine :switch :undo :redo :sync])

(def variant-program-gen
  (gen/let [components (gen/choose 2 3)
            differ? gen/boolean
            layout (gen/elements [nil :flex :grid])
            layout-children gen/boolean
            layout-item-margins gen/boolean
            grid (gen/elements [nil :square :empty])
            ops (operation-gen variant-kinds)]
    {:world [components 2 {:variants true :variant-diff differ? :rich-variants true
                           :text true :text-styles true :layout layout :layout-copies true
                           :layout-children layout-children :layout-item-margins layout-item-margins
                           :grid-square (= grid :square) :grid-empty (= grid :empty)}]
     :ops ops}))

(def scale-coverage-kinds
  "The kinds the scale programs must cover: the steps that walk every copy
   and every nesting level."
  [:edit :reset :update-main :sync :swap :switch :undo :redo])

(def scale-program-gen
  "Worlds deeper and wider than the other properties draw: up to four
   components nested three or four levels, with up to six copies each."
  (gen/let [components (gen/choose 3 4)
            depth (gen/choose 3 4)
            copies (gen/choose 3 6)
            differ? gen/boolean
            layout (gen/elements [nil :flex :grid])
            layout-children gen/boolean
            grid (gen/elements [nil :square :empty])
            ops (operation-gen variant-kinds)]
    {:world [components depth {:scale true :copies copies :variants true :variant-diff differ?
                               :rich-variants true :text true :text-styles true :layout layout
                               :layout-copies true :layout-children layout-children
                               :grid-square (= grid :square) :grid-empty (= grid :empty)}]
     :ops ops}))

(def shape-structure-kinds (into swap-kinds [:structure]))

(def shape-structure-coverage-kinds
  "The kinds the shape structure programs must cover: the structure edits,
   the children they act on and the steps that carry them into copies."
  [:structure :add-child :edit :update-main :sync :undo :redo])

(def shape-structure-program-gen
  "Histories that group, mask, combine and convert the shapes mains hold."
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 0 2)
            layout (gen/elements [nil :flex :grid])
            layout-children gen/boolean
            ops (operation-gen shape-structure-kinds)]
    {:world [components depth {:variants true :rich-variants true :text true :layout layout
                               :layout-copies true :layout-children layout-children}]
     :ops ops}))

(def token-kinds (into swap-kinds [:apply-token :unapply-token :token-value]))

(def token-coverage-kinds
  "The kinds the token programs must cover: applying, taking off and
   editing tokens, and the steps that carry them into copies."
  [:apply-token :unapply-token :token-value :style :update-main :reset :swap :sync :undo :redo])

(def token-program-gen
  "Histories that apply border radius tokens to mains and copies, take them
   off, set radii by hand and edit the tokens' values."
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 0 2)
            layout (gen/elements [nil :flex :grid])
            ops (operation-gen token-kinds)]
    {:world [components depth {:tokens true :variants true :rich-variants true :text true
                               :layout layout :layout-copies true}]
     :ops ops}))

(def grid-kinds (into swap-kinds [:grid-track]))

(def grid-coverage-kinds
  "The kinds the grid programs must cover: the grid edits and the steps that
   carry them into copies."
  [:grid-track :grid-cell :grid-direction :edit :update-main :sync :undo :redo])

(def grid-program-gen
  "Worlds whose first component is a grid, with copies in grids, and
   histories that edit its tracks and cells."
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 1 2)
            layout-children gen/boolean
            layout-item-margins gen/boolean
            grid (gen/elements [nil :square :empty])
            ops (operation-gen grid-kinds)]
    {:world [components depth {:variants true :rich-variants true :text true :layout :grid :layout-copies true
                               :layout-children layout-children :layout-item-margins layout-item-margins
                               :grid-square (= grid :square) :grid-empty (= grid :empty)}]
     :ops ops}))

(def library-kinds
  (into structure-kinds [:swap :switch :library-sync]))

(def library-program-gen
  (gen/let [depth (gen/choose 0 2)
            layout (gen/elements [nil :flex :grid])
            layout-children gen/boolean
            layout-item-margins gen/boolean
            grid (gen/elements [nil :square :empty])
            ops (operation-gen library-kinds)]
    {:world [2 depth {:library true :variants true :rich-variants true :text true :text-styles true
                      :layout layout :layout-copies true :layout-children layout-children :layout-item-margins layout-item-margins
                      :grid-square (= grid :square) :grid-empty (= grid :empty)}]
     :ops ops}))

(def stale-library-kinds
  (into (vec (remove #{:update-main} library-kinds)) (sort (disj library-source-kinds :library-accept))))

(def stale-library-coverage-kinds
  "The kinds the stale library programs must cover: the library editor's and
   the consumer's library steps; the structure and swap programs cover the
   rest."
  (into [:edit :reset :swap :switch :detach :sync :undo :redo :library-sync]
        (sort (disj library-source-kinds :library-accept))))

(def stale-library-program-gen
  (gen/let [depth (gen/choose 0 2)
            library-depth (gen/choose 1 2)
            layout (gen/elements [nil :flex :grid])
            layout-children gen/boolean
            layout-item-margins gen/boolean
            grid (gen/elements [nil :square :empty])
            ops (operation-gen stale-library-kinds)]
    {:world [2 depth {:library true :stale-libraries true :library-variants true :library-text true
                      :library-depth library-depth :variants true :rich-variants true :text true :text-styles true
                      :layout layout :layout-copies true :layout-children layout-children :layout-item-margins layout-item-margins
                      :grid-square (= grid :square) :grid-empty (= grid :empty)}]
     :ops ops}))

(def dependency-kinds
  [:edit :reset :swap :sync :undo :redo :library-sync :library-edit :library-accept
   :library-add-child :library-remove-child :library-delete :library-restore :library-undo :library-redo
   :library-transform :library-text :library-text-style])

(def dependency-program-gen
  (gen/let [depth (gen/choose 0 1)
            ops (operation-gen dependency-kinds)]
    {:world [2 depth {:library true :stale-libraries true :library-dependency true :library-text true}]
     :ops ops}))
