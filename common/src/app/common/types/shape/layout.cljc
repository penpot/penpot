;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.layout
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes.grid-layout.areas :as sga]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]))

;; :layout                 ;; :flex, :grid in the future
;; :layout-flex-dir        ;; :row, :row-reverse, :column, :column-reverse
;; :layout-gap-type        ;; :simple, :multiple
;; :layout-gap             ;; {:row-gap number , :column-gap number}

;; :layout-align-items     ;; :start :end :center :stretch
;; :layout-align-content   ;; :start :center :end :space-between :space-around :space-evenly :stretch (by default)
;; :layout-justify-items    ;; :start :center :end :space-between :space-around :space-evenly
;; :layout-justify-content ;; :start :center :end :space-between :space-around :space-evenly
;; :layout-wrap-type       ;; :wrap, :nowrap
;; :layout-padding-type    ;; :simple, :multiple
;; :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative

;; layout-grid-rows        ;; vector of grid-track
;; layout-grid-columns     ;; vector of grid-track
;; layout-grid-cells       ;; map of id->grid-cell

;; ITEMS
;; :layout-item-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
;; :layout-item-margin-type ;; :simple :multiple
;; :layout-item-h-sizing    ;; :fill :fix :auto
;; :layout-item-v-sizing    ;; :fill :fix :auto
;; :layout-item-max-h       ;; num
;; :layout-item-min-h       ;; num
;; :layout-item-max-w       ;; num
;; :layout-item-min-w       ;; num
;; :layout-item-absolute    ;; boolean
;; :layout-item-z-index     ;; int


(def layout-types
  #{:flex :grid})

(def flex-direction-types
  #{:row :row-reverse :column :column-reverse})

(def grid-direction-types
  #{:row :column})

(def gap-types
  #{:simple :multiple})

(def wrap-types
  #{:wrap :nowrap})

(def padding-type
  #{:simple :multiple})

(def justify-content-types
  #{:start :center :end :space-between :space-around :space-evenly :stretch})

(def align-content-types
  #{:start :end :center :space-between :space-around :space-evenly :stretch})

(def align-items-types
  #{:start :end :center :stretch})

(def justify-items-types
  #{:start :end :center :stretch})

(def layout-item-props
  [:layout-item-margin
   :layout-item-margin-type
   :layout-item-h-sizing
   :layout-item-v-sizing
   :layout-item-max-h
   :layout-item-min-h
   :layout-item-max-w
   :layout-item-min-w
   :layout-item-absolute
   :layout-item-z-index])

(sm/register!
 ^{::sm/type ::layout-attrs}
 [:map {:title "LayoutAttrs"}
  [:layout {:optional true} [::sm/one-of layout-types]]
  [:layout-flex-dir {:optional true} [::sm/one-of flex-direction-types]]
  [:layout-gap {:optional true}
   [:map
    [:row-gap {:optional true} ::sm/safe-number]
    [:column-gap {:optional true} ::sm/safe-number]]]
  [:layout-gap-type {:optional true} [::sm/one-of gap-types]]
  [:layout-wrap-type {:optional true} [::sm/one-of wrap-types]]
  [:layout-padding-type {:optional true} [::sm/one-of padding-type]]
  [:layout-padding {:optional true}
   [:map
    [:p1 ::sm/safe-number]
    [:p2 ::sm/safe-number]
    [:p3 ::sm/safe-number]
    [:p4 ::sm/safe-number]]]
  [:layout-justify-content {:optional true} [::sm/one-of justify-content-types]]
  [:layout-justify-items {:optional true} [::sm/one-of justify-items-types]]
  [:layout-align-content {:optional true} [::sm/one-of align-content-types]]
  [:layout-align-items {:optional true} [::sm/one-of align-items-types]]

  [:layout-grid-dir {:optional true} [::sm/one-of grid-direction-types]]
  [:layout-grid-rows {:optional true}
   [:vector {:gen/max 2} ::grid-track]]
  [:layout-grid-columns {:optional true}
   [:vector {:gen/max 2} ::grid-track]]
  [:layout-grid-cells {:optional true}
   [:map-of {:gen/max 5} ::sm/uuid ::grid-cell]]])

;; Grid types
(def grid-track-types
  #{:percent :flex :auto :fixed})

(def grid-position-types
  #{:auto :manual :area})

(def grid-cell-align-self-types
  #{:auto :start :center :end :stretch})

(def grid-cell-justify-self-types
  #{:auto :start :center :end :stretch})

(sm/register!
 ^{::sm/type ::grid-cell}
 [:map {:title "GridCell"}
  [:id ::sm/uuid]
  [:area-name {:optional true} :string]
  [:row ::sm/safe-int]
  [:row-span ::sm/safe-int]
  [:column ::sm/safe-int]
  [:column-span ::sm/safe-int]
  [:position {:optional true} [::sm/one-of grid-position-types]]
  [:align-self {:optional true} [::sm/one-of grid-cell-align-self-types]]
  [:justify-self {:optional true} [::sm/one-of grid-cell-justify-self-types]]
  [:shapes
   [:vector {:gen/max 1} ::sm/uuid]]])

(sm/register!
 ^{::sm/type ::grid-track}
 [:map {:title "GridTrack"}
  [:type [::sm/one-of grid-track-types]]
  [:value {:optional true} [:maybe ::sm/safe-number]]])

(def check-grid-track!
  (sm/check-fn ::grid-track))

;; LAYOUT CHILDREN

(def item-margin-types
  #{:simple :multiple})

(def item-h-sizing-types
  #{:fill :fix :auto})

(def item-v-sizing-types
  #{:fill :fix :auto})

(def item-align-self-types
  #{:start :end :center :stretch})

(sm/register!
 ^{::sm/type ::layout-child-attrs}
 [:map {:title "LayoutChildAttrs"}
  [:layout-item-margin-type {:optional true} [::sm/one-of item-margin-types]]
  [:layout-item-margin {:optional true}
   [:map
    [:m1 {:optional true} ::sm/safe-number]
    [:m2 {:optional true} ::sm/safe-number]
    [:m3 {:optional true} ::sm/safe-number]
    [:m4 {:optional true} ::sm/safe-number]]]
  [:layout-item-max-h {:optional true} ::sm/safe-number]
  [:layout-item-min-h {:optional true} ::sm/safe-number]
  [:layout-item-max-w {:optional true} ::sm/safe-number]
  [:layout-item-min-w {:optional true} ::sm/safe-number]
  [:layout-item-h-sizing {:optional true} [::sm/one-of item-h-sizing-types]]
  [:layout-item-v-sizing {:optional true} [::sm/one-of item-v-sizing-types]]
  [:layout-item-align-self {:optional true} [::sm/one-of item-align-self-types]]
  [:layout-item-absolute {:optional true} :boolean]
  [:layout-item-z-index {:optional true} ::sm/safe-number]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-layouts
  #{:flex :grid})

(sm/register! ::layout [::sm/one-of valid-layouts])

(defn flex-layout?
  ([objects id]
   (flex-layout? (get objects id)))
  ([shape]
   (and (cfh/frame-shape? shape)
        (= :flex (:layout shape)))))

(defn grid-layout?
  ([objects id]
   (grid-layout? (get objects id)))
  ([shape]
   (and (cfh/frame-shape? shape)
        (= :grid (:layout shape)))))

(defn any-layout?
  ([objects id]
   (any-layout? (get objects id)))

  ([shape]
   (and (cfh/frame-shape? shape)
        (let [layout (:layout shape)]
          (or (= :flex layout)
              (= :grid layout))))))

(defn flex-layout-immediate-child? [objects shape]
  (let [parent-id (:parent-id shape)
        parent (get objects parent-id)]
    (flex-layout? parent)))

(defn grid-layout-immediate-child? [objects shape]
  (let [parent-id (:parent-id shape)
        parent (get objects parent-id)]
    (grid-layout? parent)))

(defn any-layout-immediate-child? [objects shape]
  (let [parent-id (:parent-id shape)
        parent (get objects parent-id)]
    (any-layout? parent)))

(defn flex-layout-immediate-child-id? [objects id]
  (let [parent-id (dm/get-in objects [id :parent-id])
        parent (get objects parent-id)]
    (flex-layout? parent)))

(defn grid-layout-immediate-child-id? [objects id]
  (let [parent-id (dm/get-in objects [id :parent-id])
        parent (get objects parent-id)]
    (grid-layout? parent)))

(defn any-layout-immediate-child-id? [objects id]
  (let [parent-id (dm/get-in objects [id :parent-id])
        parent (get objects parent-id)]
    (any-layout? parent)))

(defn flex-layout-descent? [objects shape]
  (let [frame-id (:frame-id shape)
        frame (get objects frame-id)]
    (flex-layout? frame)))

(defn grid-layout-descent? [objects shape]
  (let [frame-id (:frame-id shape)
        frame (get objects frame-id)]
    (grid-layout? frame)))

(defn any-layout-descent? [objects shape]
  (let [frame-id (:frame-id shape)
        frame (get objects frame-id)]
    (any-layout? frame)))

(defn inside-layout?
  "Check if the shape is inside a layout"
  [objects shape]
  (loop [current-id (dm/get-prop shape :id)]
    (let [current   (get objects current-id)
          parent-id (dm/get-prop current :parent-id)]
      (cond
        (or (nil? current) (= current-id parent-id))
        false

        (cfh/frame-shape? current-id)
        (:layout current)

        :else
        (recur parent-id)))))

(defn wrap?
  [{:keys [layout-wrap-type]}]
  (= layout-wrap-type :wrap))

(defn fill-width?
  ([objects id]
   (= :fill (dm/get-in objects [id :layout-item-h-sizing])))
  ([child]
   (= :fill (:layout-item-h-sizing child))))

(defn fill-height?
  ([objects id]
   (= :fill (dm/get-in objects [id :layout-item-v-sizing])))
  ([child]
   (= :fill (:layout-item-v-sizing child))))

(defn fill?
  ([objects id]
   (or (fill-height? objects id) (fill-width? objects id)))
  ([shape]
   (or (fill-height? shape) (fill-width? shape))))

(defn auto-width?
  ([objects id]
   (= :auto (dm/get-in objects [id :layout-item-h-sizing])))
  ([child]
   (= :auto (:layout-item-h-sizing child))))

(defn auto-height?
  ([objects id]
   (= :auto (dm/get-in objects [id :layout-item-v-sizing])))
  ([child]
   (= :auto (:layout-item-v-sizing child))))

(defn auto?
  ([objects id]
   (or (auto-height? objects id) (auto-width? objects id)))
  ([shape]
   (or (auto-height? shape) (auto-width? shape))))

(defn col?
  ([objects id]
   (col? (get objects id)))
  ([{:keys [layout-flex-dir]}]
   (or (= :column layout-flex-dir) (= :column-reverse layout-flex-dir))))

(defn row?
  ([objects id]
   (row? (get objects id)))
  ([{:keys [layout-flex-dir]}]
   (or (= :row layout-flex-dir) (= :row-reverse layout-flex-dir))))

(defn gaps
  [{:keys [layout-gap]}]
  (let [layout-gap-row (or (-> layout-gap :row-gap (mth/finite 0)) 0)
        layout-gap-col (or (-> layout-gap :column-gap (mth/finite 0)) 0)]
    [layout-gap-row layout-gap-col]))

(defn paddings
  [{:keys [layout-padding-type layout-padding]}]
  (let [{pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} layout-padding]
    (if (= :simple layout-padding-type)
      [pad-top pad-right pad-top pad-right]
      [pad-top pad-right pad-bottom pad-left])))

(defn h-padding
  [{:keys [layout-padding-type layout-padding]}]
  (let [{pad-right :p2 pad-left :p4} layout-padding]
    (if (= :simple layout-padding-type)
      (+ pad-right pad-right)
      (+ pad-right pad-left))))

(defn v-padding
  [{:keys [layout-padding-type layout-padding]}]
  (let [{pad-top :p1 pad-bottom :p3} layout-padding]
    (if (= :simple layout-padding-type)
      (+ pad-top pad-top)
      (+ pad-top pad-bottom))))

(defn child-min-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-item-min-w child)))
    (max 0.01 (:layout-item-min-w child))
    0.01))

(defn child-max-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-item-max-w child)))
    (max 0.01 (:layout-item-max-w child))
    ##Inf))

(defn child-min-height
  [child]
  (if (and (fill-height? child)
           (some? (:layout-item-min-h child)))
    (max 0.01 (:layout-item-min-h child))
    0.01))

(defn child-max-height
  [child]
  (if (and (fill-height? child)
           (some? (:layout-item-max-h child)))
    (max 0.01 (:layout-item-max-h child))
    ##Inf))

(defn child-margins
  [{{:keys [m1 m2 m3 m4]} :layout-item-margin :keys [layout-item-margin-type]}]
  (let [m1 (or m1 0)
        m2 (or m2 0)
        m3 (or m3 0)
        m4 (or m4 0)]
    (if (= layout-item-margin-type :multiple)
      [m1 m2 m3 m4]
      [m1 m2 m1 m2])))

(defn child-height-margin
  [child]
  (let [[top _ bottom _] (child-margins child)]
    (+ top bottom)))

(defn child-width-margin
  [child]
  (let [[_ right _ left] (child-margins child)]
    (+ right left)))

(defn h-start?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (col? shape)
           (= layout-align-items :start))
      (and (row? shape)
           (= layout-justify-content :start))))

(defn h-center?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (col? shape)
           (= layout-align-items :center))
      (and (row? shape)
           (= layout-justify-content :center))))

(defn h-end?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (col? shape)
           (= layout-align-items :end))
      (and (row? shape)
           (= layout-justify-content :end))))

(defn v-start?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (row? shape)
           (= layout-align-items :start))
      (and (col? shape)
           (= layout-justify-content :start))))

(defn v-center?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (row? shape)
           (= layout-align-items :center))
      (and (col? shape)
           (= layout-justify-content :center))))

(defn v-end?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (row? shape)
           (= layout-align-items :end))
      (and (col? shape)
           (= layout-justify-content :end))))

(defn content-start?
  [{:keys [layout-align-content]}]
  (= :start layout-align-content))

(defn content-center?
  [{:keys [layout-align-content]}]
  (= :center layout-align-content))

(defn content-end?
  [{:keys [layout-align-content]}]
  (= :end layout-align-content))

(defn content-between?
  [{:keys [layout-align-content]}]
  (= :space-between layout-align-content))

(defn content-around?
  [{:keys [layout-align-content]}]
  (= :space-around layout-align-content))

(defn content-evenly?
  [{:keys [layout-align-content]}]
  (= :space-evenly layout-align-content))

(defn content-stretch?
  [{:keys [layout-align-content]}]
  (or (= :stretch layout-align-content)
      (nil? layout-align-content)))

(defn align-items-center?
  [{:keys [layout-align-items]}]
  (= layout-align-items :center))

(defn align-items-start?
  [{:keys [layout-align-items]}]
  (= layout-align-items :start))

(defn align-items-end?
  [{:keys [layout-align-items]}]
  (= layout-align-items :end))

(defn align-items-stretch?
  [{:keys [layout-align-items]}]
  (= layout-align-items :stretch))

(defn reverse?
  ([objects id]
   (reverse? (get objects id)))
  ([{:keys [layout-flex-dir]}]
   (or (= :row-reverse layout-flex-dir)
       (= :column-reverse layout-flex-dir))))

(defn space-between?
  [{:keys [layout-justify-content]}]
  (= layout-justify-content :space-between))

(defn space-around?
  [{:keys [layout-justify-content]}]
  (= layout-justify-content :space-around))

(defn space-evenly?
  [{:keys [layout-justify-content]}]
  (= layout-justify-content :space-evenly))

(defn align-self-start? [{:keys [layout-item-align-self]}]
  (= :start layout-item-align-self))

(defn align-self-end? [{:keys [layout-item-align-self]}]
  (= :end layout-item-align-self))

(defn align-self-center? [{:keys [layout-item-align-self]}]
  (= :center layout-item-align-self))

(defn align-self-stretch? [{:keys [layout-item-align-self]}]
  (= :stretch layout-item-align-self))

(defn item-absolute?
  ([objects id]
   (item-absolute? (get objects id)))
  ([shape]
   (true? (:layout-item-absolute shape))))

(defn position-absolute?
  ([objects id]
   (position-absolute? (get objects id)))
  ([shape]
   (or (item-absolute? shape)
       (:hidden shape))))

(defn layout-z-index
  ([objects id]
   (layout-z-index (get objects id)))
  ([shape]
   (or (:layout-item-z-index shape) 0)))

(defn- comparator-layout-z-index
  [reverse? [idx-a child-a] [idx-b child-b]]
  (cond
    (> (layout-z-index child-a) (layout-z-index child-b)) 1
    (< (layout-z-index child-a) (layout-z-index child-b)) -1
    (and (< idx-a idx-b) reverse?) -1
    (and (> idx-a idx-b) reverse?) 1
    (< idx-a idx-b) 1
    (> idx-a idx-b) -1
    :else 0))

(defn sort-layout-children-z-index
  [children reverse?]
  (->> children
       (d/enumerate)
       (sort (partial comparator-layout-z-index reverse?))
       (mapv second)))

(defn change-h-sizing?
  [frame-id objects children-ids]
  (and (flex-layout? objects frame-id)
       (auto-width? objects frame-id)
       (or (and (col? objects frame-id)
                (->> children-ids
                     (remove (partial position-absolute? objects))
                     (every? (partial fill-width? objects))))
           (and (row? objects frame-id)
                (->> children-ids
                     (remove (partial position-absolute? objects))
                     (some (partial fill-width? objects)))))))

(defn change-v-sizing?
  [frame-id objects children-ids]
  (and (flex-layout? objects frame-id)
       (auto-height? objects frame-id)
       (or (and (col? objects frame-id)
                (some (partial fill-height? objects) children-ids))
           (and (row? objects frame-id)
                (every? (partial fill-height? objects) children-ids)))))

(defn remove-layout-container-data
  [shape]
  (dissoc shape
          :layout
          :layout-flex-dir
          :layout-gap
          :layout-gap-type
          :layout-wrap-type
          :layout-padding-type
          :layout-padding
          :layout-align-content
          :layout-justify-content
          :layout-align-items
          :layout-justify-items
          :layout-grid-dir
          :layout-grid-columns
          :layout-grid-rows))

(defn remove-layout-item-data
  [shape]
  (-> shape
      (dissoc :layout-item-margin
              :layout-item-margin-type
              :layout-item-max-h
              :layout-item-min-h
              :layout-item-max-w
              :layout-item-min-w
              :layout-item-align-self
              :layout-item-absolute
              :layout-item-z-index)
      (cond-> (or (not (any-layout? shape))
                  (= :fill (:layout-item-h-sizing shape)))
        (dissoc :layout-item-h-sizing)

        (or (not (any-layout? shape))
            (= :fill (:layout-item-v-sizing shape)))
        (dissoc :layout-item-v-sizing))))

(defn update-flex-scale
  [shape scale]
  (-> shape
      (d/update-in-when [:layout-gap :row-gap] * scale)
      (d/update-in-when [:layout-gap :column-gap] * scale)
      (d/update-in-when [:layout-padding :p1] * scale)
      (d/update-in-when [:layout-padding :p2] * scale)
      (d/update-in-when [:layout-padding :p3] * scale)
      (d/update-in-when [:layout-padding :p4] * scale)))

(defn update-grid-scale
  [shape scale]
  (letfn [(scale-track [track]
            (cond-> track
              (= (:type track) :fixed)
              (update :value * scale)))]
    (-> shape
        (update :layout-grid-columns #(mapv scale-track %))
        (update :layout-grid-rows #(mapv scale-track %)))))

(defn update-flex-child
  [shape scale]
  (-> shape
      (d/update-when :layout-item-max-h * scale)
      (d/update-when :layout-item-min-h * scale)
      (d/update-when :layout-item-max-w * scale)
      (d/update-when :layout-item-min-w * scale)
      (d/update-in-when [:layout-item-margin :m1] * scale)
      (d/update-in-when [:layout-item-margin :m2] * scale)
      (d/update-in-when [:layout-item-margin :m3] * scale)
      (d/update-in-when [:layout-item-margin :m4] * scale)))

(declare assign-cells)

(def default-track-value
  {:type :flex
   :value 1})

(def grid-cell-defaults
  {:row-span 1
   :column-span 1
   :position :auto
   :align-self :auto
   :justify-self :auto
   :shapes []})

(declare resize-cell-area)
(declare cells-by-column)
(declare cells-by-row)

(defn remove-cell-areas
  "Remove the areas in the given `index` before and after the index"
  [parent prop index]
  (let [prop-span (if (= prop :column) :column-span :row-span)
        cells (if (= prop :column) (cells-by-column parent index) (cells-by-row parent index))]
    (->> cells
         (filter #(> (get % prop-span) 1))
         (reduce
          (fn [parent cell]
            (let [area? (= :area (:position cell))
                  changed-cells
                  (cond
                    ;; New track at the beginning
                    (= (get cell prop) (inc index))
                    [(assoc cell prop-span 1)
                     (-> cell
                         (assoc :id (uuid/next) :shapes [] prop (inc (get cell prop)) prop-span (dec (get cell prop-span)))
                         (dissoc :area-name)
                         (cond-> area? (assoc :position :manual)))]

                    ;; New track at the middle
                    (< (get cell prop) (inc index) (+ (get cell prop) (dec (get cell prop-span))))
                    [(assoc cell prop-span (- (inc index) (get cell prop)))
                     (-> cell
                         (assoc :id (uuid/next) :shapes [] prop (inc index) prop-span 1)
                         (dissoc :area-name)
                         (cond-> area? (assoc :position :manual)))
                     (-> cell
                         (assoc :id (uuid/next) :shapes [] prop (+ index 2) prop-span (- (+ (get cell prop) (dec (get cell prop-span))) (inc index)))
                         (dissoc :area-name)
                         (cond-> area? (assoc :position :manual)))]

                    ;; New track at the end
                    (= (+ (get cell prop) (dec (get cell prop-span))) (inc index))
                    [(assoc cell prop-span (- (inc index) (get cell prop)))
                     (-> cell
                         (assoc :id (uuid/next) :shapes [] prop (inc index) prop-span 1)
                         (dissoc :area-name)
                         (cond-> area? (assoc :position :manual)))])]

              (->> changed-cells
                   (reduce #(update %1 :layout-grid-cells assoc (:id %2) %2) parent))))
          parent))))

(defn remove-cell-areas-after
  "Remove the areas in the given `index` but only after the index."
  [parent prop index]
  (let [prop-span (if (= prop :column) :column-span :row-span)
        cells (if (= prop :column) (cells-by-column parent index) (cells-by-row parent index))]
    (->> cells
         (filter #(> (get % prop-span) 1))
         (reduce
          (fn [parent cell]
            (let [area? (= :area (:position cell))
                  changed-cells
                  (cond
                    ;; New track at the beginning
                    (= (get cell prop) (inc index))
                    [(assoc cell prop-span 1)
                     (-> cell
                         (assoc :id (uuid/next) :shapes [] prop (inc (get cell prop)) prop-span (dec (get cell prop-span)))
                         (dissoc :area-name)
                         (cond-> area? (assoc :position :manual)))]

                    ;; New track at the middle
                    (< (get cell prop) (inc index) (+ (get cell prop) (dec (get cell prop-span))))
                    [(assoc cell prop-span (- (+ index 2) (get cell prop)))
                     (-> cell
                         (assoc :id (uuid/next) :shapes [] prop (+ index 2) prop-span (- (+ (get cell prop) (dec (get cell prop-span))) (inc index)))
                         (dissoc :area-name)
                         (cond-> area? (assoc :position :manual)))])]
              (->> changed-cells
                   (reduce #(update %1 :layout-grid-cells assoc (:id %2) %2) parent))))
          parent))))

;; Adding a track creates the cells. We should check the shapes that are not tracked (with default values) and assign to the correct tracked values

(defn add-grid-track
  ([type parent value]
   (add-grid-track type parent value nil))
  ([type parent value index]
   (dm/assert!
    "expected a valid grid definition for `value`"
    (check-grid-track! value))

   (let [[tracks-prop tracks-prop-other prop prop-other prop-span prop-span-other]
         (if (= type :column)
           [:layout-grid-columns :layout-grid-rows    :column :row    :column-span :row-span]
           [:layout-grid-rows    :layout-grid-columns :row    :column :row-span    :column-span])

         new-index (d/nilv index (count (get parent tracks-prop)))
         new-track-num (inc new-index)

         ;; Increase the values for the existing cells
         layout-grid-cells
         (-> (:layout-grid-cells parent)
             (update-vals
              (fn [cell]
                (cond-> cell
                  (>= (get cell prop) new-track-num)
                  (update prop inc)

                  (and (< (get cell prop) new-track-num)
                       (> (get cell prop-span) 1)
                       (>= (+ (get cell prop) (dec (get cell prop-span))) new-track-num))
                  (update prop-span inc)))))

         ;; Search for the cells already created
         exist-cells?
         (into #{}
               (comp (filter
                      (fn [cell]
                        (and (>= new-track-num (get cell prop))
                             (< new-track-num (+ (get cell prop) (get cell prop-span))))))
                     (mapcat #(range (get % prop-other) (+ (get % prop-other) (get % prop-span-other)))))
               (vals layout-grid-cells))

         ;; Create the new cells as necesary
         layout-grid-cells
         (->> (d/enumerate (get parent tracks-prop-other))
              (remove (fn [[idx _]] (exist-cells? (inc idx))))
              (reduce
               (fn [result [idx _]]
                 (let [id (uuid/next)]
                   (assoc result id
                          (merge {:id id
                                  prop-other (inc idx)
                                  prop new-track-num}
                                 grid-cell-defaults))))
               layout-grid-cells))]

     (-> parent
         (update tracks-prop d/add-at-index new-index value)
         (assoc :layout-grid-cells layout-grid-cells)))))

(defn add-grid-column
  ([parent value]
   (add-grid-column parent value nil))
  ([parent value index]
   (add-grid-track :column parent value index)))

(defn add-grid-row
  ([parent value]
   (add-grid-row parent value nil))
  ([parent value index]
   (add-grid-track :row parent value index)))

(defn- duplicate-cells
  [shape prop from-index to-index ids-map]

  (let [[prop-span prop-other prop-other-span]
        (if (= prop :column)
          [:column-span :row :row-span]
          [:row-span :column :column-span])

        from-cells
        (if (= prop :column)
          (cells-by-column shape from-index)
          (cells-by-row shape from-index))

        to-cells
        (if (= prop :column)
          (cells-by-column shape to-index)
          (cells-by-row shape to-index))

        to-cells-idx (d/index-by prop-other to-cells)

        ;; This loop will go throught the original cells and copy their data to the target cell
        ;; After this some cells could have no correspondence and should be removed
        [shape matched]
        (loop [from-cells (seq from-cells)
               matched    #{}
               result     shape]
          (if-let [cell (first from-cells)]
            (let [match-cell
                  (-> (get to-cells-idx (get cell prop-other))
                      (d/patch-object (select-keys cell [prop-other-span :position :align-self :justify-self]))
                      (cond-> (= :area (:position cell))
                        (assoc :position :manual))
                      (cond-> (= (get cell prop-span) 1)
                        (assoc :shapes (mapv ids-map (:shapes cell)))))]
              (recur (rest from-cells)
                     (conj matched (:id match-cell))
                     (assoc-in result [:layout-grid-cells (:id match-cell)] match-cell)))

            [result matched]))

        ;; Remove cells that haven't been matched
        shape
        (->> to-cells
             (remove (fn [{:keys [id]}] (contains? matched id)))
             (reduce (fn [shape cell]
                       (update shape :layout-grid-cells dissoc (:id cell)))
                     shape))]

    shape))


(defn duplicate-row
  [shape objects index ids-map]
  (let [value (dm/get-in shape [:layout-grid-rows index])]
    (-> shape
        (remove-cell-areas-after :row index)
        (add-grid-row value (inc index))
        (duplicate-cells :row index (inc index) ids-map)
        (assign-cells objects))))

(defn duplicate-column
  [shape objects index ids-map]
  (let [value (dm/get-in shape [:layout-grid-columns index])]
    (-> shape
        (remove-cell-areas-after :column index)
        (add-grid-column value (inc index))
        (duplicate-cells :column index (inc index) ids-map)
        (assign-cells objects))))

(defn make-remove-cell
  [attr span-attr track-num]
  (fn [[_ cell]]
    ;; Only remove cells with span=1 otherwise the cell will be fixed
    (and (= track-num (get cell attr))
         (= (get cell span-attr) 1))))

(defn make-decrease-track-num
  [attr span-attr track-num]
  (fn [[id cell]]
    (let [inner-track?
          (or (= track-num (get cell attr))
              (< (get cell attr) track-num (+ (get cell attr) (get cell span-attr))))

          displace-cell?
          (and (not inner-track?) (< track-num (get cell attr)))

          cell
          (cond-> cell
            inner-track?
            (update span-attr dec)

            displace-cell?
            (update attr dec))]

      [id cell])))

(defn remove-grid-column
  [parent index objects]

  (let [track-num (inc index)

        decrease-track-num (make-decrease-track-num :column :column-span track-num)
        remove-track? (make-remove-cell :column :column-span track-num)

        update-cells
        (fn [cells]
          (into {}
                (comp (remove remove-track?)
                      (map decrease-track-num))
                cells))]
    (-> parent
        (update :layout-grid-columns d/remove-at-index index)
        (update :layout-grid-cells update-cells)
        (assign-cells objects))))

(defn remove-grid-row
  [parent index objects]
  (let [track-num (inc index)

        decrease-track-num (make-decrease-track-num :row :row-span track-num)
        remove-track? (make-remove-cell :row :row-span track-num)

        update-cells
        (fn [cells]
          (into {}
                (comp (remove remove-track?)
                      (map decrease-track-num))
                cells))]
    (-> parent
        (update :layout-grid-rows d/remove-at-index index)
        (update :layout-grid-cells update-cells)
        (assign-cells objects))))

(defn- reorder-grid-tracks
  "Swap the positions of the tracks info"
  [parent prop from-index to-index]
  (-> parent
      (update
       prop
       (fn [tracks]
         (let [tr (nth tracks from-index)]
           (mapv
            second
            (-> tracks
                (d/enumerate) ;; make unique so the insert-at-index won't remove the value
                (assoc from-index nil)
                (d/insert-at-index (inc to-index) [[nil tr]])
                (d/vec-without-nils))))))))

(defn- swap-track-content
  "Swap the shapes contained in the given tracks moves as necessary the others."
  [parent prop from-track to-track]
  (let [remap-tracks
        (cond
          (> from-track to-track)
          (into {from-track to-track}
                (map #(vector % (inc %)))
                (range to-track from-track))
          (< from-track to-track)
          (into {from-track to-track}
                (map #(vector % (dec %)))
                (range (inc from-track) (inc to-track))))]
    (-> parent
        (update
         :layout-grid-cells
         update-vals
         (fn [cell] (update cell prop #(get remap-tracks % %)))))))


(defn- reorder-grid-track
  [parent from-index to-index move-content? tracks-props prop]
  (let [from-track (inc from-index)
        to-track   (if (< to-index from-index)
                     (+ to-index 2)
                     (inc to-index))
        move-content?
        (and move-content? (not= from-track to-track))

        parent
        (cond-> parent
          move-content?
          (-> (remove-cell-areas prop from-index)
              (remove-cell-areas-after prop to-index)))

        parent
        (reorder-grid-tracks parent tracks-props from-index to-index)]

    (cond-> parent
      move-content?
      (swap-track-content prop from-track to-track))))

(defn reorder-grid-column
  [parent from-index to-index move-content?]
  (reorder-grid-track parent from-index to-index move-content? :layout-grid-columns :column))

(defn reorder-grid-row
  [parent from-index to-index move-content?]
  (reorder-grid-track parent from-index to-index move-content? :layout-grid-rows :row))

(defn cells-seq
  [{:keys [layout-grid-cells layout-grid-dir]} & {:keys [sort?] :or {sort? false}}]

  (let [comp-fn (if (= layout-grid-dir :row)
                  (juxt :row :column)
                  (juxt :column :row))
        maybe-sort?
        (if sort? (partial sort-by (comp comp-fn second)) identity)]

    (->> layout-grid-cells
         (maybe-sort?)
         (map second))))

(defn get-free-cells
  ([parent]
   (get-free-cells parent nil))

  ([parent {:keys [sort?] :or {sort? false}}]
   (->> (cells-seq parent :sort? sort?)
        (filter (comp empty? :shapes))
        (map :id))))

(defn get-cells
  ([parent]
   (get-cells parent nil))

  ([parent {:keys [sort? remove-empty?] :or {sort? false remove-empty? false}}]
   (let [maybe-remove?
         (if remove-empty? (partial remove (comp empty? :shapes)) identity)]

     (->> (cells-seq parent :sort? sort?)
          (maybe-remove?)))))

(defn check-deassigned-cells
  "Clean the cells whith shapes that are no longer in the layout"
  [parent objects]

  (let [child-set (set (:shapes parent))

        assigned?
        (fn [id]
          (and (contains? child-set id)
               (not (position-absolute? objects id))))

        cells
        (update-vals
         (:layout-grid-cells parent)
         (fn [cell]
           (-> cell
               (update :shapes #(filterv assigned? %)))))]

    (-> parent
        (assoc :layout-grid-cells cells))))

(defn overlapping-cells
  "Find overlapping cells"
  [parent]
  (let [cells (->> parent
                   :layout-grid-cells
                   (map (fn [[id cell]]
                          [id (sga/make-area cell)])))
        find-overlaps
        (fn [result [id area]]
          (let [[fid _]
                (d/seek #(and (not= (first %) id)
                              (sga/intersects? (second %) area))
                        cells)]
            (cond-> result
              (some? fid)
              (conj #{id fid}))))]
    (reduce find-overlaps #{} cells)))

;; FIXME: This is only for development
(defn fix-overlaps
  [parent overlaps]
  (reduce (fn [parent ids]
            (let [id (if (empty? (get-in parent [:layout-grid-cells (first ids)]))
                       (first ids)
                       (second ids))]
              (update parent :layout-grid-cells dissoc id)))
          parent
          overlaps))

(defn reassign-positions
  "Propagate the manual positioning to the following cells"
  [parent]
  (->> (cells-seq parent :sort? true)
       (reduce
        (fn [[parent auto?] cell]
          (let [[cell auto?]
                (cond
                  (and (empty? (:shapes cell))
                       (= :manual (:position cell))
                       (= (:row-span cell) 1)
                       (= (:column-span cell) 1))
                  [cell false]

                  (and (or (not= (:row-span cell) 1)
                           (not= (:column-span cell) 1))
                       (= :auto (:position cell)))
                  [(assoc cell :position :manual) false]

                  (empty? (:shapes cell))
                  [cell false]

                  (and (not auto?) (= :auto (:position cell)))
                  [(assoc cell :position :manual) false]

                  (= :manual (:position cell))
                  [cell false]

                  :else
                  [cell auto?])]
            [(assoc-in parent [:layout-grid-cells (:id cell)] cell) auto?]))
        [parent true])
       (first)))

(defn position-auto-shapes
  [parent]
  ;; Iterate through the cells. While auto and contains shape no changes.
  ;; If auto without shape start moving auto
  ;; Move shapes in auto-cells to the first free auto.
  (let [auto-cells (->> (cells-seq parent :sort? true)
                        (filter #(and (= (:position %) :auto)
                                      (= (:row-span %) 1)
                                      (= (:column-span %) 1))))

        shapes     (->> auto-cells (mapcat :shapes))

        parent
        (loop [parent parent
               cells (seq auto-cells)
               shapes (seq shapes)]
          (if (empty? cells)
            parent
            (let [shape (first shapes)
                  cell (first cells)
                  parent (assoc-in parent [:layout-grid-cells (:id cell) :shapes] (if (some? shape) [shape] []))]
              (recur parent
                     (rest cells)
                     (rest shapes)))))]
    parent))

(defn assign-cell-positions
  [parent objects]
  (-> parent
      (check-deassigned-cells objects)
      (reassign-positions)
      (position-auto-shapes)))

;; Assign cells takes the children and move them into the allotted cells. If there are not enough cells it creates
;; not-tracked rows/columns and put the shapes there
;;   Non-tracked tracks need to be deleted when they are empty and there are no more shapes unallocated
;; Should be caled each time a child can be added like:
;;  - On shape creation
;;  - When moving a child from layers
;;  - Moving from the transform into a cell and there are shapes without cell
;;  - Shape duplication
;;  - (maybe) create group/frames. This case will assigna a cell that had one of its children
(defn assign-cells
  [parent objects]
  (let [parent (assign-cell-positions parent objects)

        shape-has-cell?
        (into #{} (mapcat (comp :shapes second)) (:layout-grid-cells parent))

        no-cell-shapes
        (->> (:shapes parent)
             (remove shape-has-cell?)
             (remove (partial position-absolute? objects)))]

    (if (empty? no-cell-shapes)
      ;; All shapes are within a cell. No need to assign
      parent

      (let [;; We need to have at least 1 col and 1 row otherwise we can't assign
            parent
            (cond-> parent
              (empty? (:layout-grid-columns parent))
              (add-grid-column default-track-value)

              (empty? (:layout-grid-rows parent))
              (add-grid-row default-track-value))

            ;; Free cells should be ordered columns/rows depending on the parameter
            ;; in the parent
            free-cells (get-free-cells parent)

            to-add-tracks
            (if (= (:layout-grid-dir parent) :row)
              (mth/ceil (/ (- (count no-cell-shapes) (count free-cells)) (count (:layout-grid-rows parent))))
              (mth/ceil (/ (- (count no-cell-shapes) (count free-cells)) (count (:layout-grid-columns parent)))))

            add-track (if (= (:layout-grid-dir parent) :row) add-grid-column add-grid-row)

            parent
            (->> (range to-add-tracks)
                 (reduce (fn [parent _] (add-track parent default-track-value)) parent))

            cells
            (loop [cells      (:layout-grid-cells parent)
                   free-cells (get-free-cells parent {:sort? true})
                   pending    no-cell-shapes]

              (if (or (empty? free-cells) (empty? pending))
                cells
                (let [next-free (first free-cells)
                      current (first pending)
                      cells (-> cells
                                (update-in [next-free :shapes] conj current)
                                (assoc-in [next-free :position] :auto))]
                  (recur cells (rest free-cells) (rest pending)))))]

        ;; TODO: Remove after testing
        (assert (empty? (overlapping-cells parent)) (dm/str (overlapping-cells parent)))
        (assoc parent :layout-grid-cells cells)))))

(defn free-cell-push
  "Frees the cell at index and push the shapes in the order given by the `cells` attribute"
  [parent cells index]

  (let [start-cell (get cells index)]
    (if (empty? (:shapes start-cell))
      [parent cells]
      (let [[parent result-cells]
            (loop [parent parent
                   result-cells cells
                   idx index]

              (if (> idx (- (count cells) 2))
                [parent result-cells]

                (let [cell-from (get cells idx)
                      cell-to   (get cells (inc idx))
                      cell (assoc cell-to :shapes (:shapes cell-from) :position (:position cell-from))
                      parent (assoc-in parent [:layout-grid-cells (:id cell)] cell)
                      result-cells (assoc result-cells (inc idx) cell)]

                  (if (empty? (:shapes cell-to))
                    ;; to-cell was empty, so we've finished and every cell allocated
                    [parent result-cells]

                    ;; otherwise keep pushing cells
                    (recur parent result-cells (inc idx))))))]

        [(assoc-in parent [:layout-grid-cells (get-in cells [index :id]) :shapes] [])
         (update result-cells index assoc :shapes [] :position :auto)]))))


(defn in-cell?
  "Given a cell check if the row+column is inside this cell"
  [{cell-row :row cell-column :column :keys [row-span column-span]} row column]
  (and (>= row cell-row)
       (>= column cell-column)
       (<= row (+ cell-row row-span -1))
       (<= column (+ cell-column column-span -1))))

(defn cell-by-row-column
  [parent row column]
  (->> (:layout-grid-cells parent)
       (vals)
       (d/seek #(in-cell? % row column))))

(defn seek-indexed-cell
  [cells row column]
  (let [cells+index (d/enumerate cells)]
    (d/seek #(in-cell? (second %) row column) cells+index)))

(defn free-cell-shapes
  "Removes the shape-ids from the cells previously assigned."
  [parent shape-ids]
  (let [shape-ids (set shape-ids)]
    (letfn [(free-cells
              [cells]
              (reduce-kv
               (fn [m k v]
                 (if (some shape-ids (:shapes v))
                   (assoc-in m [k :shapes] [])
                   m))
               cells
               cells))]
      (update parent :layout-grid-cells free-cells))))

(defn push-into-cell
  "Push the shapes into the row/column cell and moves the rest"
  [parent shape-ids row column]

  (let [parent (-> parent (free-cell-shapes shape-ids))
        cells (vec (get-cells parent {:sort? true}))
        [start-index start-cell] (seek-indexed-cell cells row column)]
    (if (some? start-cell)
      (let [;; start-index => to-index is the range where the shapes inserted will be added
            to-index (min (+ start-index (count shape-ids)) (dec (count cells)))]

        ;; Move shift the `shapes` attribute between cells
        (->> (range start-index (inc to-index))
             (map vector shape-ids)
             (reduce
              (fn [[parent cells] [shape-id idx]]
                ;; If the shape to put in a cell is the same that is already in the cell we do nothing
                (if (= shape-id (get-in parent [:layout-grid-cells (get-in cells [idx :id]) :shapes 0]))
                  [parent cells]
                  (let [[parent cells] (free-cell-push parent cells idx)]
                    [(update-in parent [:layout-grid-cells (get-in cells [idx :id])]
                                assoc :position :manual
                                :shapes [shape-id])
                     cells])))
              [parent cells])
             (first)))
      parent)))

(defn create-cells
  "Create cells in an area. One cell per row/column "
  [parent [column row column-span row-span]]

  (->> (for [row (range row (+ row row-span))
             column (range column (+ column column-span))]
         (merge grid-cell-defaults
                {:id (uuid/next)
                 :row row
                 :column column
                 :row-span 1
                 :column-span 1}))
       (reduce #(assoc-in %1 [:layout-grid-cells (:id %2)] %2) parent)))

(defn resize-cell-area
  "Increases/decreases the cell size"
  [parent row column new-row new-column new-row-span new-column-span]
  (if (and (>= new-row 1)
           (>= new-column 1)
           (>= new-row-span 1)
           (>= new-column-span 1))
    (let [prev-cell (cell-by-row-column parent row column)
          prev-area (sga/make-area prev-cell)

          target-cell
          (-> prev-cell
              (assoc
               :position :manual
               :row new-row
               :column new-column
               :row-span new-row-span
               :column-span new-column-span))

          target-area (sga/make-area target-cell)

          ;; Create columns/rows if necessary
          parent
          (->> (range (count (:layout-grid-columns parent))
                      (+ new-column new-column-span -1))
               (reduce (fn [parent _] (add-grid-column parent default-track-value)) parent))

          parent
          (->> (range (count (:layout-grid-rows parent))
                      (+ new-row new-row-span -1))
               (reduce (fn [parent _] (add-grid-row parent default-track-value)) parent))

          parent (create-cells parent prev-area)

          cells (vec (get-cells parent {:sort? true}))
          remove-cells
          (->> cells
               (filter #(and (not= (:id target-cell) (:id %))
                             (sga/contains? target-area (sga/make-area %))))
               (into #{}))

          split-cells
          (->> cells
               (filter #(and (not= (:id target-cell) (:id %))
                             (not (contains? remove-cells %))
                             (sga/intersects? target-area (sga/make-area %)))))

          [parent _]
          (->> (d/enumerate cells)
               (reduce (fn [[parent cells] [index cur-cell]]
                         (if (contains? remove-cells cur-cell)
                           (let [[parent cells] (free-cell-push parent cells index)]
                             [parent (conj cells cur-cell)])
                           [parent cells]))
                       [parent cells]))

          parent
          (-> parent
              (assoc-in [:layout-grid-cells (:id target-cell)] target-cell))

          parent
          (->> remove-cells
               (reduce (fn [parent cell]
                         (update parent :layout-grid-cells dissoc (:id cell)))
                       parent))

          parent
          (->> split-cells
               (reduce (fn [parent cell]
                         (let [new-areas (sga/difference (sga/make-area cell) target-area)]
                           (as-> parent $
                             (update-in $ [:layout-grid-cells (:id cell)] merge (sga/area->cell-props (first new-areas)))
                             (reduce (fn [parent area]
                                       (let [cell (merge (assoc grid-cell-defaults :id (uuid/next)) (sga/area->cell-props area))]
                                         (assoc-in parent [:layout-grid-cells (:id cell)] cell))) $ new-areas))))
                       parent))]
      parent)

    ;; Not valid resize: we don't alter the layout
    parent))


(defn get-cell-by-position
  [parent target-row target-column]
  (->> (:layout-grid-cells parent)
       (d/seek
        (fn [[_ {:keys [column row column-span row-span]}]]
          (and (>= target-row row)
               (>= target-column column)
               (< target-column (+ column column-span))
               (< target-row (+ row row-span)))))
       (second)))

(defn get-cell-by-shape-id
  [parent shape-id]
  (->> (:layout-grid-cells parent)
       (d/seek
        (fn [[_ {:keys [shapes]}]]
          (contains? (set shapes) shape-id)))
       (second)))

(defn swap-shapes
  [parent id-from id-to]

  (let [cell-to (dm/get-in parent [:layout-grid-cells id-to])
        cell-from (dm/get-in parent [:layout-grid-cells id-from])]
    (-> parent
        (update-in [:layout-grid-cells id-from]
                   assoc
                   :shapes (:shapes cell-to)
                   :podition (:position cell-to))
        (update-in [:layout-grid-cells id-to]
                   assoc
                   :shapes (:shapes cell-from)
                   :position (:position cell-from)))))

(defn add-children-to-cell
  [frame children objects [row column :as cell]]
  (let [;; Temporary remove the children when moving them
        frame (-> frame
                  (update :shapes #(d/removev children %))
                  (assign-cells objects))

        children (->> children (remove #(position-absolute? objects %)))]

    (-> frame
        (update :shapes d/concat-vec children)
        (cond-> (some? cell)
          (push-into-cell children row column))
        (assign-cells objects))))

(defn get-cell-by-index
  [parent to-index]
  (let [cells (get-cells parent {:sort? true :remove-empty? true})
        to-index (- (count cells) to-index 1)]
    (nth cells to-index nil)))

(defn add-children-to-index
  [parent ids objects to-index]
  (let [target-cell (get-cell-by-index parent to-index)]
    (cond-> parent
      (some? target-cell)
      (add-children-to-cell ids objects [(:row target-cell) (:column target-cell)]))))

(defn reorder-grid-children
  [parent]
  (let [cells (get-cells parent {:sort? true})
        child? (set (:shapes parent))
        new-shapes
        (into (d/ordered-set)
              (comp (keep (comp first :shapes))
                    (filter child?))
              cells)

        ;; Add the children that are not in cells (absolute positioned for example)
        new-shapes (into new-shapes (:shapes parent))]

    (assoc parent :shapes (into [] (reverse new-shapes)))))

(defn cells-by-row
  ([parent index]
   (cells-by-row parent index true))
  ([parent index check-span?]
   (->> (:layout-grid-cells parent)
        (vals)
        (filter
         (fn [{:keys [row row-span]}]
           (if check-span?
             (and (>= (inc index) row)
                  (< (inc index) (+ row row-span)))
             (= (inc index) row)))))))

(defn cells-by-column
  ([parent index]
   (cells-by-column parent index true))
  ([parent index check-span?]
   (->> (:layout-grid-cells parent)
        (vals)
        (filter
         (fn [{:keys [column column-span] :as cell}]
           (if check-span?
             (and (>= (inc index) column)
                  (< (inc index) (+ column column-span)))
             (= (inc index) column)))))))

(defn cells-in-area
  [parent first-row last-row first-column last-column]
  (->> (:layout-grid-cells parent)
       (vals)
       (filter
        (fn [{:keys [row column row-span column-span] :as cell}]
          (and
           (or (<= row first-row (+ row row-span -1))
               (<= row last-row (+ row row-span -1))
               (<= first-row row last-row)
               (<= first-row (+ row row-span -1) last-row))

           (or (<= column first-column (+ column column-span -1))
               (<= column last-column (+ column column-span -1))
               (<= first-column column last-column)
               (<= first-column (+ column column-span -1) last-column)))))))

(defn shapes-by-row
  "Find all the shapes for a given row"
  ([parent index]
   (shapes-by-row parent index true))
  ;; check-span? if false will only see if there is a coincidence in file&row
  ([parent index check-span?]
   (->> (cells-by-row parent index check-span?)
        (mapcat :shapes))))

(defn shapes-by-column
  "Find all the shapes for a given column"
  ([parent index]
   (shapes-by-column parent index true))
  ([parent index check-span?]
   (->> (cells-by-column parent index check-span?)
        (mapcat :shapes))))

(defn cells-coordinates
  "Given a group of cells returns the coordinates that define"
  [cells]
  (loop [cells (seq cells)
         result
         {:first-row ##Inf
          :first-column ##Inf
          :last-row ##-Inf
          :last-column ##-Inf
          :cell-coords #{}}]

    (if (empty? cells)
      result
      (let [{:keys [first-row last-row first-column last-column cell-coords]} result
            current (first cells)

            first-row
            (if (< (:row current) first-row)
              (:row current)
              first-row)

            last-row
            (if (> (+ (:row current) (:row-span current) -1) last-row)
              (+ (:row current) (:row-span current) -1)
              last-row)

            first-column
            (if (< (:column current) first-column)
              (:column current)
              first-column)

            last-column
            (if (> (+ (:column current) (:column-span current) -1) last-column)
              (+ (:column current) (:column-span current) -1)
              last-column)

            cell-coords
            (into cell-coords
                  (for [r (range (:row current) (+ (:row current) (:row-span current)))
                        c (range (:column current) (+ (:column current) (:column-span current)))]
                    [r c]))]
        (recur (rest cells)
               (assoc result
                      :first-row first-row
                      :last-row last-row
                      :first-column first-column
                      :last-column last-column
                      :cell-coords cell-coords))))))

(defn valid-area-cells?
  [cells]
  (let [{:keys [first-row last-row first-column last-column cell-coords]} (cells-coordinates cells)]
    (every?
     #(contains? cell-coords %)
     (for [r (range first-row (inc last-row))
           c (range first-column (inc last-column))]
       [r c]))))

(defn remap-grid-cells
  "Remaps the shapes ids inside the cells"
  [shape ids-map]
  (let [remap-shape
        (fn [id]
          (get ids-map id id))

        remap-cell
        (fn [cell]
          (-> cell
              (update :shapes #(into [] (keep remap-shape) %))))
        shape
        (-> shape
            (update :layout-grid-cells update-vals remap-cell))]
    shape))

(defn merge-cells
  "Given target cells update with source cells while trying to keep target as
  untouched as possible"
  [target-cells source-cells omit-touched?]
  (if (not omit-touched?)
    source-cells

    (letfn [(get-data [cells id]
              (dissoc (get cells id) :shapes :row :column :row-span :column-span))]
      (let [deleted-cells
            (into #{}
                  (filter #(not (contains? source-cells %)))
                  (keys target-cells))

            touched-cells
            (into #{}
                  (filter #(and
                            (not (contains? deleted-cells %))
                            (not= (get-data source-cells %)
                                  (get-data target-cells %))))
                  (keys target-cells))]

        (->> touched-cells
             (reduce
              (fn [cells id]
                (-> cells
                    (d/update-when id d/patch-object (get-data target-cells id))))
              source-cells))))))
