;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.lines
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes.flex-layout.positions :as flp]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]))

(def conjv (fnil conj []))

(defn layout-bounds
  [{:keys [layout-padding] :as shape} shape-bounds]
  (let [;; Add padding to the bounds
        {pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} layout-padding]
    (gpo/pad-points shape-bounds pad-top pad-right pad-bottom pad-left)))

(defn init-layout-lines
  "Calculates the lines basic data and accumulated values. The positions will be calculated in a different operation"
  [shape children layout-bounds]

  (let [col?  (ctl/col? shape)
        row?  (ctl/row? shape)

        wrap? (and (ctl/wrap? shape)
                   (or col? (not (ctl/auto-width? shape)))
                   (or row? (not (ctl/auto-height? shape))))

        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)]

    (loop [line-data    nil
           result       []
           children     (seq children)]

      (if (empty? children)
        (cond-> result (some? line-data) (conj line-data))

        (let [[child-bounds child] (first children)
              {:keys [line-min-width line-min-height
                      line-max-width line-max-height
                      num-children
                      children-data]} line-data

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

            (recur {:line-min-width  (if row? (+ line-min-width next-min-width) (max line-min-width next-min-width))
                    :line-max-width  (if row? (+ line-max-width next-max-width) (max line-max-width next-max-width))
                    :line-min-height (if col? (+ line-min-height next-min-height) (max line-min-height next-min-height))
                    :line-max-height (if col? (+ line-max-height next-max-height) (max line-max-height next-max-height))
                    :num-children    (inc num-children)
                    :children-data   (conjv children-data child-data)}
                   result
                   (rest children))

            (recur {:line-min-width  next-min-width
                    :line-min-height next-min-height
                    :line-max-width  next-max-width
                    :line-max-height next-max-height
                    :num-children    1
                    :children-data   [child-data]}
                   (cond-> result (some? line-data) (conj line-data))
                   (rest children))))))))

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
              (let [start-p (flp/get-start-line parent layout-bounds layout-line base-p total-width total-height num-lines)
                    next-p  (flp/get-next-line  parent layout-bounds layout-line base-p total-width total-height num-lines)]

                [(conj result (assoc layout-line :start-p start-p))
                 next-p]))]

      (let [[total-min-width total-min-height total-max-width total-max-height]
            (->> layout-lines (reduce add-ranges [0 0 0 0]))

            get-layout-width (fn [{:keys [num-children]}] (- layout-width (* layout-gap-row (dec num-children))))
            get-layout-height (fn [{:keys [num-children]}] (- layout-height (* layout-gap-col (dec num-children))))

            num-lines (count layout-lines)

            ;; When align-items is stretch we need to adjust the main axis size to grow for the full content
            stretch-width-fix
            (if (and col? (ctl/content-stretch? parent))
              (/ (- layout-width (* layout-gap-row (dec num-lines)) total-max-width) num-lines)
              0)

            stretch-height-fix
            (if (and row? (ctl/content-stretch? parent))
              (/ (- layout-height (* layout-gap-col (dec num-lines)) total-max-height) num-lines)
              0)

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
              (map #(assoc % :line-height (+ (:line-max-height %) stretch-height-fix)))

              (and row? (< total-min-height layout-height total-max-height))
              (distribute-space :line-height :line-min-height :line-max-height total-min-height (- layout-height (* (dec num-lines) layout-gap-col)))

              (and col? (>= total-min-width layout-width))
              (map #(assoc % :line-width (:line-min-width %)))

              (and col? (<= total-max-width layout-width))
              (map #(assoc % :line-width (+ (:line-max-width %) stretch-width-fix)))

              (and col? (< total-min-width layout-width total-max-width))
              (distribute-space :line-width :line-min-width :line-max-width total-min-width (- layout-width (* (dec num-lines) layout-gap-row))))

            [total-width total-height] (->> layout-lines (reduce add-lines [0 0]))

            base-p (flp/get-base-line parent layout-bounds total-width total-height num-lines)]

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
                (map #(assoc % :child-width (:child-min-width %)))

                col?
                (map #(assoc % :child-height (:child-min-height %)))

                row?
                (distribute-space :child-width :child-min-width :child-max-width line-min-width line-width)

                col?
                (distribute-space :child-height :child-min-height :child-max-height line-min-height line-height)

                :always
                (d/index-by :id))))))

(defn calc-layout-data
  "Digest the layout data to pass it to the constrains"
  [shape children shape-bounds]

  (let [layout-bounds (layout-bounds shape shape-bounds)
        reverse?      (ctl/reverse? shape)
        children      (cond->> children (not reverse?) reverse)

        ;; Creates the layout lines information
        layout-lines
        (->> (init-layout-lines shape children layout-bounds)
             (add-lines-positions shape layout-bounds)
             (into [] (comp (map (partial add-line-spacing shape layout-bounds))
                            (map (partial add-children-resizes shape)))))]

    {:layout-lines layout-lines
     :layout-bounds layout-bounds
     :reverse? reverse?}))

