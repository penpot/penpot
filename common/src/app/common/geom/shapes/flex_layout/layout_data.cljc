;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.layout-data
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes.flex-layout.positions :as flp]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]))

(def conjv (fnil conj []))

;; Setted in app.common.geom.shapes.min-size-layout
;; We do it this way because circular dependencies
(def -child-min-width nil)

(defn child-min-width
  [child child-bounds bounds objects]
  (-child-min-width child child-bounds bounds objects))

(def -child-min-height nil)

(defn child-min-height
  [child child-bounds bounds objects]
  (-child-min-height child child-bounds bounds objects))

(defn layout-bounds
  [parent shape-bounds]
  (let [[pad-top pad-right pad-bottom pad-left] (ctl/paddings parent)]
    (gpo/pad-points shape-bounds pad-top pad-right pad-bottom pad-left)))

(defn init-layout-lines
  "Calculates the lines basic data and accumulated values. The positions will be calculated in a different operation"
  [shape children layout-bounds bounds objects auto?]

  (let [col?           (ctl/col? shape)
        row?           (ctl/row? shape)
        space-around?  (ctl/space-around? shape)
        space-evenly?  (ctl/space-evenly? shape)

        auto-width?    (or (ctl/auto-width? shape) auto?)
        auto-height?   (or (ctl/auto-height? shape) auto?)

        wrap? (and (ctl/wrap? shape) (or col? (not auto-width?)) (or row? (not auto-height?)))

        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)]

    (loop [line-data    nil
           result       (transient [])
           children     (seq children)]

      (if (not children)
        (persistent! (cond-> result (some? line-data) (conj! line-data)))

        (let [[child-bounds child] (first children)
              {:keys [line-min-width line-min-height
                      line-max-width line-max-height
                      num-children
                      children-data]} line-data

              child-width      (gpo/width-points child-bounds)
              child-height     (gpo/height-points child-bounds)
              child-max-width  (ctl/child-max-width child)
              child-max-height (ctl/child-max-height child)

              child-margin-width (ctl/child-width-margin child)
              child-margin-height (ctl/child-height-margin child)

              fill-width?  (ctl/fill-width? child)
              fill-height? (ctl/fill-height? child)

              ;; We need this info later to calculate the child resizes when fill
              child-data {:id (:id child)
                          :child-min-width (child-min-width child child-bounds bounds objects)
                          :child-min-height (child-min-height child child-bounds bounds objects)
                          :child-max-width (if fill-width? child-max-width child-width)
                          :child-max-height (if fill-height? child-max-height child-height)}

              next-min-width   (+ child-margin-width (:child-min-width child-data))
              next-min-height  (+ child-margin-height (:child-min-height child-data))
              next-max-width   (+ child-margin-width (:child-max-width child-data))
              next-max-height  (+ child-margin-height (:child-max-height child-data))

              total-gap-col
              (cond
                space-evenly?
                (* layout-gap-col (+ num-children 2))

                space-around?
                (* layout-gap-col (+ num-children 1))

                :else
                (* layout-gap-col num-children))

              total-gap-row
              (cond
                space-evenly?
                (* layout-gap-row (+ num-children 2))

                space-around?
                (* layout-gap-row (+ num-children 1))

                :else
                (* layout-gap-row num-children))

              next-line-min-width  (+ line-min-width  next-min-width  total-gap-col)
              next-line-min-height (+ line-min-height next-min-height total-gap-row)]

          (if (and (some? line-data)
                   (or (not wrap?)
                       (and row? (or (< next-line-min-width layout-width)
                                     (mth/close? next-line-min-width layout-width 0.5)))
                       (and col? (or (< next-line-min-height layout-height)
                                     (mth/close? next-line-min-height layout-height 0.5)))))

            (recur {:line-min-width  (if row? (+ line-min-width next-min-width) (max line-min-width next-min-width))
                    :line-max-width  (if row? (+ line-max-width next-max-width) (max line-max-width next-max-width))
                    :line-min-height (if col? (+ line-min-height next-min-height) (max line-min-height next-min-height))
                    :line-max-height (if col? (+ line-max-height next-max-height) (max line-max-height next-max-height))
                    :num-children    (inc num-children)
                    :children-data   (conjv children-data child-data)}
                   result
                   (next children))

            (recur {:line-min-width  next-min-width
                    :line-min-height next-min-height
                    :line-max-width  next-max-width
                    :line-max-height next-max-height
                    :num-children    1
                    :children-data   [child-data]}
                   (cond-> result (some? line-data) (conj! line-data))
                   (next children))))))))

(defn add-space-to-items
  ;; Distributes the remainder space between the lines
  [prop prop-min prop-max to-share items]
  (let [num-items (->> items (remove #(mth/close? (get % prop) (get % prop-max))) count)
        per-line-target (/ to-share num-items)]

    (loop [items (seq items)
           remainder to-share
           result (transient [])]

      (if (not items)
        [(persistent! result) remainder]

        (let [current (first items)
              cur-val (or (get current prop) (get current prop-min) 0)
              max-val (get current prop-max)
              cur-inc (if (> (+ cur-val per-line-target) max-val)
                        (- max-val cur-val)
                        per-line-target)
              current (assoc current prop (+ cur-val cur-inc))
              remainder (- remainder cur-inc)]
          (recur (next items) remainder (conj! result current)))))))

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
  [parent layout-bounds auto? layout-lines]
  (let [row?          (ctl/row? parent)
        col?          (ctl/col? parent)
        auto-width?   (or (ctl/auto-width? parent) auto?)
        auto-height?  (or (ctl/auto-height? parent) auto?)
        space-evenly? (ctl/space-evenly? parent)
        space-around? (ctl/space-around? parent)

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
                [(-> result (conj! (assoc layout-line :start-p start-p)))
                 next-p]))

            (get-layout-width [{:keys [num-children]}]
              (let [num-gap (cond space-evenly? (inc num-children)
                                  space-around? num-children
                                  :else         (dec num-children))]
                (- layout-width (* layout-gap-col num-gap))))

            (get-layout-height [{:keys [num-children]}]
              (let [num-gap (cond space-evenly? (inc num-children)
                                  space-around? num-children
                                  :else         (dec num-children))]
                (- layout-height (* layout-gap-row num-gap))))]

      (let [[total-min-width total-min-height total-max-width total-max-height]
            (->> layout-lines (reduce add-ranges [0 0 0 0]))

            num-lines (count layout-lines)

            ;; When align-items is stretch we need to adjust the main axis size to grow for the full content
            stretch-width-fix
            (if (and col? (ctl/content-stretch? parent) (not auto-width?))
              (/ (- layout-width (* layout-gap-col (dec num-lines)) total-max-width) num-lines)
              0)

            stretch-height-fix
            (if (and row? (ctl/content-stretch? parent) (not auto-height?))
              (/ (- layout-height (* layout-gap-row (dec num-lines)) total-max-height) num-lines)
              0)

            rest-layout-height (- layout-height (* (dec num-lines) layout-gap-row))
            rest-layout-width  (- layout-width (* (dec num-lines) layout-gap-col))

            ;; Distributes the space between the layout lines based on its max/min constraints

            layout-lines
            (cond->> layout-lines
              row?
              (map #(assoc % :line-width
                           (if auto-width?
                             (:line-min-width %)
                             (max (:line-min-width %) (min (get-layout-width %) (:line-max-width %))))))

              col?
              (map #(assoc % :line-height
                           (if auto-height?
                             (:line-min-height %)
                             (max (:line-min-height %) (min (get-layout-height %) (:line-max-height %))))))

              (and row? (or (>= total-min-height rest-layout-height) auto-height?))
              (map #(assoc % :line-height (:line-min-height %)))

              (and row? (<= total-max-height rest-layout-height) (not auto-height?))
              (map #(assoc % :line-height (+ (:line-max-height %) stretch-height-fix)))

              (and col? (or (>= total-min-width rest-layout-width) auto-width?))
              (map #(assoc % :line-width (:line-min-width %)))

              (and col? (<= total-max-width rest-layout-width) (not auto-width?))
              (map #(assoc % :line-width (+ (:line-max-width %) stretch-width-fix))))

            layout-lines
            (cond->> layout-lines
              (and row? (< total-min-height rest-layout-height total-max-height) (not auto-height?))
              (distribute-space :line-height :line-min-height :line-max-height total-min-height rest-layout-height)

              (and col? (< total-min-width rest-layout-width total-max-width) (not auto-width?))
              (distribute-space :line-width :line-min-width :line-max-width total-min-width rest-layout-width))

            ;; Add information to limit the growth of width: 100% shapes to the bounds of the layout
            layout-lines
            (cond
              row?
              (->> layout-lines
                   (reduce
                    (fn [[result rest-layout-height] {:keys [line-height] :as line}]
                      [(conj! result (assoc line :to-bound-height rest-layout-height))
                       (- rest-layout-height line-height layout-gap-row)])
                    [(transient []) layout-height])
                   (first)
                   (persistent!))

              col?
              (->> layout-lines
                   (reduce
                    (fn [[result rest-layout-width] {:keys [line-width] :as line}]
                      [(conj! result (assoc line :to-bound-width rest-layout-width))
                       (- rest-layout-width line-width layout-gap-col)])
                    [(transient []) layout-width])
                   (first)
                   (persistent!))

              :else
              layout-lines)

            [total-width total-height] (->> layout-lines (reduce add-lines [0 0]))

            base-p (flp/get-base-line parent layout-bounds total-width total-height num-lines)]

        (->> layout-lines
             (reduce (partial add-starts total-width total-height num-lines) [(transient []) base-p])
             (first)
             (persistent!))))))

(defn add-line-spacing
  "Calculates the baseline for a flex layout"
  [shape layout-bounds auto? {:keys [num-children line-width line-height] :as line-data}]

  (let [width (gpo/width-points layout-bounds)
        height (gpo/height-points layout-bounds)

        row?           (ctl/row? shape)
        col?           (ctl/col? shape)
        auto-height?   (or (ctl/auto-height? shape) auto?)
        auto-width?    (or (ctl/auto-width? shape) auto?)
        space-between? (ctl/space-between? shape)
        space-evenly?  (ctl/space-evenly? shape)
        space-around?  (ctl/space-around? shape)

        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        margin-x
        (cond (and row? space-evenly? (not auto-width?))
              (max layout-gap-col (/ (- width line-width) (inc num-children)))

              (and row? space-around? (not auto-width?))
              (/ (max layout-gap-col (/ (- width line-width) num-children)) 2)

              (and row? (or space-evenly? space-around?) auto-width?)
              layout-gap-col

              :else
              0)

        margin-y
        (cond (and col? space-evenly? (not auto-height?))
              (max layout-gap-row (/ (- height line-height) (inc num-children)))

              (and col? space-around? (not auto-height?))
              (/ (max layout-gap-row (/ (- height line-height) num-children)) 2)

              (and col? (or space-evenly? space-around?) auto-height?)
              layout-gap-row

              :else
              0)

        layout-gap-col
        (cond (and row? space-evenly?)
              0

              (and row? space-around? auto-width?)
              0

              (and row? space-around?)
              (/ (max layout-gap-col (/ (- width line-width) num-children)) 2)

              (and row? space-between? (not auto-width?))
              (max layout-gap-col (/ (- width line-width) (dec num-children)))

              :else
              layout-gap-col)

        layout-gap-row
        (cond (and col? space-evenly?)
              0

              (and col? space-evenly? auto-height?)
              0

              (and col? space-around?)
              (/ (max layout-gap-row (/ (- height line-height) num-children)) 2)

              (and col? space-between? (not auto-height?))
              (max layout-gap-row (/ (- height line-height) (dec num-children)))

              :else
              layout-gap-row)]
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
  ([shape shape-bounds children bounds objects]
   (calc-layout-data shape shape-bounds children bounds objects false))

  ([shape shape-bounds children bounds objects auto?]

   (let [layout-bounds (layout-bounds shape shape-bounds)
         reverse?      (ctl/reverse? shape)
         children      (cond->> children (not reverse?) reverse)

         ignore-child?
         (fn [[_ child]]
           (ctl/position-absolute? child))

         ;; Don't take into account absolute children
         children      (->> children (remove ignore-child?))

         ;; Creates the layout lines information
         layout-lines
         (->> (init-layout-lines shape children layout-bounds bounds objects auto?)
              (add-lines-positions shape layout-bounds auto?)
              (into [] (comp (map (partial add-line-spacing shape layout-bounds auto?))
                             (map (partial add-children-resizes shape)))))]

     {:layout-lines layout-lines
      :layout-bounds layout-bounds
      :reverse? reverse?})))
