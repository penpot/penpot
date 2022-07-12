;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.layout
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gre]))

;; :layout                 ;; true if active, false if not
;; :layout-dir             ;; :right, :left, :top, :bottom
;; :layout-gap             ;; number could be negative
;; :layout-type            ;; :packed, :space-between, :space-around
;; :layout-wrap-type       ;; :wrap, :no-wrap
;; :layout-padding-type    ;; :simple, :multiple
;; :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative
;; :layout-h-orientation   ;; :top, :center, :bottom
;; :layout-v-orientation   ;; :left, :center, :right

(defn col?
  [{:keys [layout-dir]}]
  (or (= :right layout-dir) (= :left layout-dir)))

(defn row?
  [{:keys [layout-dir]}]
  (or (= :top layout-dir) (= :bottom layout-dir)))

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
        (fn [[{:keys [line-width line-height num-children] :as line-data} result] child]
          (let [child-bounds (-> child :points gre/points->rect)
                next-width   (-> child-bounds :width)
                next-height  (-> child-bounds :height)]

            (if (and (some? line-data)
                     (or (not wrap?)
                         (and (col? shape) (<= (+ line-width next-width (* layout-gap num-children)) width))
                         (and (row? shape) (<= (+ line-height next-height (* layout-gap num-children)) height))))

              [{:line-width   (if (col? shape) (+ line-width next-width) (max line-width next-width))
                :line-height  (if (row? shape) (+ line-height next-height) (max line-height next-height))
                :num-children (inc num-children)}
               result]

              [{:line-width   next-width
                :line-height  next-height
                :num-children 1}
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
            [{:keys [line-width line-height num-children]} base-x base-y]

            (let [children-gap (* layout-gap (dec num-children))

                  start-x
                  (cond
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

    (let [[total-width total-height]
          (->> layout-lines (reduce add-lines [0 0]))

          total-width (+ total-width (* layout-gap (dec (count layout-lines))))
          total-height (+ total-height (* layout-gap (dec (count layout-lines))))

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
           :layout-gap layout-gap
           :margin-x margin-x
           :margin-y margin-y)))


(defn calc-layout-data
  "Digest the layout data to pass it to the constrains"
  [{:keys [layout-dir] :as shape} children layout-bounds]

  (let [reverse? (or (= :left layout-dir) (= :bottom layout-dir))
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

(defn calc-layout-modifiers
  "Calculates the modifiers for the layout"
  [parent transform child layout-data]

  (let [bounds    (-> child :points gre/points->selrect)

        [corner-p layout-data] (next-p parent bounds layout-data)

        delta-p   (-> corner-p
                      (gpt/subtract (gpt/point bounds))
                      (cond-> (some? transform) (gpt/transform transform)))

        modifiers {:displacement-after (gmt/translate-matrix delta-p)}]

    [modifiers layout-data]))
