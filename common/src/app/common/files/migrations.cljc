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
   [app.common.files.defaults :refer [version]]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.text :as gsht]
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.pages.changes :as cpc]
   [app.common.pages.helpers :as cph]
   [app.common.svg :as csvg]
   [app.common.text :as txt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

#?(:cljs (l/set-level! :info))

(defmulti migrate :version)

(defn migrate-data
  ([data] (migrate-data data version))
  ([data to-version]
   (if (= (:version data) to-version)
     data
     (let [migrate-fn #(do
                         (l/dbg :hint "migrate file" :id (:id %) :version-from %2 :version-to (inc %2))
                         (migrate (assoc %1 :version (inc %2))))]
       (reduce migrate-fn data (range (:version data 0) to-version))))))

(defn migrate-file
  [{:keys [id data] :as file}]
  (let [data (assoc data :id id)]
    (-> file
        (assoc ::orig-version (:version data))
        (assoc :data (migrate-data data)))))

(defn migrated?
  [{:keys [data] :as file}]
  (or (::migrated file)
      (> (:version data)
         (::orig-version file))))

;; Default handler, noop
(defmethod migrate :default [data] data)

;; -- MIGRATIONS --

;; Ensure that all :shape attributes on shapes are vectors.
(defmethod migrate 2
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

;; Changes paths formats
(defmethod migrate 3
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
            (let [shape (cond-> shape
                          (empty? (:selrect shape)) (cts/setup-rect))]
              (cond-> shape
                (empty? (:points shape))
                (assoc :points (grc/rect->points (:selrect shape))))))

          (update-object [object]
            (cond-> object
              (= :curve (:type object))
              (assoc :type :path)

              (#{:curve :path} (:type object))
              (migrate-path)

              (cph/frame-shape? object)
              (fix-frames-selrects)

              (and (empty? (:points object)) (not= (:id object) uuid/zero))
              (fix-empty-points)))

          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

;; We did rollback version 4 migration.
;; Keep this in order to remember the next version to be 5
(defmethod migrate 4 [data] data)

;; Put the id of the local file in :component-file in instances of local components
(defmethod migrate 5
  [data]
  (letfn [(update-object [object]
            (if (and (some? (:component-id object))
                     (nil? (:component-file object)))
              (assoc object :component-file (:id data))
              object))

          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

(defmethod migrate 6
  [data]
  ;; Fixes issues with selrect/points for shapes with width/height = 0 (line-like paths)"
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
(defmethod migrate 7
  [data]
  (letfn [(update-object [page object]
            (d/update-when object :interactions
                           (fn [interactions]
                             (filterv #(get-in page [:objects (:destination %)]) interactions))))

          (update-page [page]
            (update page :objects update-vals (partial update-object page)))]

    (update data :pages-index update-vals update-page)))

;; Remove groups without any shape, both in pages and components

(defmethod migrate 8
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

(defmethod migrate 9
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

(defmethod migrate 10
  [data]
  (letfn [(update-page [page]
            (d/update-in-when page [:objects uuid/zero] dissoc :points :selrect))]
    (update data :pages-index update-vals update-page)))

(defmethod migrate 11
  [data]
  (letfn [(update-object [objects shape]
            (if (cph/frame-shape? shape)
              (d/update-when shape :shapes (fn [shapes]
                                             (filterv (fn [id] (contains? objects id)) shapes)))
              shape))

          (update-page [page]
            (update page :objects (fn [objects]
                                    (update-vals objects (partial update-object objects)))))]

    (update data :pages-index update-vals update-page)))

(defmethod migrate 12
  [data]
  (letfn [(update-grid [grid]
            (cond-> grid
              (= :auto (:size grid))
              (assoc :size nil)))

          (update-page [page]
            (d/update-in-when page [:options :saved-grids] update-vals update-grid))]

    (update data :pages-index update-vals update-page)))

;; Add rx and ry to images
(defmethod migrate 13
  [data]
  (letfn [(fix-radius [shape]
            (if-not (or (contains? shape :rx) (contains? shape :r1))
              (-> shape
                  (assoc :rx 0)
                  (assoc :ry 0))
              shape))

          (update-object [object]
            (cond-> object
              (cph/image-shape? object)
              (fix-radius)))

          (update-page [page]
            (update page :objects update-vals update-object))]

    (update data :pages-index update-vals update-page)))

(defmethod migrate 14
  [data]
  (letfn [(process-shape [shape]
            (let [fill-color   (str/upper (:fill-color shape))
                  fill-opacity (:fill-opacity shape)]
              (cond-> shape
                (and (= 1 fill-opacity)
                     (or (= "#B1B2B5" fill-color)
                         (= "#7B7D85" fill-color)))
                (dissoc :fill-color :fill-opacity))))

          (update-container [{:keys [objects] :as container}]
            (loop [objects objects
                   shapes  (->> (vals objects)
                                (filter cph/image-shape?))]
              (if-let [shape (first shapes)]
                (let [{:keys [id frame-id] :as shape'} (process-shape shape)]
                  (if (identical? shape shape')
                    (recur objects (rest shapes))
                    (recur (-> objects
                               (assoc id shape')
                               (d/update-when frame-id dissoc :thumbnail))
                           (rest shapes))))
                (assoc container :objects objects))))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))


(defmethod migrate 15 [data] data)

;; Add fills and strokes
(defmethod migrate 16
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
              (and (not (cph/text-shape? object))
                   (not (contains? object :strokes)))
              (assign-strokes)

              (and (not (cph/text-shape? object))
                   (not (contains? object :fills)))
              (assign-fills)))

          (update-container [container]
            (update container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate 17
  [data]
  (letfn [(affected-object? [object]
            (and (cph/image-shape? object)
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
            (update container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

;;Remove position-data to solve a bug with the text positioning
(defmethod migrate 18
  [data]
  (letfn [(update-object [object]
            (cond-> object
              (cph/text-shape? object)
              (dissoc :position-data)))

          (update-container [container]
            (update container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate 19
  [data]
  (letfn [(update-object [object]
            (cond-> object
              (and (cph/text-shape? object)
                   (d/not-empty? (:position-data object))
                   (not (gsht/overlaps-position-data? object (:position-data object))))
              (dissoc :position-data)))

          (update-container [container]
            (update container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate 25
  [data]
  (some-> cfeat/*new* (swap! conj "fdata/shape-data-type"))
  (letfn [(update-object [object]
            (-> object
                (d/update-when :selrect grc/make-rect)
                (cts/map->Shape)))
          (update-container [container]
            (d/update-when container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate 26
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

(defmethod migrate 27
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

(defmethod migrate 28
  [data]
  (letfn [(update-object [objects object]
            (let [frame-id (:frame-id object)
                  calculated-frame-id
                  (or (->> (cph/get-parent-ids objects (:id object))
                           (map (d/getf objects))
                           (d/seek cph/frame-shape?)
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
            (update container :objects #(update-vals % (partial update-object %))))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

;; TODO: pending to do a migration for delete already not used fill
;; and stroke props. This should be done for >1.14.x version.

(defmethod migrate 29
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
                (cph/text-shape? object)
                (update :content #(txt/transform-nodes invalid-node? fix-node %)))))

          (update-container [container]
            (update container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate 30
  [data]
  (letfn [(update-object [object]
            (if (and (cph/frame-shape? object)
                     (not (:shapes object)))
              (assoc object :shapes [])
              object))

          (update-container [container]
            (update container :objects update-vals update-object))]

    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate 31
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

(defmethod migrate 32
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
            (update container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))

(defmethod migrate 33
  [data]
  (letfn [(update-object [object]
             ; Ensure all root objects are well formed shapes.
             (if (= (:id object) uuid/zero)
               (-> object
                   (assoc :parent-id uuid/zero
                          :frame-id uuid/zero)
                   (cts/setup-shape))
               object))

          (update-container [container]
            (update container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container))))

(defmethod migrate 34
  [data]
  (letfn [(update-object [object]
            (if (or (cph/path-shape? object)
                    (cph/bool-shape? object))
              (dissoc object :x :y :width :height)
              object))
          (update-container [container]
            (update container :objects update-vals update-object))]
    (-> data
        (update :pages-index update-vals update-container)
        (update :components update-vals update-container))))


;; NOTE: We need to repeat this migration for correct feature handling

(defmethod migrate 35
  [data]
  (-> data
      (assoc :version 25)
      (migrate)
      (assoc :version 35)))

