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
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.text :as gsht]
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.svg :as csvg]
   [app.common.text :as txt]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.shape.shadow :as ctss]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [cuerdas.core :as str]))

#?(:cljs (l/set-level! :info))

(declare ^:private available-migrations)
(declare ^:private migration-up-index)
(declare ^:private migration-down-index)

(def version cfd/version)

(defmulti migrate-data
  "A reduce function that responsible to apply a migration identified by `id`."
  (fn [_data id] id))

(defn need-migration?
  [file]
  (or (nil? (:version file))
      (not= cfd/version (:version file))
      (not= available-migrations (:migrations file))))

(def xf:map-name
  (map :name))

(defn migrate
  [{:keys [id] :as file}]

  (let [diff
        (set/difference available-migrations (:migrations file))

        data
        (reduce migrate-data (:data file) diff)

        data
        (-> data
            (assoc :id id)
            (dissoc :version))]

    (-> file
        (assoc :data data)
        (update :migrations set/union diff)
        (vary-meta assoc ::migrated (not-empty diff)))))

(defn- generate-migrations-from-version
  "A function that generates new format migration from the old,
  version based migration system"
  [version]
  (let [xform  (comp
                (take-while #(<= % version))
                (map #(str "legacy-" %))
                (filter #(contains? available-migrations %)))
        result (transduce xform conj (d/ordered-set) (range 1 (inc cfd/version)))]
    result))

(defn migrate-file
  [file]
  (binding [cfeat/*new* (atom #{})]
    (let [version (or (:version file)
                      (-> file :data :version))]
      (-> file
          (assoc :version cfd/version)
          (update :migrations
                  (fn [migrations]
                    (if (nil? migrations)
                      (generate-migrations-from-version version)
                      migrations)))
          (migrate)
          (update :features (fnil into #{}) (deref cfeat/*new*))
          ;; NOTE: in some future we can consider to apply
          ;; a migration to the whole database and remove
          ;; this code from this function that executes on
          ;; each file migration operation
          (update :features cfeat/migrate-legacy-features)))))

(defn migrated?
  [file]
  (boolean (-> file meta ::migrated)))

;; -- MIGRATIONS --

(defmethod migrate-data "legacy-2"
  [data _]
  (letfn [(update-object [object]
            (d/update-when object :shapes
                           (fn [shapes]
                             (if (seq? shapes)
                               (into [] shapes)
                               shapes))))
          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

(defmethod migrate-data "legacy-3"
  [data _]
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

;; Put the id of the local file in :component-file in instances of
;; local components
(defmethod migrate-data "legacy-5"
  [data _]
  (letfn [(update-object [object]
            (if (and (some? (:component-id object))
                     (nil? (:component-file object)))
              (assoc object :component-file (:id data))
              object))

          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

;; Fixes issues with selrect/points for shapes with width/height =
;; 0 (line-like paths)
(defmethod migrate-data "legacy-6"
  [data _]
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

;; Remove interactions pointing to deleted frames
(defmethod migrate-data "legacy-7"
  [data _]
  (letfn [(update-object [page object]
            (d/update-when object :interactions
                           (fn [interactions]
                             (filterv #(get-in page [:objects (:destination %)]) interactions))))

          (update-page [page]
            (update page :objects update-vals (partial update-object page)))]

    (update data :pages-index update-vals update-page)))

;; Remove groups without any shape, both in pages and components
(defmethod migrate-data "legacy-8"
  [data _]
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

(defmethod migrate-data "legacy-9"
  [data _]
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

(defmethod migrate-data "legacy-10"
  [data _]
  (letfn [(update-page [page]
            (d/update-in-when page [:objects uuid/zero] dissoc :points :selrect))]
    (update data :pages-index update-vals update-page)))

(defmethod migrate-data "legacy-11"
  [data _]
  (letfn [(update-object [objects shape]
            (if (cfh/frame-shape? shape)
              (d/update-when shape :shapes (fn [shapes]
                                             (filterv (fn [id] (contains? objects id)) shapes)))
              shape))

          (update-page [page]
            (update page :objects (fn [objects]
                                    (update-vals objects (partial update-object objects)))))]

    (update data :pages-index update-vals update-page)))

(defmethod migrate-data "legacy-12"
  [data _]
  (letfn [(update-grid [grid]
            (cond-> grid
              (= :auto (:size grid))
              (assoc :size nil)))

          (update-page [page]
            (d/update-in-when page [:options :saved-grids] update-vals update-grid))]

    (update data :pages-index update-vals update-page)))

;; Add rx and ry to images
(defmethod migrate-data "legacy-13"
  [data _]
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

(defmethod migrate-data "legacy-14"
  [data _]
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

(defmethod migrate-data "legacy-16"
  [data _]
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

(defmethod migrate-data "legacy-17"
  [data _]
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

;; Remove position-data to solve a bug with the text positioning
(defmethod migrate-data "legacy-18"
  [data _]
  (letfn [(update-object [object]
            (cond-> object
              (cfh/text-shape? object)
              (dissoc :position-data)))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate-data "legacy-19"
  [data _]
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

(defmethod migrate-data "legacy-25"
  [data _]
  (some-> cfeat/*new* (swap! conj "fdata/shape-data-type"))
  (letfn [(update-object [object]
            (if (cfh/root? object)
              object
              (-> object
                  (update :selrect grc/make-rect)
                  (cts/create-shape))))
          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate-data "legacy-26"
  [data _]
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

(defmethod migrate-data "legacy-27"
  [data _]
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

(defmethod migrate-data "legacy-28"
  [data _]
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

(defmethod migrate-data "legacy-29"
  [data _]
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

(defmethod migrate-data "legacy-31"
  [data _]
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

(defmethod migrate-data "legacy-32"
  [data _]
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

(defmethod migrate-data "legacy-33"
  [data _]
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

(defmethod migrate-data "legacy-34"
  [data _]
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

(defmethod migrate-data "legacy-36"
  [data _]
  (letfn [(update-container [container]
            (d/update-when container :objects (fn [objects]
                                                (if (contains? objects nil)
                                                  (dissoc objects nil)
                                                  objects))))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate-data "legacy-37"
  [data _]
  (d/without-nils data))

(defmethod migrate-data "legacy-38"
  [data _]
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

(defmethod migrate-data "legacy-39"
  [data _]
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

(defmethod migrate-data "legacy-40"
  [data _]
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

(defmethod migrate-data "legacy-41"
  [data _]
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

(defmethod migrate-data "legacy-42"
  [data _]
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

(defmethod migrate-data "legacy-43"
  [data _]
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

(defmethod migrate-data "legacy-44"
  [data _]
  (letfn [(fix-shadow [shadow]
            (let [color (if (string? (:color shadow))
                          {:color (:color shadow)
                           :opacity 1}
                          (d/without-nils (:color shadow)))]
              (assoc shadow :color color)))

          (update-object [object]
            (let [xform (comp (map fix-shadow)
                              (filter valid-shadow?))]
              (d/update-when object :shadow #(into [] xform %))))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate-data "legacy-45"
  [data _]
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

(defmethod migrate-data "legacy-46"
  [data _]
  (letfn [(update-object [object]
            (dissoc object :thumbnail))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate-data "legacy-47"
  [data _]
  (letfn [(fix-shape [page shape]
            (let [file {:id (:id data) :data data}
                  component-file (:component-file shape)
                  ;; On cloning a file, the component-file of the shapes point to the old file id
                  ;; this is a workaround to be able to found the components in that case
                  libraries {component-file {:id component-file :data data}}
                  ref-shape  (ctf/find-ref-shape file page libraries shape {:include-deleted? true :with-context? true})
                  ref-parent (get (:objects (:container (meta ref-shape))) (:parent-id ref-shape))
                  shape-swap-slot (ctk/get-swap-slot shape)
                  ref-swap-slot   (ctk/get-swap-slot ref-shape)]
              (if (and (some? shape-swap-slot)
                       (= shape-swap-slot ref-swap-slot)
                       (ctk/main-instance? ref-parent))
                (ctk/remove-swap-slot shape)
                shape)))

          (update-page [page]
            (d/update-when page :objects update-vals (partial fix-shape page)))]
    (-> data
        (update :pages-index update-vals update-page))))

(defmethod migrate-data "legacy-48"
  [data _]
  (letfn [(fix-shape [shape]
            (let [swap-slot (ctk/get-swap-slot shape)]
              (if (and (some? swap-slot)
                       (not (ctk/subcopy-head? shape)))
                (ctk/remove-swap-slot shape)
                shape)))

          (update-page [page]
            (d/update-when page :objects update-vals fix-shape))]
    (-> data
        (update :pages-index update-vals update-page))))

;; Remove hide-in-viewer for shapes that are origin or destination of an interaction
(defmethod migrate-data "legacy-49"
  [data _]
  (letfn [(update-object [destinations object]
            (cond-> object
              (or (:interactions object)
                  (contains? destinations (:id object)))
              (dissoc object :hide-in-viewer)))

          (update-page [page]
            (let [destinations (->> page
                                    :objects
                                    (vals)
                                    (mapcat :interactions)
                                    (map :destination)
                                    (set))]
              (update page :objects update-vals (partial update-object destinations))))]

    (update data :pages-index update-vals update-page)))

;; This migration mainly fixes paths with curve-to segments
;; without :c1x :c1y :c2x :c2y properties. Additionally, we found a
;; case where the params instead to be plain hash-map, is a points
;; instance. This migration normalizes all params to plain map.

(defmethod migrate-data "legacy-50"
  [data _]
  (let [update-segment
        (fn [{:keys [command params] :as segment}]
          (let [params (into {} params)
                params (cond
                         (= :curve-to command)
                         (let [x (get params :x)
                               y (get params :y)]

                           (cond-> params
                             (nil? (:c1x params))
                             (assoc :c1x x)

                             (nil? (:c1y params))
                             (assoc :c1y y)

                             (nil? (:c2x params))
                             (assoc :c2x x)

                             (nil? (:c2y params))
                             (assoc :c2y y)))

                         :else
                         params)]

            (assoc segment :params params)))

        update-shape
        (fn [shape]
          (if (cfh/path-shape? shape)
            (d/update-when shape :content (fn [content] (mapv update-segment content)))
            shape))

        update-container
        (fn [page]
          (d/update-when page :objects update-vals update-shape))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(def ^:private valid-color?
  (sm/lazy-validator ::ctc/color))

(defmethod migrate-data "legacy-51"
  [data _]
  (let [update-colors
        (fn [colors]
          (into {} (filter #(-> % val valid-color?) colors)))]
    (update data :colors update-colors)))

(defmethod migrate-data "legacy-52"
  [data _]
  (letfn [(update-shape [shape]
            (if (= :no-wrap (:layout-wrap-type shape))
              (assoc shape :layout-wrap-type :nowrap)
              shape))

          (update-page [page]
            (d/update-when page :objects update-vals update-shape))]

    (update data :pages-index update-vals update-page)))


(defmethod migrate-data "legacy-53"
  [data _]
  (migrate-data data "legacy-26"))

;; Fixes shapes with invalid colors in shadow: it first tries a non
;; destructive fix, and if it is not possible, then, shadow is removed
(defmethod migrate-data "legacy-54"
  [data _]
  (letfn [(fix-shadow [shadow]
            (update shadow :color d/without-nils))

          (update-shape [shape]
            (let [xform (comp (map fix-shadow)
                              (filter valid-shadow?))]
              (d/update-when shape :shadow #(into [] xform %))))

          (update-container [container]
            (d/update-when container :objects update-vals update-shape))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

;; This migration moves page options to the page level
(defmethod migrate-data "legacy-55"
  [data _]
  (let [update-page
        (fn [{:keys [options] :as page}]
          (cond-> page
            (and (some? (:saved-grids options))
                 (not (contains? page :default-grids)))
            (assoc :default-grids (:saved-grids options))

            (and (some? (:background options))
                 (not (contains? page :background)))
            (assoc :background (:background options))

            (and (some? (:flows options))
                 (or (not (contains? page :flows))
                     (not (map? (:flows page)))))
            (assoc :flows (d/index-by :id (:flows options)))

            (and (some? (:guides options))
                 (not (contains? page :guides)))
            (assoc :guides (:guides options))

            (and (some? (:comment-threads-position options))
                 (not (contains? page :comment-thread-positions)))
            (assoc :comment-thread-positions (:comment-threads-position options))))]

    (update data :pages-index d/update-vals update-page)))

(defmethod migrate-data "legacy-56"
  [data _]
  (letfn [(fix-fills [object]
            (d/update-when object :fills (partial filterv valid-fill?)))

          (update-object [object]
            (-> object
                (fix-fills)

                ;; If shape contains shape-ref but has a nil value, we
                ;; should remove it from shape object
                (cond-> (and (contains? object :shape-ref)
                             (nil? (get object :shape-ref)))
                  (dissoc :shape-ref))

                ;; The text shape also can has fills on the text
                ;; fragments so we need to fix fills there
                (cond-> (cfh/text-shape? object)
                  (update :content (partial txt/transform-nodes identity fix-fills)))))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))


(defmethod migrate-data "legacy-57"
  [data _]
  (letfn [(fix-thread-positions [positions]
            (reduce-kv (fn [result id {:keys [position] :as data}]
                         (let [data (cond
                                      (gpt/point? position)
                                      data

                                      (and (map? position)
                                           (gpt/valid-point-attrs? position))
                                      (assoc data :position (gpt/point position))

                                      :else
                                      (assoc data :position (gpt/point 0 0)))]
                           (assoc result id data)))
                       positions
                       positions))

          (update-page [page]
            (d/update-when page :comment-thread-positions fix-thread-positions))]

    (-> data
        (update :pages (fn [pages] (into [] (remove nil?) pages)))
        (update :pages-index dissoc nil)
        (update :pages-index update-vals update-page))))

(defmethod migrate-data "legacy-59"
  [data _]
  (letfn [(fix-touched [elem]
            (cond-> elem (string? elem) keyword))

          (update-shape [shape]
            (d/update-when shape :touched #(into #{} (map fix-touched) %)))

          (update-container [container]
            (d/update-when container :objects update-vals update-shape))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate-data "legacy-62"
  [data _]
  (let [xform-cycles-ids
        (comp (filter #(= (:id %) (:shape-ref %)))
              (map :id))

        remove-cycles
        (fn [objects]
          (let [cycles-ids (into #{} xform-cycles-ids (vals objects))
                to-detach  (->> cycles-ids
                                (map #(get objects %))
                                (map #(ctn/get-head-shape objects %))
                                (map :id)
                                distinct
                                (mapcat #(ctn/get-children-in-instance objects %))
                                (map :id)
                                set)]

            (reduce-kv (fn [objects id shape]
                         (if (contains? to-detach id)
                           (assoc objects id (ctk/detach-shape shape))
                           objects))
                       objects
                       objects)))

        update-component
        (fn [component]
          ;; we only have encounter this on deleted components,
          ;; so the relevant objects are inside the component
          (d/update-when component :objects remove-cycles))]

    (update data :components update-vals update-component)))

(defmethod migrate-data "legacy-65"
  [data _]
  (let [update-object
        (fn [object]
          (d/update-when object :plugin-data d/without-nils))

        update-page
        (fn [page]
          (-> (update-object page)
              (update :objects update-vals update-object)))]

    (-> data
        (update-object)
        (d/update-when :pages-index update-vals update-page)
        (d/update-when :colors update-vals update-object)
        (d/update-when :typographies update-vals update-object)
        (d/update-when :components update-vals update-object))))

(defmethod migrate-data "legacy-66"
  [data _]
  (letfn [(update-object [object]
            (if (and (:rx object) (not (:r1 object)))
              (-> object
                  (assoc :r1 (:rx object))
                  (assoc :r2 (:rx object))
                  (assoc :r3 (:rx object))
                  (assoc :r4 (:rx object)))
              object))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate-data "legacy-67"
  [data _]
  (letfn [(update-object [object]
            (d/update-when object :shadow #(into [] (reverse %))))

          (update-container [container]
            (d/update-when container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(def available-migrations
  (into (d/ordered-set)
        ["legacy-2"
         "legacy-3"
         "legacy-5"
         "legacy-6"
         "legacy-7"
         "legacy-8"
         "legacy-9"
         "legacy-10"
         "legacy-11"
         "legacy-12"
         "legacy-13"
         "legacy-14"
         "legacy-16"
         "legacy-17"
         "legacy-18"
         "legacy-19"
         "legacy-25"
         "legacy-26"
         "legacy-27"
         "legacy-28"
         "legacy-29"
         "legacy-31"
         "legacy-32"
         "legacy-33"
         "legacy-34"
         "legacy-36"
         "legacy-37"
         "legacy-38"
         "legacy-39"
         "legacy-40"
         "legacy-41"
         "legacy-42"
         "legacy-43"
         "legacy-44"
         "legacy-45"
         "legacy-46"
         "legacy-47"
         "legacy-48"
         "legacy-49"
         "legacy-50"
         "legacy-51"
         "legacy-52"
         "legacy-53"
         "legacy-54"
         "legacy-55"
         "legacy-56"
         "legacy-57"
         "legacy-59"
         "legacy-62"
         "legacy-65"
         "legacy-66"
         "legacy-67"]))
