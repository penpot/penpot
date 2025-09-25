;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.selection
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.files.indices :as cfi]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.text :as gst]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.util.quadtree :as qdt]
   [clojure.set :as set]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const padding-percent 0.10)

(defn- index-shape
  "A reducing function that ads a shape to the index"
  [objects parents-index clip-index index shape]
  (let [bounds
        (cond
          (and ^boolean (cfh/text-shape? shape)
               ^boolean (some? (:position-data shape))
               ^boolean (d/not-empty? (:position-data shape)))
          (gst/shape->bounds shape)

          :else
          (grc/points->rect (:points shape)))

        bound
        #js {:x (dm/get-prop bounds :x)
             :y (dm/get-prop bounds :y)
             :width (dm/get-prop bounds :width)
             :height (dm/get-prop bounds :height)}

        shape-id
        (dm/get-prop shape :id)

        frame-id
        (dm/get-prop shape :frame-id)

        shape-type
        (dm/get-prop shape :type)

        parents
        (get parents-index shape-id)

        clip-parents
        (get clip-index shape-id)

        frame
        (when (and (not= :frame shape-type)
                   (not= frame-id uuid/zero))
          (get objects frame-id))]

    (qdt/insert index
                shape-id
                bound
                (assoc shape
                       :frame frame
                       :clip-parents clip-parents
                       :parents parents))))

(defn- objects-bounds
  "Calculates the bounds of the quadtree given a objects map."
  [objects]
  (-> objects
      (dissoc uuid/zero)
      vals
      gsh/shapes->rect))

(defn- add-padding-bounds
  "Adds a padding to the bounds defined as a percent in the constant `padding-percent`.
  For a value of 0.1 will add a 20% width increase (2 x padding)"
  [bounds]
  (let [width-pad  (* (:width bounds) padding-percent)
        height-pad (* (:height bounds) padding-percent)]
    (-> bounds
        (update :x - width-pad)
        (update :x1 - width-pad)
        (update :x2 + width-pad)
        (update :y1 - height-pad)
        (update :y2 + height-pad)
        (update :width + width-pad width-pad)
        (update :height + height-pad height-pad))))

(defn- create-index
  [objects]
  (let [parents-index (cfi/generate-child-all-parents-index objects)
        clip-index    (cfi/create-clip-index objects parents-index)
        root-shapes   (cfh/get-immediate-children objects uuid/zero)
        bounds        (-> root-shapes gsh/shapes->rect add-padding-bounds)

        index         (reduce-kv #(index-shape objects parents-index clip-index %1 %3)
                                 (qdt/create (clj->js bounds))
                                 (dissoc objects uuid/zero))]
    {:index index :bounds bounds}))

;; FIXME: optimize
(defn- update-index
  [{index :index :as data} old-objects new-objects]
  (let [object-changed?
        (fn [id]
          (not= (get old-objects id)
                (get new-objects id)))

        changed-ids
        (into #{}
              (comp (filter #(not= % uuid/zero))
                    (filter object-changed?)
                    (mapcat #(into [%] (cfh/get-children-ids new-objects %))))

              (set/union (set (keys old-objects))
                         (set (keys new-objects))))

        shapes
        (->> changed-ids
             (map #(get new-objects %))
             (filterv (comp not nil?)))

        parents-index
        (cfi/generate-child-all-parents-index new-objects shapes)

        clip-index
        (cfi/create-clip-index new-objects parents-index)

        index
        (reduce #(index-shape new-objects parents-index clip-index %1 %2)
                (qdt/remove-all index changed-ids)
                shapes)]

    (assoc data :index index)))

(defn- query-index
  [{index :index} rect frame-id full-frame? include-frames? ignore-groups? clip-children? using-selrect?]
  (let [result (-> (qdt/search index (clj->js rect))
                   (es6-iterator-seq))

        ;; Check if the shape matches the filter criteria
        match-criteria?
        (fn [shape]
          (and (not (:hidden shape))
               (or (cfh/frame-shape? shape) ;; We return frames even if blocked
                   (not (:blocked shape)))
               (or (not frame-id) (= frame-id (:frame-id shape)))
               (case (:type shape)
                 :frame   include-frames?
                 (:bool :group) (not ignore-groups?)
                 true)

               ;; This condition controls when to check for overlapping. Otherwise the
               ;; shape needs to be fully contained.
               (or (not full-frame?)
                   (and (not ignore-groups?) (contains? shape :component-id))
                   (and (not ignore-groups?) (not (cfh/root-frame? shape)))
                   (and (d/not-empty? (:shapes shape))
                        (gsh/rect-contains-shape? rect shape))
                   (and (empty? (:shapes shape))
                        (gsh/overlaps? shape rect)))))

        overlaps-outer-shape?
        (fn [shape]
          (let [padding (->> (:strokes shape)
                             (map #(case (get % :stroke-alignment :center)
                                     :center (:stroke-width % 0)
                                     :outer  (* 2 (:stroke-width % 0))
                                     :inner  0))
                             (reduce d/max 0))

                scalev     (gpt/point (/ (+ (:width shape) padding)
                                         (:width shape))
                                      (/ (+ (:height shape) padding)
                                         (:height shape)))

                outer-shape (-> shape
                                (gsh/transform-shape (-> (ctm/empty)
                                                         (ctm/resize scalev (gsh/shape->center shape)))))]

            (gsh/overlaps? outer-shape rect)))

        overlaps-inner-shape?
        (fn [shape]
          (let [padding (->> (:strokes shape)
                             (map #(case (get % :stroke-alignment :center)
                                     :center (:stroke-width % 0)
                                     :outer  0
                                     :inner  (* 2 (:stroke-width % 0))))
                             (reduce d/max 0))

                scalev     (gpt/point (/ (- (:width shape) padding)
                                         (:width shape))
                                      (/ (- (:height shape) padding)
                                         (:height shape)))

                inner-shape (-> shape
                                (gsh/transform-shape (-> (ctm/empty)
                                                         (ctm/resize scalev (gsh/shape->center shape)))))]
            (gsh/overlaps? inner-shape rect)))

        overlaps-path?
        (fn [shape]
          (let [padding (->> (:strokes shape)
                             (map :stroke-width)
                             (reduce d/max 5))
                ;; For paths that fit within a 5x5 box, there won't be any intersection
                ;; when the cursor is around the center. We need to adjust the padding
                ;; to make a narrower box in that case.
                ;; FIXME: this should be a function of the zoom level as well, or use
                ;;   pixel distances
                width   (dm/get-in shape [:selrect :width] 1)
                height  (dm/get-in shape [:selrect :height] 1)
                padding (min padding (/ (max width height) 2))
                center  (grc/rect->center rect)
                rect    (grc/center->rect center padding)]
            (gsh/overlaps-path? shape rect false)))

        overlaps?
        (fn [shape]
          (if (and (false? using-selrect?)
                   (empty? (:fills shape))
                   (not (contains? (-> shape :svg-attrs) :fill))
                   (not (contains? (-> shape :svg-attrs :style) :fill)))
            (case  (:type shape)
              ;; If the shape has no fills the overlap depends on the stroke
              :rect (and (overlaps-outer-shape? shape) (not (overlaps-inner-shape? shape)))
              :circle (and (overlaps-outer-shape? shape) (not (overlaps-inner-shape? shape)))
              (:bool :path) (overlaps-path? shape)
              (gsh/overlaps? shape rect))
            (gsh/overlaps? shape rect)))

        overlaps-parent?
        (fn [clip-parents]
          (->> clip-parents (some (comp not overlaps?)) not))]

    ;; Shapes after filters of overlapping and criteria
    (into (d/ordered-set)
          (comp (map #(unchecked-get % "data"))
                (filter match-criteria?)
                (filter overlaps?)
                (filter (if clip-children?
                          (comp overlaps-parent? :clip-parents)
                          (constantly true)))
                (map :id))
          result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-page
  "Add a page index to the state"
  [state {:keys [id objects] :as page}]
  (assoc state id (create-index objects)))

(defn update-page
  "Update page index on the state"
  [state old-page new-page]
  (let [page-id (get old-page :id)]
    (update state page-id
            (fn [index]
              (let [old-objects (:objects old-page)
                    new-objects (:objects new-page)
                    old-bounds  (:bounds index)
                    new-bounds  (objects-bounds new-objects)]

                ;; If the new bounds are contained within the old bounds
                ;; we can update the index. Otherwise we need to
                ;; re-create it.
                (if (and (some? index)
                         (grc/contains-rect? old-bounds new-bounds))
                  (update-index index old-objects new-objects)
                  (create-index new-objects)))))))

(defn query
  [index {:keys [page-id rect frame-id full-frame? include-frames? ignore-groups? clip-children? using-selrect?]
          :or {full-frame? false include-frames? false clip-children? true using-selrect? false}}]
  (when-let [index (get index page-id)]
    (query-index index rect frame-id full-frame? include-frames? ignore-groups? clip-children? using-selrect?)))
