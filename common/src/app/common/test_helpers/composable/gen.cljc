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
   [app.common.geom.modifiers :as gm]
   [app.common.geom.shapes :as gsh]
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
   [app.common.test-helpers.composable.variants :as variants]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
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

(defn- with-library
  [situation opts]
  (let [library (-> (tm/make-situation (thf/sample-file :random-library :name "random-library" :is-shared true))
                    (as-> s (tm/apply-to (n/create-component "library-random" setup/labeled-fill :size 100) s))
                    (as-> s (tm/apply-to (n/make-nested-component "library-random" (or (:library-depth opts) 1)) s))
                    (as-> s (if (:library-variants opts)
                              (tm/apply-to (n/make-variant-container "library-variants"
                                                                     [["a" setup/labeled-fill] ["b" "#ff0000"]]
                                                                     :size 100) s)
                              s))
                    (as-> s (if (and (:library-variants opts) (:rich-variants opts))
                              (variants/diversify s "library-variants") s))
                    tm/file)
        dependent (when (:library-dependency opts)
                    (-> (tm/make-situation (thf/sample-file :dependent-library :name "dependent-library" :is-shared true))
                        (as-> s (tm/apply-to (n/create-component "library-dependent" setup/labeled-fill :size 100) s))
                        tm/file
                        (thc/instantiate-component :sync-library-random-component :library-dependency-copy
                                                   :parent-label :sync-library-dependent-head :library library)))
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

(defn world
  [[components depth opts]]
  (thi/reset-idmap!)
  (cond-> (reduce (fn [s index]
                    (let [name (str "random-" index)]
                      (-> (tm/apply-to (n/create-component name setup/labeled-fill :size 100) s)
                          (with-layout name (when (zero? index) (:layout opts)))
                          (as-> s (reduce #(tm/apply-to %2 %1) s
                                          [(n/make-nested-component name depth)]))
                          (with-outer-layout name (when (and (zero? index) (:layout-copies opts)) (:layout opts)))
                          (as-> s (reduce #(tm/apply-to %2 %1) s [(n/instantiate-copy name) (n/instantiate-copy name)])))))
                  (let [s (setup/empty-situation)
                        s (if (:variants opts)
                            (let [s (reduce #(tm/apply-to %2 %1) s
                                            [(n/create-component "variant" setup/labeled-fill :size 100)
                                             (n/make-variant-container "random-variants"
                                                                       (variants/members setup/labeled-fill (:rich-variants opts)
                                                                                         (:variant-diff opts))
                                                                       :size 100)])
                                  s (cond-> s (:rich-variants opts) (variants/diversify "random-variants"))]
                              (reduce #(tm/apply-to %2 %1) s
                                      [(n/make-nested-component-with-variant "variant" "random-variants" "a")
                                       (n/make-nested-component "variant" 1)
                                       (n/instantiate-copy "variant")
                                       (n/instantiate-copy "variant")]))
                            s)]
                    (cond-> s
                      (:text opts)
                      (tm/with-file (-> (thtx/add-text-component-with-copy (tm/file s) [[["Hello world"]]])
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
    (:library opts) (with-library opts)
    (:stale-libraries opts) (cf/freeze-libraries)
    (:rich-variants opts) (as-> s (assoc s :shape-budget (+ 80 (count (tree s)))))))

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
                              :update-main (and (top-copy shape) (live-component shape) (overridden (:id shape)))
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
                              :remove-child (and (= :rect (:type shape)) (nil? (:shape-ref shape))
                                                 (some ctk/main-instance? (cfh/get-parents-with-self objects (:id shape))))
                              :duplicate (and (top-copy shape) (< (count objects) (get situation :shape-budget 120))
                                              (cld/allow-duplicate? objects shape))
                              :nest (and (top-copy shape) (not (overridden (:id shape))))
                              :delete (ctk/main-instance? shape)
                              (:swap :switch) (and (copy-head shape) (live-component shape))
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
      :nest (vec (for [entry eligible
                       parent (tree situation)
                       :let [parent-shape (:shape parent)]
                       :when (and (ctk/main-instance? parent-shape)
                                  (not= (:parent-id (:shape entry)) (:id parent-shape))
                                  (not (ctn/invalid-structure-for-component?
                                        objects parent-shape [(:shape entry)] false libraries)))]
                   (assoc entry :into (:path parent) :parent (:shape parent))))
      eligible)))

(def library-source-kinds
  #{:library-edit :library-add-child :library-remove-child :library-delete :library-restore
    :library-undo :library-redo :library-accept})

(defn- library-candidates
  [situation kind]
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
                 (filter (fn [{:keys [shape]}]
                           (case kind
                             (:library-edit :library-remove-child)
                             (and (= :rect (:type shape)) (nil? (:shape-ref shape)))
                             (:library-add-child :library-delete) (ctk/main-instance? shape)))
                         (tree (tm/with-file situation library))))]
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
   [:layout-align-items [:start :center :end]]])

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
                         (library-candidates situation kind)
                         (candidates situation model (if (= kind :style) :edit kind) scope))
                (= kind :style)
                (filter (fn [{:keys [shape]}]
                          (and (shadow/attribute-applicable? shape style-property)
                               (case style-property
                                 (:layout-gap :layout-padding :layout-align-items) (some? (:layout shape))
                                 :layout-item-margin (some? (:layout (tm/shape-by-id situation (:parent-id shape))))
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
             (when target
               (case kind
                 (:edit :style) (n/change-property target property value)
                 :transform (let [action (nth [:move :width :height :rotate :flip-x :flip-y]
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
                 :text (let [text (thtx/view (:content (:shape selected)))
                             text (if (even? (or (nth tuple 2 nil) 0))
                                    (thtx/set-letters text (nth ["Hello world" "Hello there" "Hello\nworld"] (mod (or (nth tuple 3 nil) 0) 3)))
                                    (thtx/restyle text :size (nth [14 18 24] (mod (or (nth tuple 3 nil) 0) 3))))]
                         (n/edit-text target :content text))
                 :text-style (n/edit-text target :content
                                          (edit-text-style (:shape selected) (nth tuple 2 nil) (nth tuple 3 nil))
                                          (when (and (= 8 (mod (or (nth tuple 2 nil) 0) (count text-style-edits)))
                                                     (odd? (or (nth tuple 3 nil) 0)))
                                            {:typography {:id (thi/id :generated-typography)
                                                          :file-id (:id (tm/file situation))}}))
                 :add-child (n/add-child target (keyword (str "generated-" (count (:applied situation))))
                                         :width 20 :height 20 :x 10 :y 10)
                 :remove-child (n/remove-child target)
                 :duplicate (n/duplicate target)
                 :nest (n/nest-copy target (constantly (get-in selected [:parent :id])))
                 :delete (n/delete-component target)
                 :swap (n/swap-shape target (constantly (get-in selected [:destination :shape :component-id])))
                 :switch (n/switch-variant target (:variant-value selected) :pos (:variant-pos selected))
                 :restore (n/restore-component (n/delete-component target)))))]
    {:op op :library-id (:library-id selected) :shape (:shape selected)
     :destination (:shape (:destination selected)) :property property :value value
     :opportunities (if (seq items) (count items) (if (or op (= kind :sync)) 1 0))
     :trace (cond-> {:kind kind :tuple tuple
                     :status (if (or op (= kind :sync)) :executed :skip)}
              selected (assoc :target (:path selected))
              (:into selected) (assoc :into (:into selected))
              (:destination selected) (assoc :to (get-in selected [:destination :path]))
              (= kind :switch) (assoc :property-pos (:variant-pos selected))
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
            (if (= (get-in before [key :shape]) (get-in after [key :shape]))
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
    (when-not (= before (oracle/snapshot undone))
      (oracle/fail :I7 {:phase :undo :diff (thn/diff before (oracle/snapshot undone))}))
    (when-not (= (oracle/snapshot after) (oracle/snapshot redone))
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
        model (-> model
                  (shadow/parent-orientation (all-objects situation) (all-objects settled))
                  (shadow/settle (all-objects settled)))
        all (into (set (synchronizable-components situation)) (r/pending-components situation))
        full-before (:situation (cf/sync-world situation all))
        full (:situation (cf/sync-world settled all))
        opts {:free-head-placement false}
        settled-design (oracle/canonical settled opts)
        full-before-design (oracle/canonical full-before opts)
        full-design (oracle/canonical full opts)
        journal (when checkpoint
                  (merge (peek (:undo state))
                         (reference-patches (:reference-start (peek (:undo state)))
                                            (reference-snapshot settled))
                         {:after model}))]
    (oracle/check settled model)
    (when-not (oracle/equivalent? settled-design full-before-design)
      (oracle/fail :I6 {:diff (thn/diff settled-design full-before-design)}))
    (when-not (oracle/equivalent? settled-design full-design)
      (oracle/fail :I5 {:diff (thn/diff settled-design full-design)}))
    (when checkpoint (assert-round-trip checkpoint settled journal))
    (cond-> (assoc state :situation settled :model model :checkpoint nil)
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
                        {:undo [] :redo [] :baseline (get (oracle/snapshot before) owner)})
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
    (when entry (assert-round-trip (oracle/snapshot before) after entry))
    (when-not (= (dissoc (oracle/snapshot before) owner) (dissoc (oracle/snapshot after) owner))
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
                    :transform (n/geometry-modifiers shape (:action op) (:value op)))
        tree (gm/set-objects-modifiers {(:id shape) {:modifiers modifiers}} objects)]
    (gsh/transform-shape shape (get-in tree [(:id shape) :modifiers]))))

(defn- update-model
  [model before after {:keys [shape property value op]} kind]
  (let [objects (all-objects before)
        id (:id shape)]
    (case kind
      (:edit :style) (shadow/edit model shape property value)
      :reset (shadow/clear-subtree model objects id)
      :update-main (shadow/update-main model objects (all-objects after) id)
      :detach (shadow/detach model objects (all-objects after) id)
      :nest (let [parent (get objects (tm/target-shape-id before (:into op)))]
              (if (:blocked parent)
                (shadow/edit model shape :blocked true)
                model))
      (:text :text-style) (shadow/edit-text model shape (:content (n/text-edit-commit shape (:edit op) (:value op)))
                                            (some? (:typography op)))
      :duplicate (shadow/duplicate model objects id
                                   (:objects (thf/current-page (tm/file after)))
                                   (:new-head (n/applied-result op (tm/file before) (tm/file after))))
      (:swap :switch)
      (let [after-objects (all-objects after)
            old-main (get-in (cf/libraries before) [(:component-file shape) :data :components
                                                    (:component-id shape) :main-instance-id])
            head (get after-objects id)
            new-main (get-in (cf/libraries after) [(:component-file head) :data :components
                                                   (:component-id head) :main-instance-id])]
        (shadow/swap model objects after-objects id old-main new-main (= kind :switch)))
      (:resize :rotate :transform)
      (let [shape-after (requested-geometry before {:shape shape :op op} kind)
            group :geometry-group
            parent (get objects (:parent-id shape))
            position? (and (:shape-ref parent)
                           (not (contains? (oracle/derived-attributes objects shape) :x)))
            changed? (or (not (shadow/same-value? shape-after group (shadow/values shape group)))
                         (and position? (not (gsh/close-attrs? :points (:points shape) (:points shape-after) 1e-10))))]
        (if (and (:shape-ref shape) changed?)
          (assoc-in model [id group] (shadow/author-geometry shape-after (when position? parent)))
          model))
      model)))

(defn step
  [state tuple]
  (let [[kind index] tuple
        state (if (= kind :sync) state (settle-state state))
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
                                  (not= (get (oracle/snapshot situation) owner)
                                        (get (oracle/snapshot after) owner))
                                  (assoc-in [:library-histories owner :foreign-write] true)))
                              after (keys (:library-histories situation))))
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
            meaningful (not (oracle/equivalent? (oracle/design-snapshot situation {:free-head-placement false})
                                                (oracle/design-snapshot after {:free-head-placement false})))
            state (-> state
                      (assoc :situation after :model next-model)
                      (update-in (conj coverage-path (if meaningful :changed :no-op)) (fnil inc 0))
                      (cond-> (and (not meaningful)
                                   (> (r/undo-depth after) (r/undo-depth situation)))
                        (update-in (conj coverage-path :undo-only) (fnil inc 0)))
                      (update-in [:executed kind] (fnil inc 0)))]
        (when (#{:resize :rotate :transform} kind)
          (oracle/check-requested-geometry (:objects (thf/current-page (tm/file after)))
                                           (tm/shape-by-id after (get-in resolved [:shape :id]))
                                           (requested-geometry situation resolved kind)))
        (when (#{:swap :switch} kind)
          (let [head (tm/shape-by-id after (get-in resolved [:shape :id]))]
            (when-not (= (get-in resolved [:destination :component-id]) (:component-id head))
              (oracle/fail :I9 {:phase :requested-component :kind kind}))))
        (when (#{:text :text-style} kind)
          (let [actual (tm/shape-by-id after (get-in resolved [:shape :id]))
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
            changed (-> (assoc :checkpoint (oracle/snapshot situation) :redo [])
                        (update :undo conj {:before model :after next-model
                                            :reference-start (reference-snapshot situation)}))))))))

(defn initial-state
  [world-spec]
  (let [situation (world world-spec)]
    {:situation situation :baseline (oracle/snapshot situation)
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
                (= (get baseline (:id (tm/file situation)))
                   (get (oracle/snapshot undone) (:id (tm/file situation))))
                (= baseline (oracle/snapshot undone)))
      (oracle/fail :I7 {:phase :sequence-undo}))
    (when-not (= (oracle/snapshot situation) (oracle/snapshot redone))
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
      (when (and (not foreign-write) (not= baseline (get (oracle/snapshot undone) owner)))
        (oracle/fail :I7 {:phase :source-sequence-undo}))
      (when-not (= (oracle/snapshot editor) (oracle/snapshot redone))
        (oracle/fail :I7 {:phase :source-sequence-redo})))))

(defn run
  "Run a program. A supplied trace must replay the same targets and skips.
   Failures include the program and labelled trace for a regression."
  ([program] (run program nil))
  ([{:keys [prefix ops] world-spec :world :as program} replay-trace]
   (let [progress (atom (initial-state world-spec))]
     (try
       (doseq [[phase tuple] (concat (map #(vector :setup %) prefix)
                                     (map #(vector :random %) ops))]
         (swap! progress assoc :current tuple :phase phase)
         (swap! progress step tuple))
       (swap! progress settle-state)
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
                         (assoc (ex-data cause) :program program :trace (:trace @progress)
                                :coverage (:coverage @progress)
                                :failing-op (:current @progress))
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

(defn coverage-errors
  "Coverage comes only from random steps. Stable syncs count as checks;
   mutations must change state and each required kind must supply at least
   0.5% of meaningful changes. Setup, skips and no-ops cannot satisfy it."
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
                                         (< changed (* 0.005 total-changed))))
                               {:kind kind :reason :insufficient-random-coverage
                                :counts (get random kind)}))))
                   kinds)
       (or (zero? total-changed) (< total-changed (* 0.1 attempted)))
       (conj {:reason :insufficient-random-mutations :changed total-changed :attempted attempted})))))

(defn operation-gen
  "Random single actions and short user histories. Every chunk can shrink
   away; none is a required prefix. Pairs expose operations whose targets
   otherwise rarely exist, while standalone actions still exercise skips."
  [kinds]
  (let [tuple (gen/tuple (gen/elements kinds) gen/nat gen/nat gen/nat gen/nat)
        histories (cond-> [[24 (gen/fmap vector tuple)]]
                    (contains? (set kinds) :style)
                    (conj [2 (gen/fmap (fn [[index property]] [[:style index property 1]])
                                       (gen/tuple gen/nat (gen/choose 0 (dec (count style-edits)))))])
                    (contains? (set kinds) :text-style)
                    (conj [2 (gen/fmap (fn [[index unit]] [[:text-style index unit 1]])
                                       (gen/tuple gen/nat (gen/choose 0 (dec (count text-style-edits)))))])
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
                    (every? (set kinds) [:library-add-child :library-sync])
                    (conj [1 (gen/fmap (fn [index] [[:library-add-child index] [:library-sync]]) gen/nat)]))]
    (gen/fmap #(vec (mapcat identity %)) (gen/vector (gen/frequency histories) 0 40))))

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
   :remove-child :duplicate :nest :delete :restore :resize :rotate :style :transform])

(def structure-program-gen
  (gen/let [components (gen/choose 2 3)
            depth (gen/choose 0 2)
            layout (gen/elements [nil :flex :grid])
            ops (operation-gen structure-kinds)]
    {:world [components depth {:text true :text-styles true :layout layout}]
     :ops ops}))

(def swap-kinds (into structure-kinds [:swap :switch]))

(def swap-program-gen
  (gen/let [components (gen/choose 2 3)
            differ? gen/boolean
            layout (gen/elements [nil :flex :grid])
            ops (operation-gen swap-kinds)]
    {:world [components 2 {:variants true :variant-diff differ? :rich-variants true
                           :text true :text-styles true :layout layout :layout-copies true}]
     :ops ops}))

(def library-kinds
  (into structure-kinds [:swap :switch :library-sync]))

(def library-program-gen
  (gen/let [depth (gen/choose 0 2)
            layout (gen/elements [nil :flex :grid])
            ops (operation-gen library-kinds)]
    {:world [2 depth {:library true :variants true :rich-variants true :text true :text-styles true
                      :layout layout :layout-copies true}]
     :ops ops}))

(def stale-library-kinds
  (into (vec (remove #{:update-main} library-kinds)) (sort (disj library-source-kinds :library-accept))))

(def stale-library-program-gen
  (gen/let [depth (gen/choose 0 2)
            library-depth (gen/choose 1 2)
            layout (gen/elements [nil :flex :grid])
            ops (operation-gen stale-library-kinds)]
    {:world [2 depth {:library true :stale-libraries true :library-variants true
                      :library-depth library-depth :variants true :rich-variants true :text true :text-styles true
                      :layout layout :layout-copies true}]
     :ops ops}))

(def dependency-kinds
  [:edit :reset :swap :sync :undo :redo :library-sync :library-edit :library-accept
   :library-add-child :library-remove-child :library-delete :library-restore :library-undo :library-redo])

(def dependency-program-gen
  (gen/let [depth (gen/choose 0 1)
            ops (operation-gen dependency-kinds)]
    {:world [2 depth {:library true :stale-libraries true :library-dependency true}]
     :ops ops}))
