;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.lines
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gst]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]))

(def conjv (fnil conj []))

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

        calculate-line-data
        (fn [[{:keys [line-min-width line-min-height
                      line-max-width line-max-height
                      num-children
                      children-data] :as line-data} result] child]

          (let [child-bounds     (gst/parent-coords-points child shape)
                child-width      (gpo/width-points child-bounds)
                child-height     (gpo/height-points child-bounds)
                child-min-width  (ctl/child-min-width child)
                child-min-height (ctl/child-min-height child)
                child-max-width  (ctl/child-max-width child)
                child-max-height (ctl/child-max-height child)

                [child-margin-top child-margin-right child-margin-bottom child-margin-left]
                (ctl/child-margins child)

                child-margin-width (+ child-margin-left child-margin-right)
                child-margin-height (+ child-margin-top child-margin-bottom)

                fill-width?  (ctl/fill-width? child)
                fill-height? (ctl/fill-height? child)

                ;; We need this info later to calculate the child resizes when fill
                child-data {:id (:id child)
                            :child-min-width (if fill-width? child-min-width child-width)
                            :child-min-height (if fill-height? child-min-height child-height)
                            :child-max-width (if fill-width? child-max-width child-width)
                            :child-max-height (if fill-height? child-max-height child-height)}

                next-min-width   (+ child-margin-width (if fill-width? child-min-width child-width))
                next-min-height  (+ child-margin-height (if fill-height? child-min-height child-height))
                next-max-width   (+ child-margin-width (if fill-width? child-max-width child-width))
                next-max-height  (+ child-margin-height (if fill-height? child-max-height child-height))

                next-line-min-width  (+ line-min-width  next-min-width  (* layout-gap-row num-children))
                next-line-min-height (+ line-min-height next-min-height (* layout-gap-col num-children))]
            (if (and (some? line-data)
                     (or (not wrap?)
                         (and row? (<= next-line-min-width layout-width))
                         (and col? (<= next-line-min-height layout-height))))

              [{:line-min-width  (if row? (+ line-min-width next-min-width) (max line-min-width next-min-width))
                :line-max-width  (if row? (+ line-max-width next-max-width) (max line-max-width next-max-width))
                :line-min-height (if col? (+ line-min-height next-min-height) (max line-min-height next-min-height))
                :line-max-height (if col? (+ line-max-height next-max-height) (max line-max-height next-max-height))
                :num-children    (inc num-children)
                :children-data   (conjv children-data child-data)}
               result]

              [{:line-min-width  next-min-width
                :line-min-height next-min-height
                :line-max-width  next-max-width
                :line-max-height next-max-height
                :num-children    1
                :children-data   [child-data]}
               (cond-> result (some? line-data) (conj line-data))])))

        [line-data layout-lines] (reduce calculate-line-data [nil []] children)]

    (cond-> layout-lines (some? line-data) (conj line-data))))

(defn get-base-line
  "Main axis line"
  [parent layout-bounds total-width total-height num-lines]

  (let [layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)
        row?          (ctl/row? parent)
        col?          (ctl/col? parent)
        hv            (partial gpo/start-hv layout-bounds)
        vv            (partial gpo/start-vv layout-bounds)

        end?     (ctl/content-end? parent)
        center?  (ctl/content-center? parent)
        around?  (ctl/content-around? parent)

        ;; Adjust the totals so it takes into account the gaps
        [layout-gap-row layout-gap-col] (ctl/gaps parent)
        lines-gap-row (* (dec num-lines) layout-gap-row)
        lines-gap-col (* (dec num-lines) layout-gap-col)

        free-width-gap (- layout-width total-width lines-gap-row)
        free-height-gap (- layout-height total-height lines-gap-col)
        free-width (- layout-width total-width)
        free-height (- layout-height total-height)]

    (cond-> (gpo/origin layout-bounds)
      row?
      (cond-> center?
        (gpt/add (vv (/ free-height-gap 2)))

        end?
        (gpt/add (vv free-height-gap))

        around?
        (gpt/add (vv (/ free-height (inc num-lines)))))

      col?
      (cond-> center?
        (gpt/add (hv (/ free-width-gap 2)))

        end?
        (gpt/add (hv free-width-gap))

        around?
        (gpt/add (hv (/ free-width (inc num-lines))))))))

(defn get-next-line
  [parent layout-bounds {:keys [line-width line-height]} base-p total-width total-height num-lines]

  (let [layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)
        row? (ctl/row? parent)
        col? (ctl/col? parent)

        [layout-gap-row layout-gap-col] (ctl/gaps parent)

        hv   #(gpo/start-hv layout-bounds %)
        vv   #(gpo/start-vv layout-bounds %)

        stretch? (ctl/content-stretch? parent)
        between? (ctl/content-between? parent)
        around?  (ctl/content-around? parent)

        free-width  (- layout-width total-width)
        free-height (- layout-height total-height)

        line-gap-row (cond
                       stretch?
                       (/ free-width num-lines)

                       between?
                       (/ free-width (dec num-lines))

                       around?
                       (/ free-width (inc num-lines))

                       :else
                       layout-gap-row)

        line-gap-col (cond
                       stretch?
                       (/ free-height num-lines)

                       between?
                       (/ free-height (dec num-lines))

                       around?
                       (/ free-height (inc num-lines))

                       :else
                       layout-gap-col)]

    (cond-> base-p
      row?
      (gpt/add (vv (+ line-height (max layout-gap-col line-gap-col))))

      col?
      (gpt/add (hv (+ line-width (max layout-gap-row line-gap-row)))))))

(defn get-start-line
  "Cross axis line. It's position is fixed along the different lines"
  [parent layout-bounds {:keys [line-width line-height num-children]} base-p total-width total-height num-lines]

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
        content-stretch? (ctl/content-stretch? parent)

        hv   #(gpo/start-hv layout-bounds %)
        vv   #(gpo/start-vv layout-bounds %)

        children-gap-width (* layout-gap-row (dec num-children))
        children-gap-height (* layout-gap-col (dec num-children))

        line-height
        (if (and row? content-stretch?)
          (+ line-height (/ (- layout-height total-height) num-lines))
          line-height)

        line-width
        (if (and col? content-stretch?)
          (+ line-width (/ (- layout-width total-width) num-lines))
          line-width)

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

(defn add-space-to-items
  ;; Distributes the remainder space between the lines
  [prop prop-min prop-max to-share items]
  (let [num-items (->> items (remove #(mth/close? (get % prop) (get % prop-max))) count)
        per-line-target (/ to-share num-items)]
    (loop [current (first items)
           items   (rest items)
           remainder to-share
           result []]
      (if (nil? current)
        [result remainder]
        (let [cur-val (or (get current prop) (get current prop-min) 0)
              max-val (get current prop-max)
              cur-inc (if (> (+ cur-val per-line-target) max-val)
                        (- max-val cur-val)
                        per-line-target)
              current (assoc current prop (+ cur-val cur-inc))
              remainder (- remainder cur-inc)
              result (conj result current)]
          (recur (first items) (rest items) remainder result))))))

(defn distribute-space
  [prop prop-min prop-max min-value bound-value items]
  (loop [to-share (- bound-value min-value)
         items    items]
    (if (<= to-share 0)
      items
      (let [[items remainder] (add-space-to-items prop prop-min prop-max to-share items)]
        (assert (<= remainder to-share) (str remainder ">" to-share))
        (if (or (<= remainder 0) (= remainder to-share))
          items
          (recur remainder items))))))

(defn add-lines-positions
  [parent layout-bounds layout-lines]

  (let [row? (ctl/row? parent)
        col? (ctl/col? parent)

        [layout-gap-row layout-gap-col] (ctl/gaps parent)

        layout-width   (gpo/width-points layout-bounds)
        layout-height  (gpo/height-points layout-bounds)]

    (letfn [(add-lines [[total-width total-height]
                        {:keys [line-width line-height]}]
              [(+ total-width line-width) (+ total-height line-height)])

            (add-ranges [[total-min-width total-min-height total-max-width total-max-height]
                        {:keys [line-min-width line-min-height line-max-width line-max-height]}]
              [(+ total-min-width line-min-width)
               (+ total-min-height line-min-height)
               (+ total-max-width line-max-width)
               (+ total-max-height line-max-height)])

            (add-starts [total-width total-height num-lines [result base-p] layout-line]
              (let [start-p (get-start-line parent layout-bounds layout-line base-p total-width total-height num-lines)
                    next-p  (get-next-line  parent layout-bounds layout-line base-p total-width total-height num-lines)]

                [(conj result
                       (assoc layout-line :start-p start-p))
                 next-p]))]

      (let [[total-min-width total-min-height total-max-width total-max-height]
            (->> layout-lines (reduce add-ranges [0 0 0 0]))

            get-layout-width (fn [{:keys [num-children]}] (- layout-width (* layout-gap-row (dec num-children))))
            get-layout-height (fn [{:keys [num-children]}] (- layout-height (* layout-gap-col (dec num-children))))

            num-lines (count layout-lines)

            ;; Distributes the space between the layout lines based on its max/min constraints
            layout-lines
            (cond->> layout-lines
              row?
              (map #(assoc % :line-width (max (:line-min-width %) (min (get-layout-width %) (:line-max-width %)))))

              col?
              (map #(assoc % :line-height (max (:line-min-height %) (min (get-layout-height %) (:line-max-height %)))))

              (and row? (>= total-min-height layout-height))
              (map #(assoc % :line-height (:line-min-height %)))

              (and row? (<= total-max-height layout-height))
              (map #(assoc % :line-height (:line-max-height %)))

              (and row? (< total-min-height layout-height total-max-height))
              (distribute-space :line-height :line-min-height :line-max-height total-min-height (- layout-height (* (dec num-lines) layout-gap-col)))

              (and col? (>= total-min-width layout-width))
              (map #(assoc % :line-width (:line-min-width %)))

              (and col? (<= total-max-width layout-width))
              (map #(assoc % :line-width (:line-max-width %)))

              (and col? (< total-min-width layout-width total-max-width))
              (distribute-space :line-width :line-min-width :line-max-width total-min-width (- layout-width (* (dec num-lines) layout-gap-row))))

            [total-width total-height] (->> layout-lines (reduce add-lines [0 0]))

            base-p (get-base-line parent layout-bounds total-width total-height num-lines)]

        (first (reduce (partial add-starts total-width total-height num-lines) [[] base-p] layout-lines))))))

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

(defn add-children-resizes
  [shape {:keys [line-min-width line-width line-min-height line-height] :as line-data}]

  (let [row? (ctl/row? shape)
        col? (ctl/col? shape)]
    (update line-data :children-data
            (fn [children-data]
              (cond->> children-data
                row?
                (distribute-space :child-width :child-min-width :child-max-width line-min-width line-width)

                col?
                (distribute-space :child-height :child-min-height :child-max-height line-min-height line-height)

                :always
                (d/index-by :id))))))

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
             (mapv (partial add-line-spacing shape layout-bounds))
             (mapv (partial add-children-resizes shape)))]

    {:layout-lines layout-lines
     :reverse? reverse?}))
