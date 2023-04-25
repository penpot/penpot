;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.layout
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]))

;; FIXME: need proper schemas

;; :layout                 ;; :flex, :grid in the future
;; :layout-flex-dir        ;; :row, :row-reverse, :column, :column-reverse
;; :layout-gap-type        ;; :simple, :multiple
;; :layout-gap             ;; {:row-gap number , :column-gap number}

;; :layout-align-items     ;; :start :end :center :stretch
;; :layout-justify-content ;; :start :center :end :space-between :space-around :space-evenly
;; :layout-align-content   ;; :start :center :end :space-between :space-around :space-evenly :stretch (by default)
;; :layout-wrap-type       ;; :wrap, :nowrap
;; :layout-padding-type    ;; :simple, :multiple
;; :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative

;; layout-grid-rows
;; layout-grid-columns
;; layout-justify-items

;; ITEMS
;; :layout-item-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
;; :layout-item-margin-type ;; :simple :multiple
;; :layout-item-h-sizing    ;; :fill :fix :auto
;; :layout-item-v-sizing    ;; :fill :fix :auto
;; :layout-item-max-h       ;; num
;; :layout-item-min-h       ;; num
;; :layout-item-max-w       ;; num
;; :layout-item-min-w       ;; num
;; :layout-item-absolute
;; :layout-item-z-index

(def layout-types
  #{:flex :grid})

(def flex-direction-types
  #{:row :reverse-row :row-reverse :column :reverse-column :column-reverse}) ;;TODO remove reverse-column and reverse-row after script

(def gap-types
  #{:simple :multiple})

(def wrap-types
  #{:wrap :nowrap :no-wrap}) ;;TODO remove no-wrap after script

(def padding-type
  #{:simple :multiple})

(def justify-content-types
  #{:start :center :end :space-between :space-around :space-evenly})

(def align-content-types
  #{:start :end :center :space-between :space-around :space-evenly :stretch})

(def align-items-types
  #{:start :end :center :stretch})

(def justify-items-types
  #{:start :end :center :stretch})

(sm/def! ::layout-attrs
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
   [:layout-align-items {:optional true} [::sm/one-of align-items-types]]])

;; (s/def :grid/type #{:percent :flex :auto :fixed})
;; (s/def :grid/value (s/nilable ::us/safe-number))
;; (s/def ::grid-definition (s/keys :req-un [:grid/type]
;;                                  :opt-un [:grid/value]))
;; (s/def ::layout-grid-rows (s/coll-of ::grid-definition :kind vector?))
;; (s/def ::layout-grid-columns (s/coll-of ::grid-definition :kind vector?))

;; (s/def :grid-cell/id uuid?)
;; (s/def :grid-cell/area-name ::us/string)
;; (s/def :grid-cell/row-start ::us/safe-integer)
;; (s/def :grid-cell/row-span ::us/safe-integer)
;; (s/def :grid-cell/column-start ::us/safe-integer)
;; (s/def :grid-cell/column-span ::us/safe-integer)
;; (s/def :grid-cell/position #{:auto :manual :area})
;; (s/def :grid-cell/align-self #{:auto :start :end :center :stretch})
;; (s/def :grid-cell/justify-self #{:auto :start :end :center :stretch})
;; (s/def :grid-cell/shapes (s/coll-of uuid?))

;; (s/def ::grid-cell (s/keys :opt-un [:grid-cell/id
;;                                     :grid-cell/area-name
;;                                     :grid-cell/row-start
;;                                     :grid-cell/row-span
;;                                     :grid-cell/column-start
;;                                     :grid-cell/column-span
;;                                     :grid-cell/position ;; auto, manual, area
;;                                     :grid-cell/align-self
;;                                     :grid-cell/justify-self
;;                                     :grid-cell/shapes]))
;; (s/def ::layout-grid-cells (s/map-of uuid? ::grid-cell))

;; (s/def ::layout-container-props
;;   (s/keys :opt-un [
;;                    ;; grid
;;                    ::layout-grid-dir
;;                    ::layout-justify-items
;;                    ::layout-grid-rows
;;                    ::layout-grid-columns
;;                    ::layout-grid-cells
;;                    ]))


(def item-margin-types
  #{:simple :multiple})

(def item-h-sizing-types
  #{:fill :fix :auto})

(def item-v-sizing-types
  #{:fill :fix :auto})

(def item-align-self-types
  #{:start :end :center :stretch})

(sm/def! ::layout-child-attrs
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

(def schema:grid-definition
  [:map {:title "LayoutGridDefinition"}
   [:type [::sm/one-of #{:percent :flex :auto :fixed}]]
   [:value {:optional true} [:maybe ::sm/safe-int]]])

(def grid-definition?
  (sm/pred-fn schema:grid-definition))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-layouts
  #{:flex :grid})

(sm/def! ::layout
  [::sm/one-of valid-layouts])

(defn flex-layout?
  ([objects id]
   (flex-layout? (get objects id)))
  ([shape]
   (and (= :frame (:type shape))
        (= :flex (:layout shape)))))

(defn grid-layout?
  ([objects id]
   (grid-layout? (get objects id)))
  ([shape]
   (and (= :frame (:type shape))
        (= :grid (:layout shape)))))

(defn any-layout?
  ([objects id]
   (any-layout? (get objects id)))

  ([shape]
   (or (flex-layout? shape) (grid-layout? shape))))

(defn flex-layout-immediate-child? [objects shape]
  (let [parent-id (:parent-id shape)
        parent (get objects parent-id)]
    (flex-layout? parent)))

(defn any-layout-immediate-child? [objects shape]
  (let [parent-id (:parent-id shape)
        parent (get objects parent-id)]
    (any-layout? parent)))

(defn flex-layout-immediate-child-id? [objects id]
  (let [parent-id (dm/get-in objects [id :parent-id])
        parent (get objects parent-id)]
    (flex-layout? parent)))

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

  (loop [current-id (:id shape)]
    (let [current (get objects current-id)]
      (cond
        (or (nil? current) (= current-id (:parent-id current)))
        false

        (= :frame (:type current))
        (:layout current)

        :else
        (recur (:parent-id current))))))

(defn wrap? [{:keys [layout-wrap-type]}]
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

(defn child-min-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-item-min-w child)))
    (max 0 (:layout-item-min-w child))
    0))

(defn child-max-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-item-max-w child)))
    (max 0 (:layout-item-max-w child))
    ##Inf))

(defn child-min-height
  [child]
  (if (and (fill-height? child)
           (some? (:layout-item-min-h child)))
    (max 0 (:layout-item-min-h child))
    0))

(defn child-max-height
  [child]
  (if (and (fill-height? child)
           (some? (:layout-item-max-h child)))
    (max 0 (:layout-item-max-h child))
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
  [{:keys [layout-flex-dir]}]
  (or (= :row-reverse layout-flex-dir)
      (= :column-reverse layout-flex-dir)))

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

(defn layout-absolute?
  ([objects id]
   (layout-absolute? (get objects id)))
  ([shape]
   (true? (:layout-item-absolute shape))))

(defn layout-z-index
  ([objects id]
   (layout-z-index (get objects id)))
  ([shape]
   (or (:layout-item-z-index shape) 0)))

(defn change-h-sizing?
  [frame-id objects children-ids]
  (and (flex-layout? objects frame-id)
       (auto-width? objects frame-id)
       (or (and (col? objects frame-id)
                (->> children-ids
                     (remove (partial layout-absolute? objects))
                     (every? (partial fill-width? objects))))
           (and (row? objects frame-id)
                (->> children-ids
                     (remove (partial layout-absolute? objects))
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
          :layout-justify-content
          :layout-align-items
          :layout-align-content
          :layout-grid-dir
          :layout-justify-items
          :layout-grid-columns
          :layout-grid-rows
          ))

(defn remove-layout-item-data
  [shape]
  (dissoc shape
          :layout-item-margin
          :layout-item-margin-type
          :layout-item-h-sizing
          :layout-item-v-sizing
          :layout-item-max-h
          :layout-item-min-h
          :layout-item-max-w
          :layout-item-min-w
          :layout-item-align-self
          :layout-item-absolute
          :layout-item-z-index))

(defn update-flex-scale
  [shape scale]
  (-> shape
      (d/update-in-when [:layout-gap :row-gap] * scale)
      (d/update-in-when [:layout-gap :column-gap] * scale)
      (d/update-in-when [:layout-padding :p1] * scale)
      (d/update-in-when [:layout-padding :p2] * scale)
      (d/update-in-when [:layout-padding :p3] * scale)
      (d/update-in-when [:layout-padding :p4] * scale)))

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
  {:type :auto})

(def grid-cell-defaults
  {:row-span 1
   :column-span 1
   :position :auto
   :align-self :auto
   :justify-self :auto
   :shapes []})

;; TODO: GRID ASSIGNMENTS

;; Adding a track creates the cells. We should check the shapes that are not tracked (with default values) and assign to the correct tracked values
(defn add-grid-column
  [parent value]
  (dm/assert!
   "expected a valid grid definition for `value`"
   (grid-definition? value))

  (let [rows (:layout-grid-rows parent)
        new-col-num (inc (count (:layout-grid-columns parent)))

        layout-grid-cells
        (->> (d/enumerate rows)
             (reduce (fn [result [row-idx _row]]
                       (let [id (uuid/next)]
                         (assoc result id
                                (merge {:id id
                                        :row (inc row-idx)
                                        :column new-col-num
                                        :track? true}
                                       grid-cell-defaults))))
                     (:layout-grid-cells parent)))]
    (-> parent
        (update :layout-grid-columns (fnil conj []) value)
        (assoc :layout-grid-cells layout-grid-cells))))

(defn add-grid-row
  [parent value]
  (dm/assert!
   "expected a valid grid definition for `value`"
   (grid-definition? value))

  (let [cols (:layout-grid-columns parent)
        new-row-num (inc (count (:layout-grid-rows parent)))

        layout-grid-cells
        (->> (d/enumerate cols)
             (reduce (fn [result [col-idx _col]]
                       (let [id (uuid/next)]
                         (assoc result id
                                (merge {:id id
                                        :column (inc col-idx)
                                        :row new-row-num
                                        :track? true}
                                       grid-cell-defaults))))
                     (:layout-grid-cells parent)))]
    (-> parent
        (update :layout-grid-rows (fnil conj []) value)
        (assoc :layout-grid-cells layout-grid-cells))))


;; TODO: SPAN NOT CHECK!!
(defn make-remove-track
  [attr track-num]
  (comp #(= % track-num) attr second))

(defn make-decrease-track-num
  [attr track-num]
  (fn [[key value]]
    (let [new-val
          (cond-> value
            (> (get value attr) track-num)
            (update attr dec))]
      [key new-val])))

(defn remove-grid-column
  [parent index]
  (let [track-num (inc index)

        decrease-track-num (make-decrease-track-num :column track-num)
        remove-track? (make-remove-track :column track-num)

        remove-cells
        (fn [cells]
          (into {} (comp
                    (remove remove-track?)
                    (map decrease-track-num)) cells))]
    (-> parent
        (update :layout-grid-columns d/remove-at-index index)
        (update :layout-grid-cells remove-cells)
        (assign-cells))))

(defn remove-grid-row
  [parent index]
  (let [track-num (inc index)

        decrease-track-num (make-decrease-track-num :row track-num)
        remove-track? (make-remove-track :row track-num)

        remove-cells
        (fn [cells]
          (into {} (comp
                    (remove remove-track?)
                    (map decrease-track-num)) cells))]
    (-> parent
        (update :layout-grid-rows d/remove-at-index index)
        (update :layout-grid-cells remove-cells)
        (assign-cells))))

;; TODO: Mix the cells given as arguments leaving only one. It should move all the shapes in those cells in the direction for the grid
;; and lastly use assign-cells to reassing the orphaned shapes
(defn merge-cells
  [parent _cells]
  parent)

(defn get-cells
  ([parent]
   (get-cells parent nil))

  ([{:keys [layout-grid-cells layout-grid-dir]} {:keys [sort?] :or {sort? false}}]
   (let [comp-fn (if (= layout-grid-dir :row)
                   (juxt :row :column)
                   (juxt :column :row))

         maybe-sort?
         (if sort? (partial sort-by (comp comp-fn second)) identity)]

     (->> layout-grid-cells
          (maybe-sort?)
          (map (fn [[id cell]] (assoc cell :id id)))))))

(defn get-free-cells
  ([parent]
   (get-free-cells parent nil))

  ([{:keys [layout-grid-cells layout-grid-dir]} {:keys [sort?] :or {sort? false}}]
   (let [comp-fn (if (= layout-grid-dir :row)
                   (juxt :row :column)
                   (juxt :column :row))

         maybe-sort?
         (if sort? (partial sort-by (comp comp-fn second)) identity)]

     (->> layout-grid-cells
          (filter (comp empty? :shapes second))
          (maybe-sort?)
          (map first)))))

(defn check-deassigned-cells
  "Clean the cells whith shapes that are no longer in the layout"
  [parent]

  (let [child? (set (:shapes parent))
        cells (update-vals
               (:layout-grid-cells parent)
               (fn [cell] (update cell :shapes #(filterv child? %))))]

    (assoc parent :layout-grid-cells cells)))

;; TODO
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
  [parent]
  (let [parent (-> parent check-deassigned-cells)

        shape-has-cell?
        (into #{} (mapcat (comp :shapes second)) (:layout-grid-cells parent))

        no-cell-shapes
        (->> (:shapes parent) (remove shape-has-cell?))]

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
            (loop [cells (:layout-grid-cells parent)
                   free-cells (get-free-cells parent {:sort? true})
                   pending no-cell-shapes]
              (if (or (empty? free-cells) (empty? pending))
                cells
                (let [next-free (first free-cells)
                      current (first pending)
                      cells (update-in cells [next-free :shapes] conj current)]
                  (recur cells (rest free-cells) (rest pending)))))]

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
                      cell (assoc cell-to :shapes (:shapes cell-from))
                      parent (assoc-in parent [:layout-grid-cells (:id cell)] cell)
                      result-cells (assoc result-cells (inc idx) cell)]

                  (if (empty? (:shapes cell-to))
                    ;; to-cell was empty, so we've finished and every cell allocated
                    [parent result-cells]

                    ;; otherwise keep pushing cells
                    (recur parent result-cells (inc idx))))))]

        [(assoc-in parent [:layout-grid-cells (get-in cells [index :id]) :shapes] [])
         (assoc-in result-cells [index :shapes] [])]))))

(defn seek-indexed-cell
  [cells row column]
  (let [cells+index (d/enumerate cells)]
    (d/seek (fn [[_ {cell-row :row cell-column :column}]]
              (and (= cell-row row)
                   (= cell-column column))) cells+index)))

(defn push-into-cell
  "Push the shapes into the row/column cell and moves the rest"
  [parent shape-ids row column]

  (let [cells (vec (get-cells parent {:sort? true}))
        cells+index (d/enumerate cells)

        [start-index _] (seek-indexed-cell cells row column)

        ;; start-index => to-index is the range where the shapes inserted will be added
        to-index (min (+ start-index (count shape-ids)) (dec (count cells)))]

    ;; Move shift the `shapes` attribute between cells
    (->> (range start-index (inc to-index))
         (map vector shape-ids)
         (reduce (fn [[parent cells] [shape-id idx]]
                   (let [[parent cells] (free-cell-push parent cells idx)]
                     [(assoc-in parent [:layout-grid-cells (get-in cells [idx :id]) :shapes] [shape-id])
                      cells]))
                 [parent cells])
         (first))))
