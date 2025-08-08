;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.util.code-gen.style-css-values
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.formats :as fmt]
   [app.util.code-gen.common :as cgc]
   [cuerdas.core :as str]))

(defn fill->color
  [{:keys [fill-color fill-opacity fill-color-gradient fill-image]}]
  {:color fill-color
   :opacity fill-opacity
   :gradient fill-color-gradient
   :image fill-image})

(defmulti get-value
  (fn [property _shape _objects _options] property))

(defmethod get-value :position
  [_ shape objects _]
  (cond
    (or (and (ctl/any-layout-immediate-child? objects shape)
             (not (ctl/position-absolute? shape))
             (or (cfh/group-like-shape? shape)
                 (cfh/frame-shape? shape)
                 (cgc/svg-markup? shape)))
        (cfh/root-frame? shape))
    :relative

    (and (ctl/any-layout-immediate-child? objects shape)
         (not (ctl/position-absolute? shape)))
    nil

    :else
    :absolute))

(defn get-shape-position
  [shape objects coord]

  (when (and (not (cfh/root-frame? shape))
             (or (not (ctl/any-layout-immediate-child? objects shape))
                 (ctl/position-absolute? shape)))

    (let [parent (get objects (:parent-id shape))

          parent-value (dm/get-in parent [:selrect coord])

          [selrect _ _]
          (-> (:points shape)
              (gsh/transform-points (gsh/shape->center parent) (:transform-inverse parent (gmt/matrix)))
              (gsh/calculate-geometry))

          shape-value (get selrect coord)]
      (- shape-value parent-value))))

(defmethod get-value :left
  [_ shape objects _]
  (get-shape-position shape objects :x))

(defmethod get-value :top
  [_ shape objects _]
  (get-shape-position shape objects :y))

(defmethod get-value :flex
  [_ shape objects _]
  (let [parent (cfh/get-parent objects (:id shape))]
    (when (and (ctl/flex-layout-immediate-child? objects shape)
               (or (and (contains? #{:row :row-reverse} (:layout-flex-dir parent))
                        (= :fill (:layout-item-h-sizing shape)))
                   (and (contains? #{:column :column-reverse} (:layout-flex-dir parent))
                        (= :fill (:layout-item-v-sizing shape)))))
      1)))

(defn get-shape-size
  [shape objects type]
  (let [parent (cfh/get-parent objects (:id shape))
        sizing (if (= type :width)
                 (:layout-item-h-sizing shape)
                 (:layout-item-v-sizing shape))]
    (cond
      (and (ctl/flex-layout-immediate-child? objects shape)
           (or (and (= type :height)
                    (contains? #{:row :row-reverse} (:layout-flex-dir parent))
                    (= :fill (:layout-item-v-sizing shape)))
               (and (= type :width)
                    (contains? #{:column :column-reverse} (:layout-flex-dir parent))
                    (= :fill (:layout-item-h-sizing shape)))))
      :fill

      (and (ctl/flex-layout-immediate-child? objects shape) (= sizing :fill))
      nil

      (or (and (ctl/any-layout? shape) (= sizing :auto) (not (cgc/svg-markup? shape)))
          (and (ctl/grid-layout-immediate-child? objects shape) (= sizing :fill)))
      sizing

      (some? (:selrect shape))
      (-> shape :selrect type)

      (some? (get shape type))
      (get shape type))))

(defmethod get-value :width
  [_ shape objects options]
  (let [root? (contains? (:root-shapes options) (:id shape))]
    (if (and root? (ctl/any-layout? shape))
      :fill
      ;; Don't set fixed width for auto-width text shapes
      (when-not (and (cfh/text-shape? shape) (= (:grow-type shape) :auto-width))
        (get-shape-size shape objects :width)))))

(defmethod get-value :height
  [_ shape objects options]
  (let [root? (contains? (:root-shapes options) (:id shape))]
    (when-not (and root? (ctl/any-layout? shape))
      ;; Don't set fixed height for auto-height text shapes
      (when-not (and (cfh/text-shape? shape) (= (:grow-type shape) :auto-height))
        (get-shape-size shape objects :height)))))

(defmethod get-value :flex-grow
  [_ shape _ options]
  (let [root? (contains? (:root-shapes options) (:id shape))]
    (when (and root? (ctl/any-layout? shape))
      1)))

(defmethod get-value :transform
  [_ shape objects _]
  (if (cgc/svg-markup? shape)
    (let [parent (get objects (:parent-id shape))
          transform
          (:transform-inverse parent (gmt/matrix))

          transform-str (when-not (gmt/unit? transform) (fmt/format-matrix transform))]

      (if (cgc/has-wrapper? objects shape)
        (dm/str "translate(-50%, -50%) " (d/nilv transform-str ""))
        transform-str))

    (let [parent (get objects (:parent-id shape))

          transform
          (gmt/multiply (:transform shape (gmt/matrix))
                        (:transform-inverse parent (gmt/matrix)))

          transform-str (when-not (gmt/unit? transform) (fmt/format-matrix transform))]

      (if (cgc/has-wrapper? objects shape)
        (dm/str "translate(-50%, -50%) " (d/nilv transform-str ""))
        transform-str))))

(defmethod get-value :background
  [_ {:keys [fills] :as shape} _ _]
  (let [single-fill? (= (count fills) 1)]
    (when (and (not (cgc/svg-markup? shape)) (not (cfh/group-shape? shape)) single-fill?)
      (fill->color (first fills)))))

(defn get-stroke-data
  [stroke]
  (let [width (:stroke-width stroke)
        style (:stroke-style stroke)
        color {:color (:stroke-color stroke)
               :opacity (:stroke-opacity stroke)
               :gradient (:stroke-color-gradient stroke)}]

    (when (and (some? stroke) (not= :none (:stroke-style stroke)))
      {:color color
       :style style
       :width width})))

(defmethod get-value :border
  [_ shape _ _]
  (when-not (cgc/svg-markup? shape)
    (get-stroke-data (first (:strokes shape)))))

(defmethod get-value :border-radius
  [_ {:keys [rx r1 r2 r3 r4] :as shape} _ _]
  (cond
    (cfh/circle-shape? shape)
    "50%"

    (some? rx)
    [rx]

    (every? some? [r1 r2 r3 r4])
    [r1 r2 r3 r4]))

(defmethod get-value :box-shadow
  [_ shape _ _]
  (when-not (cgc/svg-markup? shape)
    (:shadow shape)))

(defmethod get-value :filter
  [_ shape _ _]
  (when-not (cgc/svg-markup? shape)
    (get-in shape [:blur :value])))

(defmethod get-value :display
  [_ shape _ _]
  (cond
    (:hidden shape) "none"
    (ctl/flex-layout? shape) "flex"
    (ctl/grid-layout? shape) "grid"))

(defmethod get-value :opacity
  [_ shape _ _]
  (when (< (:opacity shape) 1)
    (:opacity shape)))

(defmethod get-value :overflow
  [_ shape _ _]
  (when (and (cfh/frame-shape? shape)
             (not (cgc/svg-markup? shape))
             (not (:show-content shape)))
    "hidden"))

(defmethod get-value :flex-direction
  [_ shape _ _]
  (:layout-flex-dir shape))

(defmethod get-value :align-items
  [_ shape _ _]
  (:layout-align-items shape))

(defmethod get-value :align-content
  [_ shape _ _]
  (:layout-align-content shape))

(defmethod get-value :justify-items
  [_ shape _ _]
  (:layout-justify-items shape))

(defmethod get-value :justify-content
  [_ shape _ _]
  (:layout-justify-content shape))

(defmethod get-value :flex-wrap
  [_ shape _ _]
  (:layout-wrap-type shape))

(defmethod get-value :gap
  [_ shape _ _]
  (let [[g1 g2] (ctl/gaps shape)]
    (when (and (= g1 g2) (or (not= g1 0) (not= g2 0)))
      [g1])))

(defmethod get-value :row-gap
  [_ shape _ _]
  (let [[g1 g2] (ctl/gaps shape)]
    (when (and (not= g1 g2) (not= g1 0)) [g1])))

(defmethod get-value :column-gap
  [_ shape _ _]
  (let [[g1 g2] (ctl/gaps shape)]
    (when (and (not= g1 g2) (not= g2 0)) [g2])))

(defmethod get-value :padding
  [_ {:keys [layout-padding]} _ _]
  (when (some? layout-padding)
    (let [default-padding {:p1 0 :p2 0 :p3 0 :p4 0}
          {:keys [p1 p2 p3 p4]} (merge default-padding layout-padding)]
      (when (or (not= p1 0) (not= p2 0) (not= p3 0) (not= p4 0))
        [p1 p2 p3 p4]))))

(defmethod get-value :grid-template-rows
  [_ shape _ _]
  (:layout-grid-rows shape))

(defmethod get-value :grid-template-columns
  [_ shape _ _]
  (:layout-grid-columns shape))

(defn area-cell?
  [{:keys [position area-name]}]
  (and (= position :area) (d/not-empty? area-name)))

(defmethod get-value :grid-template-areas
  [_ shape _ _]
  (when (and (ctl/grid-layout? shape)
             (some area-cell? (vals (:layout-grid-cells shape))))
    (let [result
          (->> (d/enumerate (:layout-grid-rows shape))
               (map
                (fn [[row _]]
                  (dm/str
                   "\""
                   (->> (d/enumerate (:layout-grid-columns shape))
                        (map (fn [[column _]]
                               (let [cell (ctl/get-cell-by-position shape (inc row) (inc column))]
                                 (str/replace (:area-name cell ".") " " "-"))))
                        (str/join " "))
                   "\"")))
               (str/join "\n"))]
      result)))

(defn get-grid-coord
  [shape objects prop span-prop]
  (when (and (ctl/grid-layout-immediate-child? objects shape)
             (not (ctl/position-absolute? shape)))
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))]
      (when (and
             (not (and (= (:position cell) :area) (d/not-empty? (:area-name cell))))
             (or (= (:position cell) :manual)
                 (> (:row-span cell) 1)
                 (> (:column-span cell) 1)))
        (if (> (get cell span-prop) 1)
          (dm/str (get cell prop) " / " (+ (get cell prop) (get cell span-prop)))
          (get cell prop))))))

(defmethod get-value :grid-column
  [_ shape objects _]
  (get-grid-coord shape objects :column :column-span))

(defmethod get-value :grid-row
  [_ shape objects _]
  (get-grid-coord shape objects :row :row-span))

(defmethod get-value :grid-area
  [_ shape objects _]
  (when (and (ctl/grid-layout-immediate-child? objects shape)
             (not (ctl/position-absolute? shape)))
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))]
      (when (and (= (:position cell) :area) (d/not-empty? (:area-name cell)))
        (str/replace (:area-name cell) " " "-")))))

(defmethod get-value :flex-shrink
  [_ shape objects _]
  (when (and (ctl/flex-layout-immediate-child? objects shape)

             (not (and (contains? #{:row :reverse-row} (:layout-flex-dir shape))
                       (= :fill (:layout-item-h-sizing shape))))

             (not (and (contains? #{:column :column-row} (:layout-flex-dir shape))
                       (= :fill (:layout-item-v-sizing shape))))

             ;;(not= :fill (:layout-item-h-sizing shape))
             ;;(not= :fill (:layout-item-v-sizing shape))
             (not= :auto (:layout-item-h-sizing shape))
             (not= :auto (:layout-item-v-sizing shape)))
    0))

(defmethod get-value :margin
  [_ {:keys [layout-item-margin] :as shape} objects _]

  (when (ctl/any-layout-immediate-child? objects shape)
    (let [default-margin {:m1 0 :m2 0 :m3 0 :m4 0}
          {:keys [m1 m2 m3 m4]} (merge default-margin layout-item-margin)]
      (when (or (not= m1 0) (not= m2 0) (not= m3 0) (not= m4 0))
        [m1 m2 m3 m4]))))

(defmethod get-value :z-index
  [_ {:keys [layout-item-z-index] :as shape} objects _]
  (cond
    (cfh/root-frame? shape)
    0

    (ctl/any-layout-immediate-child? objects shape)
    layout-item-z-index))

(defmethod get-value :max-height
  [_ shape objects _]
  (cond
    (ctl/any-layout-immediate-child? objects shape)
    (:layout-item-max-h shape)))

(defmethod get-value :min-height
  [_ shape objects _]
  (cond
    (and (ctl/any-layout-immediate-child? objects shape) (some? (:layout-item-min-h shape)))
    (:layout-item-min-h shape)

    (and (ctl/auto-height? shape) (cfh/frame-shape? shape) (not (:show-content shape)))
    (-> shape :selrect :height)))

(defmethod get-value :max-width
  [_ shape objects _]
  (cond
    (ctl/any-layout-immediate-child? objects shape)
    (:layout-item-max-w shape)))

(defmethod get-value :min-width
  [_ shape objects _]
  (cond
    (and (ctl/any-layout-immediate-child? objects shape) (some? (:layout-item-min-w shape)))
    (:layout-item-min-w shape)

    (and (ctl/auto-width? shape) (cfh/frame-shape? shape) (not (:show-content shape)))
    (-> shape :selrect :width)))

(defmethod get-value :align-self
  [_ shape objects _]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-align-self shape)

    (ctl/grid-layout-immediate-child? objects shape)
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))
          align-self (:align-self cell)]
      (when (not= align-self :auto) align-self))))

(defmethod get-value :justify-self
  [_ shape objects _]
  (cond
    (ctl/grid-layout-immediate-child? objects shape)
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))
          justify-self (:justify-self cell)]
      (when (not= justify-self :auto) justify-self))))

(defmethod get-value :grid-auto-flow
  [_ shape _ _]
  (when (and (ctl/grid-layout? shape) (= (:layout-grid-dir shape) :column))
    "column"))

(defmethod get-value :default
  [property shape _ _]
  (get shape property))
