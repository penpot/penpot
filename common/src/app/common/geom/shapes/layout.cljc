;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.layout
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gre]))

;; :layout                 ;; true if active, false if not
;; :layout-flex-dir        ;; :row, :column, :reverse-row, :reverse-column
;; :layout-gap             ;; number could be negative
;; :layout-type            ;; :packed, :space-between, :space-around
;; :layout-wrap-type       ;; :wrap, :no-wrap
;; :layout-padding-type    ;; :simple, :multiple
;; :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative
;; :layout-h-orientation   ;; :top, :center, :bottom
;; :layout-v-orientation   ;; :left, :center, :right

(defn col?
  [{:keys [layout-flex-dir]}]
  (or (= :column layout-flex-dir) (= :reverse-column layout-flex-dir)))

(defn row?
  [{:keys [layout-flex-dir]}]
  (or (= :row layout-flex-dir) (= :reverse-row layout-flex-dir)))

(defn h-start?
  [{:keys [layout-h-orientation]}]
  (= layout-h-orientation :left))

(defn h-center?
  [{:keys [layout-h-orientation]}]
  (= layout-h-orientation :center))

(defn h-end?
  [{:keys [layout-h-orientation]}]
  (= layout-h-orientation :right))

(defn v-start?
  [{:keys [layout-v-orientation]}]
  (= layout-v-orientation :top))

(defn v-center?
  [{:keys [layout-v-orientation]}]
  (= layout-v-orientation :center))

(defn v-end?
  [{:keys [layout-v-orientation]}]
  (= layout-v-orientation :bottom))

(defn add-padding [transformed-rect {:keys [layout-padding-type layout-padding]}]
  (let [{:keys [p1 p2 p3 p4]} layout-padding
        [p1 p2 p3 p4]
        (if (= layout-padding-type :multiple)
          [p1 p2 p3 p4]
          [p1 p1 p1 p1])]

    (-> transformed-rect
        (update :y + p1)
        (update :width - p2 p3)
        (update :x + p3)
        (update :height - p1 p4))))

(defn calc-layout-lines
  [{:keys [layout-gap layout-wrap-type] :as shape} children {:keys [width height] :as layout-bounds}]

  (let [wrap? (= layout-wrap-type :wrap)

        reduce-fn
        (fn [[{:keys [line-width line-height num-children line-fill? child-fill? num-child-fill] :as line-data} result] child]
          (let [child-bounds (-> child :points gre/points->rect)

                cur-child-fill?
                (or (and (col? shape) (= :fill (:layout-h-behavior child)))
                    (and (row? shape) (= :fill (:layout-v-behavior child))))

                cur-line-fill?
                (or (and (row? shape) (= :fill (:layout-h-behavior child)))
                    (and (col? shape) (= :fill (:layout-v-behavior child))))

                ;; TODO LAYOUT: ADD MINWIDTH/HEIGHT
                next-width   (if (or (and (col? shape) cur-child-fill?)
                                     (and (row? shape) cur-line-fill?))
                               0
                               (-> child-bounds :width))

                next-height  (if (or (and (row? shape) cur-child-fill?)
                                     (and (col? shape) cur-line-fill?))
                               0
                               (-> child-bounds :height))]

            (if (and (some? line-data)
                     (or (not wrap?)
                         (and (col? shape) (<= (+ line-width next-width (* layout-gap num-children)) width))
                         (and (row? shape) (<= (+ line-height next-height (* layout-gap num-children)) height))))

              ;; When :fill we add min width (0 by default)
              [{:line-width     (if (col? shape) (+ line-width next-width) (max line-width next-width))
                :line-height    (if (row? shape) (+ line-height next-height) (max line-height next-height))
                :num-children   (inc num-children)
                :child-fill?    (or cur-child-fill? child-fill?)
                :line-fill?     (or cur-line-fill? line-fill?)
                :num-child-fill (cond-> num-child-fill cur-child-fill? inc)}
               result]

              [{:line-width     next-width
                :line-height    next-height
                :num-children   1
                :child-fill?    cur-child-fill?
                :line-fill?     cur-line-fill?
                :num-child-fill (if cur-child-fill? 1 0)}
               (cond-> result (some? line-data) (conj line-data))])))

        [line-data layout-lines] (reduce reduce-fn [nil []] children)]

    (cond-> layout-lines (some? line-data) (conj line-data))))

(defn calc-layout-lines-position
  [{:keys [layout-gap layout-type] :as shape} {:keys [x y width height]} layout-lines]

  (letfn [(get-base-line
            [total-width total-height]

            (let [base-x
                  (cond
                    (and (row? shape) (h-center? shape))
                    (+ x (/ (- width total-width) 2))

                    (and (row? shape) (h-end? shape))
                    (+ x width (- total-width))

                    :else x)

                  base-y
                  (cond
                    (and (col? shape) (v-center? shape))
                    (+ y (/ (- height total-height) 2))

                    (and (col? shape) (v-end? shape))
                    (+ y height (- total-height))

                    :else y)]

              [base-x base-y]))

          (get-start-line
            [{:keys [line-width line-height num-children child-fill?]} base-x base-y]

            (let [children-gap (* layout-gap (dec num-children))

                  line-width  (if (and (col? shape) child-fill?) (- width (* layout-gap num-children)) line-width)
                  line-height (if (and (row? shape) child-fill?) (- height (* layout-gap num-children)) line-height)

                  start-x
                  (cond
                    ;;(and (col? shape) child-fill?)
                    ;;;; TODO LAYOUT: Start has to take into account max-width
                    ;;x

                    (or (and (col? shape) (= :space-between layout-type))
                        (and (col? shape) (= :space-around layout-type)))
                    x

                    (and (col? shape) (h-center? shape))
                    (- (+ x (/ width 2)) (/ (+ line-width children-gap) 2))

                    (and (col? shape) (h-end? shape))
                    (- (+ x width) (+ line-width children-gap))

                    (and (row? shape) (h-center? shape))
                    (+ base-x (/ line-width 2))

                    (and (row? shape) (h-end? shape))
                    (+ base-x line-width)

                    (row? shape)
                    base-x

                    :else
                    x)

                  start-y
                  (cond
                    ;;(and (row? shape) child-fill?)
                    ;;;; TODO LAYOUT: Start has to take into account max-width
                    ;;y
                    
                    (or (and (row? shape) (= :space-between layout-type))
                        (and (row? shape) (= :space-around layout-type)))
                    y

                    (and (row? shape) (v-center? shape))
                    (- (+ y (/ height 2)) (/ (+ line-height children-gap) 2))

                    (and (row? shape) (v-end? shape))
                    (- (+ y height) (+ line-height children-gap))

                    (and (col? shape) (v-center? shape))
                    (+ base-y (/ line-height 2))

                    (and (col? shape) (v-end? shape))
                    (+ base-y line-height)

                    (col? shape)
                    base-y

                    :else
                    y)]

              [start-x start-y]))

          (get-next-line
            [{:keys [line-width line-height]} base-x base-y]
            (let [next-x (if (col? shape) base-x (+ base-x line-width layout-gap))
                  next-y (if (row? shape) base-y (+ base-y line-height layout-gap))]
              [next-x next-y]))

          (add-lines [[total-width total-height] {:keys [line-width line-height]}]
            [(+ total-width line-width)
             (+ total-height line-height)])

          (add-starts [[result base-x base-y] layout-line]
            (let [[start-x start-y] (get-start-line layout-line base-x base-y)
                  [next-x next-y]   (get-next-line layout-line base-x base-y)]
              [(conj result
                     (assoc layout-line
                            :start-x start-x
                            :start-y start-y))
               next-x
               next-y]))]

    (let [[total-width total-height] (->> layout-lines (reduce add-lines [0 0]))

          total-width (+ total-width (* layout-gap (dec (count layout-lines))))
          total-height (+ total-height (* layout-gap (dec (count layout-lines))))

          vertical-fill-space (- height total-height)
          horizontal-fill-space (- width total-width)
          num-line-fill (count (->> layout-lines (filter :line-fill?)))

          layout-lines
          (->> layout-lines
               (mapv #(cond-> %
                        (and (col? shape) (:line-fill? %))
                        (update :line-height + (/ vertical-fill-space num-line-fill))

                        (and (row? shape) (:line-fill? %))
                        (update :line-width + (/ horizontal-fill-space num-line-fill)))))

          total-height (if (and (col? shape) (> num-line-fill 0)) height total-height)
          total-width (if (and (row? shape) (> num-line-fill 0)) width total-width)

          [base-x base-y]
          (get-base-line total-width total-height)

          [layout-lines _ _ _ _]
          (reduce add-starts [[] base-x base-y] layout-lines)]
      layout-lines)))

(defn calc-layout-line-data
  [{:keys [layout-type layout-gap] :as shape}
   {:keys [width height] :as layout-bounds}
   {:keys [num-children line-width line-height] :as line-data}]

  (let [layout-gap
        (cond
          (= :packed layout-type)
          layout-gap

          (= :space-around layout-type)
          0

          (and (col? shape) (= :space-between layout-type))
          (/ (- width line-width) (dec num-children))

          (and (row? shape) (= :space-between layout-type))
          (/ (- height line-height) (dec num-children)))

        margin-x
        (if (and (col? shape) (= :space-around layout-type))
          (/ (- width line-width) (inc num-children) )
          0)

        margin-y
        (if (and (row? shape) (= :space-around layout-type))
          (/ (- height line-height) (inc num-children))
          0)]

    (assoc line-data
           :layout-bounds layout-bounds
           :layout-gap layout-gap
           :margin-x margin-x
           :margin-y margin-y)))


(defn calc-layout-data
  "Digest the layout data to pass it to the constrains"
  [{:keys [layout-flex-dir] :as shape} children layout-bounds]

  (let [reverse? (or (= :reverse-row layout-flex-dir) (= :reverse-column layout-flex-dir))
        layout-bounds (-> layout-bounds (add-padding shape))
        children (cond->> children reverse? reverse)
        layout-lines
        (->> (calc-layout-lines shape children layout-bounds)
             (calc-layout-lines-position shape layout-bounds)
             (map (partial calc-layout-line-data shape layout-bounds)))]

    {:layout-lines layout-lines
     :reverse? reverse?}))

(defn next-p
  "Calculates the position for the current shape given the layout-data context"
  [shape
   {:keys [width height]}
   {:keys [start-x start-y layout-gap margin-x margin-y] :as layout-data}]

  (let [pos-x
        (cond
          (and (row? shape) (h-center? shape))
          (- start-x (/ width 2))

          (and (row? shape) (h-end? shape))
          (- start-x width)

          :else
          start-x)

        pos-y
        (cond
          (and (col? shape) (v-center? shape))
          (- start-y (/ height 2))

          (and (col? shape) (v-end? shape))
          (- start-y height)

          :else
          start-y)


        pos-x (cond-> pos-x (some? margin-x) (+ margin-x))
        pos-y (cond-> pos-y (some? margin-y) (+ margin-y))

        corner-p (gpt/point pos-x pos-y)

        next-x
        (if (col? shape)
          (+ start-x width layout-gap)
          start-x)

        next-y
        (if (row? shape)
          (+ start-y height layout-gap)
          start-y)

        next-x (cond-> next-x (some? margin-x) (+ margin-x))
        next-y (cond-> next-y (some? margin-y) (+ margin-y))


        layout-data
        (assoc layout-data :start-x next-x :start-y next-y)]
    [corner-p layout-data]))

(defn calc-fill-width-data
  [child-bounds
   {:keys [layout-gap] :as parent}
   {:keys [layout-h-behavior] :as child}
   {:keys [num-children line-width layout-bounds line-fill? child-fill?] :as layout-data}]

  (cond
    (and (col? parent) (= :fill layout-h-behavior) child-fill?)
    (let [fill-space (- (:width layout-bounds) line-width (* layout-gap num-children))
          fill-width (/ fill-space (:num-child-fill layout-data))
          fill-scale (/ fill-width (:width child-bounds))]
      {:bounds {:width fill-width}
       :modifiers [{:type :resize
                    :origin (gpt/point child-bounds)
                    :vector (gpt/point fill-scale 1)}]})

    (and (row? parent) (= :fill layout-h-behavior) line-fill?)
    (let [fill-scale (/ line-width (:width child-bounds))]
      {:bounds {:width line-width}
       :modifiers [{:type :resize
                    :origin (gpt/point child-bounds)
                    :vector (gpt/point fill-scale 1)}]})
    ))

(defn calc-fill-height-data
  [child-bounds
   {:keys [layout-gap] :as parent}
   {:keys [layout-v-behavior] :as child}
   {:keys [num-children line-height layout-bounds line-fill? child-fill?] :as layout-data}]

  (cond
    (and (row? parent) (= :fill layout-v-behavior) child-fill?)
    (let [fill-space (- (:height layout-bounds) line-height (* layout-gap num-children))
          fill-height (/ fill-space (:num-child-fill layout-data))
          fill-scale (/ fill-height (:height child-bounds))]
      {:bounds {:height fill-height}
       :modifiers [{:type :resize
                    :origin (gpt/point child-bounds)
                    :vector (gpt/point 1 fill-scale)}]})

    (and (col? parent) (= :fill layout-v-behavior) line-fill?)
    (let [fill-scale (/ line-height (:height child-bounds))]
      {:bounds {:height line-height}
       :modifiers [{:type :resize
                    :origin (gpt/point child-bounds)
                    :vector (gpt/point 1 fill-scale)}]})
    ))

(defn calc-layout-modifiers
  "Calculates the modifiers for the layout"
  [parent transform child layout-data]
  (let [child-bounds    (-> child :points gre/points->selrect)

        fill-width  (calc-fill-width-data child-bounds parent child layout-data)
        fill-height (calc-fill-height-data child-bounds parent child layout-data)

        child-bounds (cond-> child-bounds
                       fill-width (merge (:bounds fill-width))
                       fill-height (merge (:bounds fill-height)))

        [corner-p layout-data] (next-p parent child-bounds layout-data)

        delta-p
        (-> corner-p
            (gpt/subtract (gpt/point child-bounds))
            (cond-> (some? transform) (gpt/transform transform)))

        modifiers
        (-> []
            (cond-> fill-width (d/concat-vec (:modifiers fill-width)))
            (cond-> fill-height (d/concat-vec (:modifiers fill-height)))
            (conj {:type :move :vector delta-p}))]

    [modifiers layout-data]))

