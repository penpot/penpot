;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.lines
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gst]
   [app.common.types.shape.layout :as ctl]))

(defn layout-bounds
  [{:keys [layout-padding layout-padding-type] :as shape}]
  (let [;; Add padding to the bounds
        {pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} layout-padding
        [pad-top pad-right pad-bottom pad-left]
        (if (= layout-padding-type :multiple)
          [pad-top pad-right pad-bottom pad-left]
          [pad-top pad-top pad-top pad-top])

        ;; Normalize the points to remove flips
        ;; TODO LAYOUT: Need function to normalize the points
        points (gst/parent-coords-points shape shape)]

    (gpo/pad-points points pad-top pad-right pad-bottom pad-left)))

(defn init-layout-lines
  "Calculates the lines basic data and accumulated values. The positions will be calculated in a different operation"
  [shape children layout-bounds]

  (let [wrap? (ctl/wrap? shape)
        col?  (ctl/col? shape)
        row?  (ctl/row? shape)

        [layout-gap-row layout-gap-col] (ctl/gaps shape)
        layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)

        reduce-fn
        (fn [[{:keys [line-width line-height num-children line-fill? child-fill? num-child-fill] :as line-data} result] child]
          (let [child-bounds (gst/parent-coords-points child shape)
                child-width  (gpo/width-points child-bounds)
                child-height (gpo/height-points child-bounds)
                child-min-width (ctl/child-min-width child)
                child-min-height (ctl/child-min-height child)

                fill-width? (ctl/fill-width? child)
                fill-height? (ctl/fill-height? child)

                cur-child-fill? (or (and row? fill-width?) (and col? fill-height?))
                cur-line-fill?  (or (and col? fill-width?) (and row? fill-height?))

                next-width   (if fill-width? child-min-width child-width)
                next-height  (if fill-height? child-min-height child-height)

                next-line-width  (+ line-width  next-width  (* layout-gap-row (dec num-children)))
                next-line-height (+ line-height next-height (* layout-gap-col (dec num-children)))]

            (if (and (some? line-data)
                     (or (not wrap?)
                         (and row? (<= next-line-width layout-width))
                         (and col? (<= next-line-height layout-height))))

              [{:line-width  (if row? (+ line-width next-width) (max line-width next-width))
                :line-height (if col? (+ line-height next-height) (max line-height next-height))
                :num-children    (inc num-children)
                :child-fill?     (or cur-child-fill? child-fill?)
                :line-fill?      (or cur-line-fill? line-fill?)
                :num-child-fill  (cond-> num-child-fill cur-child-fill? inc)}
               result]

              [{:line-width  next-width
                :line-height next-height
                :num-children    1
                :child-fill?     cur-child-fill?
                :line-fill?      cur-line-fill?
                :num-child-fill  (if cur-child-fill? 1 0)}
               (cond-> result (some? line-data) (conj line-data))])))

        [line-data layout-lines] (reduce reduce-fn [nil []] children)]

    (cond-> layout-lines (some? line-data) (conj line-data))))

(defn get-base-line
  [parent layout-bounds total-width total-height]

  (let [layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)
        row?          (ctl/row? parent)
        col?          (ctl/col? parent)
        h-center?     (ctl/h-center? parent)
        h-end?        (ctl/h-end? parent)
        v-center?     (ctl/v-center? parent)
        v-end?        (ctl/v-end? parent)
        hv            (partial gpo/start-hv layout-bounds)
        vv            (partial gpo/start-vv layout-bounds)]

    (cond-> (gpo/origin layout-bounds)
      (and col? h-center?)
      (gpt/add (hv (/ (- layout-width total-width) 2)))

      (and col? h-end?)
      (gpt/add (hv (- layout-width total-width)))

      (and row? v-center?)
      (gpt/add (vv (/ (- layout-height total-height) 2)))

      (and row? v-end?)
      (gpt/add (vv (- layout-height total-height))))))

(defn get-next-line
  [parent layout-bounds {:keys [line-width line-height]} base-p]

  (let [row? (ctl/row? parent)
        col? (ctl/col? parent)

        [layout-gap-row layout-gap-col] (ctl/gaps parent)

        hv   #(gpo/start-hv layout-bounds %)
        vv   #(gpo/start-vv layout-bounds %)]

    (cond-> base-p
      col?
      (gpt/add (hv (+ line-width layout-gap-row)))

      row?
      (gpt/add (vv (+ line-height layout-gap-col))))))

(defn get-start-line
  [parent layout-bounds {:keys [line-width line-height num-children child-fill? ]} base-p]

  (let [layout-width   (gpo/width-points layout-bounds)
        layout-height  (gpo/height-points layout-bounds)
        [layout-gap-row layout-gap-col] (ctl/gaps parent)

        row?           (ctl/row? parent)
        col?           (ctl/col? parent)
        space-between? (ctl/space-between? parent)
        space-around?  (ctl/space-around? parent)
        h-center?      (ctl/h-center? parent)
        h-end?         (ctl/h-end? parent)
        v-center?      (ctl/v-center? parent)
        v-end?         (ctl/v-end? parent)

        hv   #(gpo/start-hv layout-bounds %)
        vv   #(gpo/start-vv layout-bounds %)

        children-gap-width (* layout-gap-row (dec num-children))
        children-gap-height (* layout-gap-col (dec num-children))

        line-width  (if (and row? child-fill?)
                      (- layout-width (* layout-gap-row (dec num-children)))
                      line-width)

        line-height (if (and col? child-fill?)
                      (- layout-height (* layout-gap-col (dec num-children)))
                      line-height)

        start-p
        (cond-> base-p
          ;; X AXIS
          (and row? h-center? (not space-around?) (not space-between?))
          (-> (gpt/add (hv (/ layout-width 2)))
              (gpt/subtract (hv (/ (+ line-width children-gap-width) 2))))

          (and row? h-end? (not space-around?) (not space-between?))
          (-> (gpt/add (hv layout-width))
              (gpt/subtract (hv (+ line-width children-gap-width))))

          (and col? h-center?)
          (gpt/add (hv (/ line-width 2)))

          (and col? h-end?)
          (gpt/add (hv line-width))

          ;; Y AXIS
          (and col? v-center? (not space-around?) (not space-between?))
          (-> (gpt/add (vv (/ layout-height 2)))
              (gpt/subtract (vv (/ (+ line-height children-gap-height) 2))))

          (and col? v-end? (not space-around?) (not space-between?))
          (-> (gpt/add (vv layout-height))
              (gpt/subtract (vv (+ line-height children-gap-height))))

          (and row? v-center?)
          (gpt/add (vv (/ line-height 2)))

          (and row? v-end?)
          (gpt/add (vv line-height)))]

    start-p))


(defn add-lines-positions
  [parent layout-bounds layout-lines]

  (let [layout-width   (gpo/width-points layout-bounds)
        layout-height  (gpo/height-points layout-bounds)
        [layout-gap-row layout-gap-col] (ctl/gaps parent)

        row?           (ctl/row? parent)
        col?           (ctl/col? parent)]

    (letfn [(add-lines [[total-width total-height] {:keys [line-width line-height]}]
              [(+ total-width line-width)
               (+ total-height line-height)])

            (add-starts [[result base-p] layout-line]
              (let [start-p (get-start-line parent layout-bounds layout-line base-p)
                    next-p  (get-next-line  parent layout-bounds layout-line base-p)]

                [(conj result
                       (assoc layout-line :start-p start-p))
                 next-p]))]

      (let [[total-width total-height] (->> layout-lines (reduce add-lines [0 0]))

            total-width (+ total-width (* layout-gap-row (dec (count layout-lines))))
            total-height (+ total-height (* layout-gap-col (dec (count layout-lines))))

            vertical-fill-space   (- layout-height total-height)
            horizontal-fill-space (- layout-width total-width)
            
            num-line-fill (count (->> layout-lines (filter :line-fill?)))

            layout-lines
            (->> layout-lines
                 (mapv #(cond-> %
                          (and row? (:line-fill? %))
                          (update :line-height + (/ vertical-fill-space num-line-fill))

                          (and col? (:line-fill? %))
                          (update :line-width + (/ horizontal-fill-space num-line-fill)))))

            total-width (if (and col? (> num-line-fill 0)) layout-width total-width)
            total-height (if (and row? (> num-line-fill 0)) layout-height total-height)

            base-p (get-base-line parent layout-bounds total-width total-height)]

        (first (reduce add-starts [[] base-p] layout-lines))))))

(defn add-line-spacing
  "Calculates the baseline for a flex layout"
  [shape layout-bounds {:keys [num-children line-width line-height] :as line-data}]

  (let [width (gpo/width-points layout-bounds)
        height (gpo/height-points layout-bounds)

        row?           (ctl/row? shape)
        col?           (ctl/col? shape)
        space-between? (ctl/space-between? shape)
        space-around?  (ctl/space-around? shape)

        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        layout-gap-row
        (cond (and row? space-around?)
              0

              (and row? space-between?)
              (/ (- width line-width) (dec num-children))

              :else
              layout-gap-row)

        layout-gap-col
        (cond (and col? space-around?)
              0

              (and col? space-between?)
              (/ (- height line-height) (dec num-children))

              :else
              layout-gap-col)

        margin-x
        (if (and row? space-around?)
          (/ (- width line-width) (inc num-children))
          0)

        margin-y
        (if (and col? space-around?)
          (/ (- height line-height) (inc num-children))
          0)]
    (assoc line-data
           :layout-bounds layout-bounds
           :layout-gap-row layout-gap-row
           :layout-gap-col layout-gap-col
           :margin-x margin-x
           :margin-y margin-y)))

(defn calc-layout-data
  "Digest the layout data to pass it to the constrains"
  [shape children]

  (let [layout-bounds (layout-bounds shape)
        reverse?      (ctl/reverse? shape)
        children      (cond->> children reverse? reverse)

        ;; Creates the layout lines information
        layout-lines
        (->> (init-layout-lines shape children layout-bounds)
             (add-lines-positions shape layout-bounds)
             (mapv (partial add-line-spacing shape layout-bounds)))]

    {:layout-lines layout-lines
     :reverse? reverse?}))
