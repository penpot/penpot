;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.migrations
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.defaults :as cfd]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.text :as gsht]
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.svg :as csvg]
   [app.common.text :as txt]
   [app.common.types.shape :as cts]
   [app.common.types.shape.shadow :as ctss]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

#?(:cljs (l/set-level! :info))

(declare ^:private migrations)

(def version cfd/version)

(defn need-migration?
  [file]
  (or (nil? (:version file))
      (not= cfd/version (:version file))))

(defn- apply-migrations
  [data migrations from-version]

  (loop [migrations migrations
         data data]
    (if-let [[to-version migrate-fn] (first migrations)]
      (let [migrate-fn (or migrate-fn identity)]
        (l/inf :hint "migrate file"
               :op (if (>= from-version to-version) "down" "up")
               :file-id (str (:id data))
               :version to-version)
        (recur (rest migrations)
               (migrate-fn data)))
      data)))

(defn migrate-data
  [data migrations from-version to-version]
  (if (= from-version to-version)
    data
    (let [migrations (if (< from-version to-version)
                       (->> migrations
                            (drop-while #(<= (get % :id) from-version))
                            (take-while #(<= (get % :id) to-version))
                            (map (juxt :id :migrate-up)))
                       (->> (reverse migrations)
                            (drop-while #(> (get % :id) from-version))
                            (take-while #(> (get % :id) to-version))
                            (map (juxt :id :migrate-down))))]
      (apply-migrations data migrations from-version))))

(defn fix-version
  "Fixes the file versioning numbering"
  [{:keys [version] :as file}]
  (if (int? version)
    file
    (let [version (or version (-> file :data :version))]
      (-> file
          (assoc :version version)
          (update :data dissoc :version)))))

(defn migrate-file
  [{:keys [id data features version] :as file}]
  (binding [cfeat/*new* (atom #{})]
    (let [version (or version (:version data))
          file    (-> file
                      (assoc :version cfd/version)
                      (update :data (fn [data]
                                      (-> data
                                          (assoc :id id)
                                          (dissoc :version)
                                          (migrate-data migrations version cfd/version))))
                      (update :features (fnil into #{}) (deref cfeat/*new*))
                      ;; NOTE: in some future we can consider to apply
                      ;; a migration to the whole database and remove
                      ;; this code from this function that executes on
                      ;; each file migration operation
                      (update :features cfeat/migrate-legacy-features))]

      (if (or (not= version (:version file))
              (not= features (:features file)))
        (vary-meta file assoc ::migrated true)
        file))))

(defn migrated?
  [file]
  (true? (-> file meta ::migrated)))

;; -- MIGRATIONS --

(defn migrate-up-2
  "Ensure that all :shape attributes on shapes are vectors"
  [data]
  (letfn [(update-object [object]
            (d/update-when object :shapes
                           (fn [shapes]
                             (if (seq? shapes)
                               (into [] shapes)
                               shapes))))
          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

(defn migrate-up-3
  "Changes paths formats"
  [data]
  (letfn [(migrate-path [shape]
            (if-not (contains? shape :content)
              (let [content (gsp/segments->content (:segments shape) (:close? shape))
                    selrect (gsh/content->selrect content)
                    points  (grc/rect->points selrect)]
                (-> shape
                    (dissoc :segments)
                    (dissoc :close?)
                    (assoc :content content)
                    (assoc :selrect selrect)
                    (assoc :points points)))
              ;; If the shape contains :content is already in the new format
              shape))

          (fix-frames-selrects [frame]
            (if (= (:id frame) uuid/zero)
              frame
              (let [selrect (gsh/shape->rect frame)]
                (-> frame
                    (assoc :selrect selrect)
                    (assoc :points (grc/rect->points selrect))))))

          (fix-empty-points [shape]
            (if (empty? (:points shape))
              (-> shape
                  (update :selrect (fn [selrect]
                                     (if (map? selrect)
                                       (grc/make-rect selrect)
                                       selrect)))
                  (cts/setup-shape))
              shape))

          (update-object [object]
            (cond-> object
              (= :curve (:type object))
              (assoc :type :path)

              (#{:curve :path} (:type object))
              (migrate-path)

              (cfh/frame-shape? object)
              (fix-frames-selrects)

              (and (empty? (:points object)) (not= (:id object) uuid/zero))
              (fix-empty-points)))

          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

(defn migrate-up-5
  "Put the id of the local file in :component-file in instances of
  local components"
  [data]
  (letfn [(update-object [object]
            (if (and (some? (:component-id object))
                     (nil? (:component-file object)))
              (assoc object :component-file (:id data))
              object))

          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

(defn migrate-up-6
  "Fixes issues with selrect/points for shapes with width/height = 0 (line-like paths)"
  [data]
  (letfn [(fix-line-paths [shape]
            (if (= (:type shape) :path)
              (let [{:keys [width height]} (grc/points->rect (:points shape))]
                (if (or (mth/almost-zero? width) (mth/almost-zero? height))
                  (let [selrect (gsh/content->selrect (:content shape))
                        points (grc/rect->points selrect)
                        transform (gmt/matrix)
                        transform-inv (gmt/matrix)]
                    (assoc shape
                           :selrect selrect
                           :points points
                           :transform transform
                           :transform-inverse transform-inv))
                  shape))
              shape))

          (update-container [container]
            (update container :objects update-vals fix-line-paths))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-7
  "Remove interactions pointing to deleted frames"
  [data]
  (letfn [(update-object [page object]
            (d/update-when object :interactions
                           (fn [interactions]
                             (filterv #(get-in page [:objects (:destination %)]) interactions))))

          (update-page [page]
            (update page :objects update-vals (partial update-object page)))]

    (update data :pages-index update-vals update-page)))

(defn migrate-up-8
  "Remove groups without any shape, both in pages and components"
  [data]
  (letfn [(clean-parents [obj deleted?]
            (d/update-when obj :shapes
                           (fn [shapes]
                             (into [] (remove deleted?) shapes))))

          (obj-is-empty? [obj]
            (and (= (:type obj) :group)
                 (or (empty? (:shapes obj))
                     (nil? (:selrect obj)))))

          (clean-objects [objects]
            (loop [entries (seq objects)
                   deleted #{}
                   result  objects]
              (let [[id obj :as entry] (first entries)]
                (if entry
                  (if (obj-is-empty? obj)
                    (recur (rest entries)
                           (conj deleted id)
                           (dissoc result id))
                    (recur (rest entries)
                           deleted
                           result))
                  [(count deleted)
                   (d/mapm #(clean-parents %2 deleted) result)]))))

          (clean-container [container]
            (loop [n       0
                   objects (:objects container)]
              (let [[deleted objects] (clean-objects objects)]
                (if (and (pos? deleted) (< n 1000))
                  (recur (inc n) objects)
                  (assoc container :objects objects)))))]

    (-> data
        (update :pages-index update-vals clean-container)
        (update :components update-vals clean-container))))

(defn migrate-up-9
  [data]
  (letfn [(find-empty-groups [objects]
            (->> (vals objects)
                 (filter (fn [shape]
                           (and (= :group (:type shape))
                                (or (empty? (:shapes shape))
                                    (every? (fn [child-id]
                                              (not (contains? objects child-id)))
                                            (:shapes shape))))))
                 (map :id)))

          (calculate-changes [[page-id page]]
            (let [objects (:objects page)
                  eids    (find-empty-groups objects)]

              (map (fn [id]
                     {:type :del-obj
                      :page-id page-id
                      :id id})
                   eids)))]

    (loop [data data]
      (let [changes (mapcat calculate-changes (:pages-index data))]
        (if (seq changes)
          (recur (cpc/process-changes data changes))
          data)))))

(defn migrate-up-10
  [data]
  (letfn [(update-page [page]
            (d/update-in-when page [:objects uuid/zero] dissoc :points :selrect))]
    (update data :pages-index update-vals update-page)))

(defn migrate-up-11
  [data]
  (letfn [(update-object [objects shape]
            (if (cfh/frame-shape? shape)
              (d/update-when shape :shapes (fn [shapes]
                                             (filterv (fn [id] (contains? objects id)) shapes)))
              shape))

          (update-page [page]
            (update page :objects (fn [objects]
                                    (update-vals objects (partial update-object objects)))))]

    (update data :pages-index update-vals update-page)))

(defn migrate-up-12
  [data]
  (letfn [(update-grid [grid]
            (cond-> grid
              (= :auto (:size grid))
              (assoc :size nil)))

          (update-page [page]
            (d/update-in-when page [:options :saved-grids] update-vals update-grid))]

    (update data :pages-index update-vals update-page)))

(defn migrate-up-13
  "Add rx and ry to images"
  [data]
  (letfn [(fix-radius [shape]
            (if-not (or (contains? shape :rx) (contains? shape :r1))
              (-> shape
                  (assoc :rx 0)
                  (assoc :ry 0))
              shape))

          (update-object [object]
            (cond-> object
              (cfh/image-shape? object)
              (fix-radius)))

          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

(defn migrate-up-14
  [data]
  (letfn [(process-shape [shape]
            (let [fill-color   (str/upper (:fill-color shape))
                  fill-opacity (:fill-opacity shape)]
              (cond-> shape
                (and (= 1 fill-opacity)
                     (or (= "#B1B2B5" fill-color)
                         (= "#7B7D85" fill-color)))
                (dissoc :fill-color :fill-opacity))))

          (update-container [container]
            (if (contains? container :objects)
              (loop [objects (:objects container)
                     shapes  (->> (vals objects)
                                  (filter cfh/image-shape?))]
                (if-let [shape (first shapes)]
                  (let [{:keys [id frame-id] :as shape'} (process-shape shape)]
                    (if (identical? shape shape')
                      (recur objects (rest shapes))
                      (recur (-> objects
                                 (assoc id shape')
                                 (d/update-when frame-id dissoc :thumbnail))
                             (rest shapes))))
                  (assoc container :objects objects)))
              container))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-16
  "Add fills and strokes"
  [data]
  (letfn [(assign-fills [shape]
            (let [attrs {:fill-color (:fill-color shape)
                         :fill-color-gradient (:fill-color-gradient shape)
                         :fill-color-ref-file (:fill-color-ref-file shape)
                         :fill-color-ref-id (:fill-color-ref-id shape)
                         :fill-opacity (:fill-opacity shape)}
                  clean-attrs (d/without-nils attrs)]
              (cond-> shape
                (d/not-empty? clean-attrs)
                (assoc :fills [clean-attrs]))))

          (assign-strokes [shape]
            (let [attrs {:stroke-style (:stroke-style shape)
                         :stroke-alignment (:stroke-alignment shape)
                         :stroke-width (:stroke-width shape)
                         :stroke-color (:stroke-color shape)
                         :stroke-color-ref-id (:stroke-color-ref-id shape)
                         :stroke-color-ref-file (:stroke-color-ref-file shape)
                         :stroke-opacity (:stroke-opacity shape)
                         :stroke-color-gradient (:stroke-color-gradient shape)
                         :stroke-cap-start (:stroke-cap-start shape)
                         :stroke-cap-end (:stroke-cap-end shape)}
                  clean-attrs (d/without-nils attrs)]
              (cond-> shape
                (d/not-empty? clean-attrs)
                (assoc :strokes [clean-attrs]))))

          (update-object [object]
            (cond-> object
              (and (not (cfh/text-shape? object))
                   (not (contains? object :strokes)))
              (assign-strokes)

              (and (not (cfh/text-shape? object))
                   (not (contains? object :fills)))
              (assign-fills)))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-17
  [data]
  (letfn [(affected-object? [object]
            (and (cfh/image-shape? object)
                 (some? (:fills object))
                 (= 1 (count (:fills object)))
                 (some? (:fill-color object))
                 (some? (:fill-opacity object))
                 (let [color-old   (str/upper (:fill-color object))
                       color-new   (str/upper (get-in object [:fills 0 :fill-color]))
                       opacity-old (:fill-opacity object)
                       opacity-new (get-in object [:fills 0 :fill-opacity])]
                   (and (= color-old color-new)
                        (or (= "#B1B2B5" color-old)
                            (= "#7B7D85" color-old))
                        (= 1 opacity-old opacity-new)))))

          (update-object [object]
            (cond-> object
              (affected-object? object)
              (assoc :fills [])))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-18
  "Remove position-data to solve a bug with the text positioning"
  [data]
  (letfn [(update-object [object]
            (cond-> object
              (cfh/text-shape? object)
              (dissoc :position-data)))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-19
  [data]
  (letfn [(update-object [object]
            (cond-> object
              (and (cfh/text-shape? object)
                   (d/not-empty? (:position-data object))
                   (not (gsht/overlaps-position-data? object (:position-data object))))
              (dissoc :position-data)))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-25
  [data]
  (some-> cfeat/*new* (swap! conj "fdata/shape-data-type"))
  (letfn [(update-object [object]
            (if (cfh/root? object)
              object
              (-> object
                  (update :selrect grc/make-rect)
                  (cts/map->Shape))))
          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-26
  [data]
  (letfn [(update-object [object]
            (cond-> object
              (nil? (:transform object))
              (assoc :transform (gmt/matrix))

              (nil? (:transform-inverse object))
              (assoc :transform-inverse (gmt/matrix))))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-27
  [data]
  (letfn [(update-object [object]
            (cond-> object
              (contains? object :main-instance?)
              (-> (assoc :main-instance (:main-instance? object))
                  (dissoc :main-instance?))

              (contains? object :component-root?)
              (-> (assoc :component-root (:component-root? object))
                  (dissoc :component-root?))

              (contains? object :remote-synced?)
              (-> (assoc :remote-synced (:remote-synced? object))
                  (dissoc :remote-synced?))

              (contains? object :masked-group?)
              (-> (assoc :masked-group (:masked-group? object))
                  (dissoc :masked-group?))

              (contains? object :saved-component-root?)
              (-> (assoc :saved-component-root (:saved-component-root? object))
                  (dissoc :saved-component-root?))))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-28
  [data]
  (letfn [(update-object [objects object]
            (let [frame-id (:frame-id object)
                  calculated-frame-id
                  (or (->> (cfh/get-parent-ids objects (:id object))
                           (map (d/getf objects))
                           (d/seek cfh/frame-shape?)
                           :id)
                      ;; If we cannot find any we let the frame-id as it was before
                      frame-id)]
              (when (not= frame-id calculated-frame-id)
                (l/trc :hint "Fix wrong frame-id"
                       :shape (:name object)
                       :id (:id object)
                       :current (dm/get-in objects [frame-id :name])
                       :calculated (get-in objects [calculated-frame-id :name])))
              (assoc object :frame-id calculated-frame-id)))

          (update-container [container]
            (d/update-when container :objects #(update-vals % (partial update-object %))))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-29
  [data]
  (letfn [(valid-ref? [ref]
            (or (uuid? ref)
                (nil? ref)))

          (valid-node? [node]
            (and (valid-ref? (:typography-ref-file node))
                 (valid-ref? (:typography-ref-id node))
                 (valid-ref? (:fill-color-ref-file node))
                 (valid-ref? (:fill-color-ref-id node))))

          (fix-ref [ref]
            (if (valid-ref? ref) ref nil))

          (fix-node [node]
            (-> node
                (d/update-when :typography-ref-file fix-ref)
                (d/update-when :typography-ref-id fix-ref)
                (d/update-when :fill-color-ref-file fix-ref)
                (d/update-when :fill-color-ref-id fix-ref)))

          (update-object [object]
            (let [invalid-node? (complement valid-node?)]
              (cond-> object
                (cfh/text-shape? object)
                (update :content #(txt/transform-nodes invalid-node? fix-node %)))))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-31
  [data]
  (letfn [(update-object [object]
            (cond-> object
              (contains? object :use-for-thumbnail?)
              (-> (assoc :use-for-thumbnail (:use-for-thumbnail? object))
                  (dissoc :use-for-thumbnail?))))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-32
  [data]
  (some-> cfeat/*new* (swap! conj "fdata/shape-data-type"))
  (letfn [(update-object [object]
            (as-> object object
              (if (contains? object :svg-attrs)
                (update object :svg-attrs csvg/attrs->props)
                object)
              (if (contains? object :svg-viewbox)
                (update object :svg-viewbox grc/make-rect)
                object)))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-33
  [data]
  (letfn [(update-object [object]
            ;; Ensure all root objects are well formed shapes.
            (if (= (:id object) uuid/zero)
              (-> object
                  (assoc :parent-id uuid/zero)
                  (assoc :frame-id uuid/zero)
                  ;; We explicitly dissoc them and let the shape-setup
                  ;; to regenerate it with valid values.
                  (dissoc :selrect)
                  (dissoc :points)
                  (cts/setup-shape))
              object))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container))))

(defn migrate-up-34
  [data]
  (letfn [(update-object [object]
            (if (or (cfh/path-shape? object)
                    (cfh/bool-shape? object))
              (dissoc object :x :y :width :height)
              object))
          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-36
  [data]
  (letfn [(update-container [container]
            (d/update-when container :objects (fn [objects]
                                                (if (contains? objects nil)
                                                  (dissoc objects nil)
                                                  objects))))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-37
  "Clean nil values on data"
  [data]
  (d/without-nils data))

(defn migrate-up-38
  [data]
  (letfn [(fix-gradient [{:keys [type] :as gradient}]
            (if (string? type)
              (assoc gradient :type (keyword type))
              gradient))

          (update-fill [fill]
            (d/update-when fill :fill-color-gradient fix-gradient))

          (update-object [object]
            (d/update-when object :fills #(mapv update-fill %)))

          (update-shape [shape]
            (let [shape (update-object shape)]
              (if (cfh/text-shape? shape)
                (-> shape
                    (update :content (partial txt/transform-nodes identity update-fill))
                    (d/update-when :position-data #(mapv update-object %)))
                shape)))

          (update-container [container]
            (d/update-when container :objects update-vals update-shape))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-39
  [data]
  (letfn [(update-shape [shape]
            (cond
              (and (cfh/bool-shape? shape)
                   (not (contains? shape :bool-content)))
              (assoc shape :bool-content [])

              (and (cfh/path-shape? shape)
                   (not (contains? shape :content)))
              (assoc shape :content [])

              :else
              shape))

          (update-container [container]
            (d/update-when container :objects update-vals update-shape))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-40
  [data]
  (letfn [(update-shape [{:keys [content shapes] :as shape}]
            ;; Fix frame shape that in reallity is a path shape
            (if (and (cfh/frame-shape? shape)
                     (contains? shape :selrect)
                     (seq content)
                     (not (seq shapes))
                     (contains? (first content) :command))
              (-> shape
                  (assoc :type :path)
                  (assoc :x nil)
                  (assoc :y nil)
                  (assoc :width nil)
                  (assoc :height nil))
              shape))

          (update-container [container]
            (d/update-when container :objects update-vals update-shape))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-41
  [data]
  (letfn [(update-shape [shape]
            (cond
              (or (cfh/bool-shape? shape)
                  (cfh/path-shape? shape))
              shape

              ;; Fix all shapes that has geometry broken but still
              ;; preservers the selrect, so we recalculate the
              ;; geometry from selrect.
              (and (contains? shape :selrect)
                   (or (nil? (:x shape))
                       (nil? (:y shape))
                       (nil? (:width shape))
                       (nil? (:height shape))))
              (let [selrect (:selrect shape)]
                (-> shape
                    (assoc :x (:x selrect))
                    (assoc :y (:y selrect))
                    (assoc :width (:width selrect))
                    (assoc :height (:height selrect))))

              :else
              shape))

          (update-container [container]
            (d/update-when container :objects update-vals update-shape))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-42
  [data]
  (letfn [(update-object [object]
            (if (and (or (cfh/frame-shape? object)
                         (cfh/group-shape? object)
                         (cfh/bool-shape? object))
                     (not (:shapes object)))
              (assoc object :shapes [])
              object))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(def ^:private valid-fill?
  (sm/lazy-validator ::cts/fill))

(defn migrate-up-43
  [data]
  (letfn [(number->string [v]
            (if (number? v)
              (str v)
              v))

          (update-text-node [node]
            (-> node
                (d/update-when :fills #(filterv valid-fill? %))
                (d/update-when :font-size number->string)
                (d/update-when :font-weight number->string)
                (d/without-nils)))

          (update-object [object]
            (if (cfh/text-shape? object)
              (update object :content #(txt/transform-nodes identity update-text-node %))
              object))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(def ^:private valid-shadow?
  (sm/lazy-validator ::ctss/shadow))

(defn migrate-up-44
  [data]
  (letfn [(fix-shadow [shadow]
            (if (string? (:color shadow))
              (let [color {:color (:color shadow)
                           :opacity 1}]
                (assoc shadow :color color))
              shadow))

          (update-object [object]
            (d/update-when object :shadow
                           #(into []
                                  (comp (map fix-shadow)
                                        (filter valid-shadow?))
                                  %)))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defn migrate-up-45
  [data]
  (letfn [(fix-shape [shape]
            (let [frame-id  (or (:frame-id shape)
                                uuid/zero)
                  parent-id (or (:parent-id shape)
                                frame-id)]
              (assoc shape :frame-id frame-id
                     :parent-id parent-id)))

          (update-container [container]
            (d/update-when container :objects update-vals fix-shape))]
    (-> data
        (update :pages-index update-vals update-container))))

(defn migrate-up-46
  [data]
  (letfn [(update-object [object]
            (dissoc object :thumbnail))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(def migrations
  "A vector of all applicable migrations"
  [{:id 2 :migrate-up migrate-up-2}
   {:id 3 :migrate-up migrate-up-3}
   {:id 5 :migrate-up migrate-up-5}
   {:id 6 :migrate-up migrate-up-6}
   {:id 7 :migrate-up migrate-up-7}
   {:id 8 :migrate-up migrate-up-8}
   {:id 9 :migrate-up migrate-up-9}
   {:id 10 :migrate-up migrate-up-10}
   {:id 11 :migrate-up migrate-up-11}
   {:id 12 :migrate-up migrate-up-12}
   {:id 13 :migrate-up migrate-up-13}
   {:id 14 :migrate-up migrate-up-14}
   {:id 16 :migrate-up migrate-up-16}
   {:id 17 :migrate-up migrate-up-17}
   {:id 18 :migrate-up migrate-up-18}
   {:id 19 :migrate-up migrate-up-19}
   {:id 25 :migrate-up migrate-up-25}
   {:id 26 :migrate-up migrate-up-26}
   {:id 27 :migrate-up migrate-up-27}
   {:id 28 :migrate-up migrate-up-28}
   {:id 29 :migrate-up migrate-up-29}
   {:id 31 :migrate-up migrate-up-31}
   {:id 32 :migrate-up migrate-up-32}
   {:id 33 :migrate-up migrate-up-33}
   {:id 34 :migrate-up migrate-up-34}
   {:id 36 :migrate-up migrate-up-36}
   {:id 37 :migrate-up migrate-up-37}
   {:id 38 :migrate-up migrate-up-38}
   {:id 39 :migrate-up migrate-up-39}
   {:id 40 :migrate-up migrate-up-40}
   {:id 41 :migrate-up migrate-up-41}
   {:id 42 :migrate-up migrate-up-42}
   {:id 43 :migrate-up migrate-up-43}
   {:id 44 :migrate-up migrate-up-44}
   {:id 45 :migrate-up migrate-up-45}
   {:id 46 :migrate-up migrate-up-46}])
