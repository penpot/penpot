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

(defn area-cell?
  [{:keys [position area-name]}]
  (and (= position :area) (d/not-empty? area-name)))

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

;; SHAPE VALUES

(defn- get-position
  [shape objects]
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

(defn- get-left-position
  [shape objects]
  (get-shape-position shape objects :x))

(defn- get-top-position
  [shape objects]
  (get-shape-position shape objects :y))

(defn- get-flex
  [shape objects]
  (let [parent (cfh/get-parent objects (:id shape))]
    (when (and (ctl/flex-layout-immediate-child? objects shape)
               (or (and (contains? #{:row :row-reverse} (:layout-flex-dir parent))
                        (= :fill (:layout-item-h-sizing shape)))
                   (and (contains? #{:column :column-reverse} (:layout-flex-dir parent))
                        (= :fill (:layout-item-v-sizing shape)))))
      1)))

(defn- get-width
  [shape objects options]
  (let [root? (contains? (:root-shapes options) (:id shape))]
    (if (and root? (ctl/any-layout? shape))
      :fill
      ;; Don't set fixed width for auto-width text shapes
      (when-not (and (cfh/text-shape? shape) (= (:grow-type shape) :auto-width))
        (get-shape-size shape objects :width)))))

(defn- get-height
  [shape objects options]
  (let [root? (contains? (:root-shapes options) (:id shape))]
    (when-not (and root? (ctl/any-layout? shape))
      ;; Don't set fixed height for auto-height text shapes
      (when-not (and (cfh/text-shape? shape) (= (:grow-type shape) :auto-height))
        (get-shape-size shape objects :height)))))

(defn- get-flex-grow
  [shape options]
  (let [root? (contains? (:root-shapes options) (:id shape))]
    (when (and root? (ctl/any-layout? shape))
      1)))

(defn- get-transform
  [shape objects]
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

(defn- get-background
  [{:keys [fills] :as shape}]
  (let [single-fill? (= (count fills) 1)]
    (when (and (not (cgc/svg-markup? shape)) (not (cfh/group-shape? shape)) single-fill?)
      (fill->color (first fills)))))

(defn- get-border
  [shape]
  (when-not (cgc/svg-markup? shape)
    (get-stroke-data (first (:strokes shape)))))

(defn- get-border-radius
  [{:keys [rx r1 r2 r3 r4] :as shape}]
  (cond
    (cfh/circle-shape? shape)
    "50%"

    (some? rx)
    [rx]

    (every? some? [r1 r2 r3 r4])
    [r1 r2 r3 r4]))

(defn- get-border-start-start-radius
  [{:keys [_ r1 _ _ _] :as shape}]
  (when (and r1 (not= r1 0))
    [r1]))

(defn- get-border-start-end-radius
  [{:keys [_ _ r2 _ _] :as shape}]
  (when (and r2 (not= r2 0))
    [r2]))

(defn- get-border-end-start-radius
  [{:keys [_ _ _ r3 _] :as shape}]
  (when (and r3 (not= r3 0))
    [r3]))

(defn- get-border-end-end-radius
  [{:keys [_ _ _ _ r4] :as shape}]
  (when (and r4 (not= r4 0))
    [r4]))

(defn- get-border-style
  [stroke]
  (when-not (cgc/svg-markup? stroke)
    (get-stroke-data stroke)))

(defn- get-border-width
  [stroke]
  (when-not (cgc/svg-markup? stroke)
    (get-stroke-data stroke)))

(defn- get-border-color
  [stroke]
  (when-not (cgc/svg-markup? stroke)
    (get-stroke-data stroke)))

(defn- get-box-shadow
  [shape]
  (when-not (cgc/svg-markup? shape)
    (:shadow shape)))

(defn- get-filter
  [shape]
  (when-not (cgc/svg-markup? shape)
    (get-in shape [:blur :value])))

(defn- get-display
  [shape]
  (cond
    (:hidden shape) "none"
    (ctl/flex-layout? shape) "flex"
    (ctl/grid-layout? shape) "grid"))

(defn- get-opacity
  [shape]
  (when (< (:opacity shape) 1)
    (:opacity shape)))

(defn- get-overflow
  [shape]
  (when (and (cfh/frame-shape? shape)
             (not (cgc/svg-markup? shape))
             (not (:show-content shape)))
    "hidden"))

(defn- get-flex-direction
  [shape]
  (:layout-flex-dir shape))

(defn- get-align-items
  [shape]
  (:layout-align-items shape))

(defn- get-align-content
  [shape]
  (:layout-align-content shape))

(defn- get-justify-items
  [shape]
  (:layout-justify-items shape))

(defn- get-justify-content
  [shape]
  (:layout-justify-content shape))

(defn- get-flex-wrap
  [shape]
  (:layout-wrap-type shape))

(defn- get-gap
  [shape]
  (let [[g1 g2] (ctl/gaps shape)]
    (when (and (= g1 g2) (or (not= g1 0) (not= g2 0)))
      [g1])))

(defn- get-row-gap
  [shape]
  (let [[g1 g2] (ctl/gaps shape)]
    (when (and (not= g1 g2) (not= g1 0)) [g1])))

(defn- get-column-gap
  [shape]
  (let [[g1 g2] (ctl/gaps shape)]
    (when (and (not= g1 g2) (not= g2 0)) [g2])))

(defn- get-padding
  [{:keys [layout-padding]}]
  (when (some? layout-padding)
    (let [default-padding {:p1 0 :p2 0 :p3 0 :p4 0}
          {:keys [p1 p2 p3 p4]} (merge default-padding layout-padding)]
      (when (or (not= p1 0) (not= p2 0) (not= p3 0) (not= p4 0))
        [p1 p2 p3 p4]))))

(defn- get-padding-block-start
  [{:keys [layout-padding]}]
  (when (and (:p1 layout-padding) (not= (:p1 layout-padding) 0))
    [(:p1 layout-padding)]))

(defn- get-padding-inline-end
  [{:keys [layout-padding]}]
  (when (and (:p2 layout-padding) (not= (:p2 layout-padding) 0))
    [(:p2 layout-padding)]))

(defn- get-padding-block-end
  [{:keys [layout-padding]}]
  (when (and (:p3 layout-padding) (not= (:p3 layout-padding) 0))
    [(:p3 layout-padding)]))

(defn- get-padding-inline-start
  [{:keys [layout-padding]}]
  (when (and (:p4 layout-padding) (not= (:p4 layout-padding) 0))
    [(:p4 layout-padding)]))

(defn- get-grid-template-rows
  [shape]
  (:layout-grid-rows shape))

(defn- get-grid-template-columns
  [shape]
  (:layout-grid-columns shape))

(defn- get-grid-template-areas
  [shape]
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

(defn- get-grid-column
  [shape objects]
  (get-grid-coord shape objects :column :column-span))

(defn- get-grid-row
  [shape objects]
  (get-grid-coord shape objects :row :row-span))

(defn- get-grid-area
  [shape objects]
  (when (and (ctl/grid-layout-immediate-child? objects shape)
             (not (ctl/position-absolute? shape)))
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))]
      (when (and (= (:position cell) :area) (d/not-empty? (:area-name cell)))
        (str/replace (:area-name cell) " " "-")))))

(defn- get-flex-shrink
  [shape objects]
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

(defn- get-margin
  [{:keys [layout-item-margin] :as shape} objects]

  (when (ctl/any-layout-immediate-child? objects shape)
    (let [default-margin {:m1 0 :m2 0 :m3 0 :m4 0}
          {:keys [m1 m2 m3 m4]} (merge default-margin layout-item-margin)]
      (when (or (not= m1 0) (not= m2 0) (not= m3 0) (not= m4 0))
        [m1 m2 m3 m4]))))

(defn- get-margin-block-start
  [{:keys [layout-item-margin] :as shape} objects]
  (when (and (ctl/any-layout-immediate-child? objects shape) (:m1 layout-item-margin) (not= (:m1 layout-item-margin) 0))
    [(:m1 layout-item-margin)]))

(defn- get-margin-inline-end
  [{:keys [layout-item-margin] :as shape} objects]
  (when (and (ctl/any-layout-immediate-child? objects shape) (:m2 layout-item-margin) (not= (:m2 layout-item-margin) 0))
    [(:m2 layout-item-margin)]))

(defn- get-margin-block-end
  [{:keys [layout-item-margin] :as shape} objects]
  (when (and (ctl/any-layout-immediate-child? objects shape) (:m3 layout-item-margin) (not= (:m3 layout-item-margin) 0))
    [(:m3 layout-item-margin)]))

(defn- get-margin-inline-start
  [{:keys [layout-item-margin] :as shape} objects]
  (when (and (ctl/any-layout-immediate-child? objects shape) (:m4 layout-item-margin) (not= (:m4 layout-item-margin) 0))
    [(:m4 layout-item-margin)]))


(defn- get-z-index
  [{:keys [layout-item-z-index] :as shape} objects]
  (cond
    (cfh/root-frame? shape)
    0

    (ctl/any-layout-immediate-child? objects shape)
    layout-item-z-index))

(defn- get-max-height
  [shape objects]
  (cond
    (ctl/any-layout-immediate-child? objects shape)
    (:layout-item-max-h shape)))

(defn- get-min-height
  [shape objects]
  (cond
    (and (ctl/any-layout-immediate-child? objects shape) (some? (:layout-item-min-h shape)))
    (:layout-item-min-h shape)
    (and (ctl/auto-height? shape) (cfh/frame-shape? shape) (not (:show-content shape)))
    (-> shape :selrect :height)))

(defn- get-max-width
  [shape objects]
  (cond
    (ctl/any-layout-immediate-child? objects shape)
    (:layout-item-max-w shape)))

(defn- get-min-width
  [shape objects]
  (cond
    (and (ctl/any-layout-immediate-child? objects shape) (some? (:layout-item-min-w shape)))
    (:layout-item-min-w shape)

    (and (ctl/auto-width? shape) (cfh/frame-shape? shape) (not (:show-content shape)))
    (-> shape :selrect :width)))

(defn- get-align-self
  [shape objects]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-align-self shape)

    (ctl/grid-layout-immediate-child? objects shape)
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))
          align-self (:align-self cell)]
      (when (not= align-self :auto) align-self))))

(defn- get-justify-self
  [shape objects]
  (cond
    (ctl/grid-layout-immediate-child? objects shape)
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))
          justify-self (:justify-self cell)]
      (when (not= justify-self :auto) justify-self))))

(defn- get-grid-auto-flow
  [shape]
  (when (and (ctl/grid-layout? shape) (= (:layout-grid-dir shape) :column))
    "column"))

(defn get-value
  "Get the value for a given CSS property from a shape"
  [property shape objects options]
  (case property
    ;; Positioning
    :position (get-position shape objects)
    :left (get-left-position  shape objects)
    :top (get-top-position  shape objects)
    :z-index (get-z-index  shape objects)
    :transform (get-transform  shape objects)

    ;; Size
    :width (get-width shape objects options)
    :height (get-height shape objects options)
    (:max-width :max-inline-size) (get-max-width  shape objects)
    (:min-width :min-inline-size) (get-min-width  shape objects)
    (:max-height :max-block-size) (get-max-height  shape objects)
    (:min-height :min-block-size) (get-min-height  shape objects)

    ;; Spacing
    :margin (get-margin shape objects)
    :margin-block-start (get-margin-block-start shape objects)
    :margin-inline-end (get-margin-inline-end shape objects)
    :margin-block-end (get-margin-block-end shape objects)
    :margin-inline-start (get-margin-inline-start shape objects)
    :padding (get-padding shape)
    :padding-block-start (get-padding-block-start shape)
    :padding-inline-end (get-padding-inline-end shape)
    :padding-block-end (get-padding-block-end shape)
    :padding-inline-start (get-padding-inline-start shape)

    ;; Border & Background
    :border (get-border shape)
    :border-style (get-border-style shape)
    :border-width (get-border-width shape)
    :border-color (get-border-color shape)
    :border-radius (get-border-radius shape)
    :border-start-start-radius (get-border-start-start-radius shape)
    :border-start-end-radius (get-border-start-end-radius shape)
    :border-end-start-radius (get-border-end-start-radius shape)
    :border-end-end-radius (get-border-end-end-radius shape)
    :background (get-background shape)

    ;; Visual Effects
    :opacity (get-opacity shape)
    :box-shadow (get-box-shadow shape)
    :filter (get-filter shape)
    :overflow (get-overflow shape)

    ;; Display
    :display (get-display shape)

    ;; Flexbox
    :flex (get-flex shape objects)
    :flex-grow (get-flex-grow shape options)
    :flex-shrink (get-flex-shrink shape objects)
    :flex-direction (get-flex-direction shape)
    :flex-wrap (get-flex-wrap shape)
    :align-items (get-align-items shape)
    :align-content (get-align-content shape)
    :align-self (get-align-self shape objects)
    :justify-content (get-justify-content shape)
    :justify-items (get-justify-items shape)
    :justify-self (get-justify-self shape objects)

    ;; Grid
    :grid-template-rows (get-grid-template-rows shape)
    :grid-template-columns (get-grid-template-columns shape)
    :grid-template-areas (get-grid-template-areas shape)
    :grid-column (get-grid-column shape objects)
    :grid-row (get-grid-row shape objects)
    :grid-area (get-grid-area shape objects)
    :grid-auto-flow (get-grid-auto-flow shape)

    ;; Spacing (Flex/Grid)
    :gap (get-gap shape)
    :row-gap (get-row-gap shape)
    :column-gap (get-column-gap shape)

    ;; Default
    (get shape property)))
