;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.layout-new
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gre]
   [app.common.geom.shapes.transforms :as gst]
   ))

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

;; FUNCTIONS TO WORK WITH POINTS SQUARES

(defn origin
  [points]
  (nth points 0))

(defn start-hv
  "Horizontal vector from the origin with a magnitude `val`"
  [[p0 p1 p2 p3] val]
  (-> (gpt/to-vec p0 p1)
      (gpt/unit)
      (gpt/scale val)))

(defn end-hv
  "Horizontal vector from the oposite to the origin in the x axis with a magnitude `val`"
  [[p0 p1 p2 p3] val]
  (-> (gpt/to-vec p1 p0)
      (gpt/unit)
      (gpt/scale val)))

(defn start-vv
  "Vertical vector from the oposite to the origin in the x axis with a magnitude `val`"
  [[p0 p1 p2 p3] val]
  (-> (gpt/to-vec p0 p3)
      (gpt/unit)
      (gpt/scale val)))

(defn end-vv
  "Vertical vector from the oposite to the origin in the x axis with a magnitude `val`"
  [[p0 p1 p2 p3] val]
  (-> (gpt/to-vec p3 p0)
      (gpt/unit)
      (gpt/scale val)))

;;(defn start-hp
;;  [[p0 _ _ _ :as points] val]
;;  (gpt/add p0 (start-hv points val)))
;;
;;(defn end-hp
;;  "Horizontal Vector from the oposite to the origin in the x axis with a magnitude `val`"
;;  [[_ p1 _ _ :as points] val]
;;  (gpt/add p1 (end-hv points val)))
;;
;;(defn start-vp
;;  "Vertical Vector from the oposite to the origin in the x axis with a magnitude `val`"
;;  [[p0 _ _ _ :as points] val]
;;  (gpt/add p0 (start-vv points val)))
;;
;;(defn end-vp
;;  "Vertical Vector from the oposite to the origin in the x axis with a magnitude `val`"
;;  [[_ _ p3 _ :as points] val]
;;  (gpt/add p3 (end-vv points val)))

(defn width-points
  [[p0 p1 p2 p3]]
  (gpt/length (gpt/to-vec p0 p1)))

(defn height-points
  [[p0 p1 p2 p3]]
  (gpt/length (gpt/to-vec p0 p3)))

(defn pad-points
  [[p0 p1 p2 p3 :as points] pad-top pad-right pad-bottom pad-left]
  (let [top-v    (start-vv points pad-top)
        right-v  (end-hv points pad-right)
        bottom-v (end-vv points pad-bottom)
        left-v   (start-hv points pad-left)]

    [(-> p0 (gpt/add left-v)  (gpt/add top-v))
     (-> p1 (gpt/add right-v) (gpt/add top-v))
     (-> p2 (gpt/add right-v) (gpt/add bottom-v))
     (-> p3 (gpt/add left-v)  (gpt/add bottom-v))]))

;;;;


(defn calc-layout-lines
  [{:keys [layout-gap layout-wrap-type] :as parent} children layout-bounds]

  (let [wrap? (= layout-wrap-type :wrap)
        layout-width (width-points layout-bounds)
        layout-height (height-points layout-bounds)

        reduce-fn
        (fn [[{:keys [line-width line-height num-children line-fill? child-fill? num-child-fill] :as line-data} result] child]
          (let [child-bounds (gst/parent-coords-points child parent)
                child-width  (width-points child-bounds)
                child-height (height-points child-bounds)

                col? (col? parent)
                row? (row? parent)

                cur-child-fill?
                (or (and col? (= :fill (:layout-h-behavior child)))
                    (and row? (= :fill (:layout-v-behavior child))))

                cur-line-fill?
                (or (and row? (= :fill (:layout-h-behavior child)))
                    (and col? (= :fill (:layout-v-behavior child))))

                ;; TODO LAYOUT: ADD MINWIDTH/HEIGHT
                next-width   (if (or (and col? cur-child-fill?)
                                     (and row? cur-line-fill?))
                               0
                               child-width)

                next-height  (if (or (and row? cur-child-fill?)
                                     (and col? cur-line-fill?))
                               0
                               child-height)

                next-total-width (+ line-width next-width (* layout-gap num-children))
                next-total-height (+ line-height next-height (* layout-gap num-children))]

            (if (and (some? line-data)
                     (or (not wrap?)
                         (and col? (<= next-total-width layout-width))
                         (and row? (<= next-total-height layout-height))))

              ;; When :fill we add min width (0 by default)
              [{:line-width     (if col? (+ line-width next-width) (max line-width next-width))
                :line-height    (if row? (+ line-height next-height) (max line-height next-height))
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
  [{:keys [layout-gap layout-type] :as parent} layout-bounds layout-lines]

  (let [layout-width   (width-points layout-bounds)
        layout-height  (height-points layout-bounds)
        row?           (row? parent)
        col?           (col? parent)
        h-center?      (h-center? parent)
        h-end?         (h-end? parent)
        v-center?      (v-center? parent)
        v-end?         (v-end? parent)
        space-between? (= :space-between layout-type)
        space-around?  (= :space-around layout-type)]
    
    (letfn [(get-base-line
              [total-width total-height]

              (cond-> (origin layout-bounds)
                (and row? h-center?)
                (gpt/add (start-hv layout-bounds (/ (- layout-width total-width) 2)))
                
                (and row? h-end?)
                (gpt/add (start-hv layout-bounds (- layout-width total-width)))
                
                (and col? v-center?)
                (gpt/add (start-vv layout-bounds (/ (- layout-height total-height) 2)))
                
                (and col? v-end?)
                (gpt/add (start-vv layout-bounds (- layout-height total-height)))
                ))

            (get-start-line
              [{:keys [line-width line-height num-children child-fill?]} base-p]

              (let [children-gap (* layout-gap (dec num-children))

                    ;;line-width  (if (and col? child-fill?)
                    ;;              (- layout-width (* layout-gap num-children))
                    ;;              line-width)
                    ;;
                    ;;line-height (if (and row? child-fill?)
                    ;;              (- layout-height (* layout-gap num-children))
                    ;;              line-height)


                    start-p
                    (cond-> base-p
                      ;; X AXIS
                      (and col? h-center? (not space-between?) (not space-around?))
                      (-> (gpt/add (start-hv layout-bounds (/ layout-width 2)))
                          (gpt/subtract (start-hv layout-bounds (/ (+ line-width children-gap) 2))))

                      (and col? h-end? (not space-between?) (not space-around?))
                      (-> (gpt/add (start-hv layout-bounds layout-width))
                          (gpt/subtract (start-hv layout-bounds (+ line-width children-gap))))

                      (and row? h-center? (not space-between?) (not space-around?))
                      (gpt/add (start-hv layout-bounds (/ line-width 2)))

                      (and row? h-end? (not space-between?) (not space-around?))
                      (gpt/add (start-hv layout-bounds line-width))

                      ;; Y AXIS
                      (and row? v-center? (not space-between?) (not space-around?))
                      (-> (gpt/add (start-vv layout-bounds (/ layout-height 2)))
                          (gpt/subtract (start-vv layout-bounds (/ (+ line-height children-gap) 2))))

                      (and row? v-end? (not space-between?) (not space-around?))
                      (-> (gpt/add (start-vv layout-bounds layout-height))
                          (gpt/subtract (start-vv layout-bounds (+ line-height children-gap))))

                      (and col? v-center? (not space-between?) (not space-around?))
                      (gpt/add (start-vv layout-bounds (/ line-height 2)))

                      (and col? v-end? (not space-between?) (not space-around?))
                      (gpt/add (start-vv layout-bounds line-height))

                      )
                    

                    ;;start-x
                    ;;(cond
                    ;;  ;;(and (col? shape) child-fill?)
                    ;;  ;; TODO LAYOUT: Start has to take into account max-width
                    ;;  ;;x
                    ;;
                    ;;  (or (and col? space-between?) (and col? space-around?))
                    ;;  x
                    ;;
                    ;;  (and col? h-center?)
                    ;;  (- (+ x (/ width 2)) (/ (+ line-width children-gap) 2))
                    ;;
                    ;;  (and col? h-end?)
                    ;;  (- (+ x width) (+ line-width children-gap))
                    ;;
                    ;;  (and row? h-center?)
                    ;;  (+ base-x (/ line-width 2))
                    ;;
                    ;;  (and row? h-end?)
                    ;;  (+ base-x line-width)
                    ;;
                    ;;  row?
                    ;;  base-x
                    ;;
                    ;;  :else
                    ;;  x)

                    ;;start-y
                    ;;(cond
                    ;;  ;; (and (row? shape) child-fill?)
                    ;;  ;; TODO LAYOUT: Start has to take into account max-width
                    ;;  ;; y
                    ;;  
                    ;;  (or (and (row? shape) (= :space-between layout-type))
                    ;;      (and (row? shape) (= :space-around layout-type)))
                    ;;  y
                    ;;
                    ;;  (and (row? shape) (v-center? shape))
                    ;;  (- (+ y (/ height 2)) (/ (+ line-height children-gap) 2))
                    ;;
                    ;;  (and (row? shape) (v-end? shape))
                    ;;  (- (+ y height) (+ line-height children-gap))
                    ;;
                    ;;  (and (col? shape) (v-center? shape))
                    ;;  (+ base-y (/ line-height 2))
                    ;;
                    ;;  (and (col? shape) (v-end? shape))
                    ;;  (+ base-y line-height)
                    ;;
                    ;;  (col? shape)
                    ;;  base-y
                    ;;
                    ;;  :else
                    ;;  y)
                    ]

                start-p))

            (get-next-line
              [{:keys [line-width line-height]} base-p]

              (cond-> base-p
                col?
                (gpt/add (start-hv layout-bounds (+ line-width layout-gap)))

                row?
                (gpt/add (start-vv layout-bounds (+ line-height layout-gap)))
                )

              #_(let [next-x (if col? base-x (+ base-x line-width layout-gap))
                    next-y (if row? base-y (+ base-y line-height layout-gap))]
                [next-x next-y]))

            (add-lines [[total-width total-height] {:keys [line-width line-height]}]
              [(+ total-width line-width)
               (+ total-height line-height)])

            (add-starts [[result base-p] layout-line]
              (let [start-p (get-start-line layout-line base-p)
                    next-p  (get-next-line layout-line base-p)]
                [(conj result
                       (assoc layout-line :start-p start-p))
                 next-p]))]

      (let [[total-width total-height] (->> layout-lines (reduce add-lines [0 0]))

            total-width (+ total-width (* layout-gap (dec (count layout-lines))))
            total-height (+ total-height (* layout-gap (dec (count layout-lines))))

            vertical-fill-space (- layout-height total-height)
            horizontal-fill-space (- layout-width total-width)
            num-line-fill (count (->> layout-lines (filter :line-fill?)))

            layout-lines
            (->> layout-lines
                 (mapv #(cond-> %
                          (and col? (:line-fill? %))
                          (update :line-height + (/ vertical-fill-space num-line-fill))

                          (and row? (:line-fill? %))
                          (update :line-width + (/ horizontal-fill-space num-line-fill)))))

            total-height (if (and col? (> num-line-fill 0)) layout-height total-height)
            total-width (if (and row? (> num-line-fill 0)) layout-width total-width)

            base-p (get-base-line total-width total-height)

            [layout-lines _ _ _ _]
            (reduce add-starts [[] base-p] layout-lines)]
        layout-lines))))

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

(defn next-p
  "Calculates the position for the current shape given the layout-data context"
  [parent
   child-bounds
   {:keys [start-p layout-gap margin-x margin-y] :as layout-data}]

  (let [width     (width-points child-bounds)
        height    (height-points child-bounds)
        
        row?      (row? parent)
        col?      (col? parent)
        h-center? (h-center? parent)
        h-end?    (h-end? parent)
        v-center? (v-center? parent)
        v-end?    (v-end? parent)
        points    (:points parent)

        corner-p
        (cond-> start-p
          (and row? h-center?)
          (gpt/add (start-hv points (- (/ width 2))))

          (and row? h-end?)
          (gpt/add (start-hv points (- width)))

          (and col? v-center?)
          (gpt/add (start-vv points (- (/ height 2))))

          (and col? v-end?)
          (gpt/add (start-vv points (- height)))

          (some? margin-x)
          (gpt/add (start-hv points margin-x))

          (some? margin-y)
          (gpt/add (start-vv points margin-y)))

        next-p
        (cond-> start-p
          col?
          (gpt/add (start-hv points (+ width layout-gap)))

          row?
          (gpt/add (start-vv points (+ height layout-gap)))

          (some? margin-x)
          (gpt/add (start-hv points margin-x))

          (some? margin-y)
          (gpt/add (start-vv points margin-y)))
        
        layout-data
        (assoc layout-data :start-p next-p)]

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

(defn normalize-child-modifiers
  "Apply the modifiers and then normalized them against the parent coordinates"
  [parent child modifiers transformed-parent]

  (let [transformed-child (gst/transform-shape child modifiers)
        child-bb-before (gst/parent-coords-rect child parent)
        child-bb-after  (gst/parent-coords-rect transformed-child transformed-parent)
        scale-x (/ (:width child-bb-before) (:width child-bb-after))
        scale-y (/ (:height child-bb-before) (:height child-bb-after))]
    (-> modifiers
        (update :v2 #(conj %
                           {:type :resize
                            :transform (:transform transformed-parent)
                            :transform-inverse (:transform-inverse transformed-parent)
                            :origin (-> transformed-parent :points (nth 0))
                            :vector (gpt/point scale-x scale-y)})))))

(defn calc-layout-data
  "Digest the layout data to pass it to the constrains"
  [{:keys [layout-dir points layout-padding layout-padding-type] :as parent} children]

  (let [;; Add padding to the bounds
        {pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} layout-padding
        [pad-top pad-right pad-bottom pad-left]
        (if (= layout-padding-type :multiple)
          [pad-top pad-right pad-bottom pad-left]
          [pad-top pad-top pad-top pad-top])
        layout-bounds (-> points (pad-points pad-top pad-right pad-bottom pad-left))

        ;; Reverse
        reverse? (or (= :left layout-dir) (= :bottom layout-dir))
        children (cond->> children reverse? reverse)

        ;; Creates the layout lines information
        layout-lines
        (->> (calc-layout-lines parent children layout-bounds)
             (calc-layout-lines-position parent layout-bounds)
             (map (partial calc-layout-line-data parent layout-bounds)))]

    {:layout-lines layout-lines
     :reverse? reverse?}))

(defn calc-layout-modifiers
  "Calculates the modifiers for the layout"
  [parent child layout-line]
  (let [child-bounds (gst/parent-coords-points child parent)

        ;;fill-width   (calc-fill-width-data child-bounds parent child layout-line)
        ;;fill-height  (calc-fill-height-data child-bounds parent child layout-line)

        ;;child-bounds (cond-> child-bounds
        ;;               fill-width (merge (:bounds fill-width))
        ;;               fill-height (merge (:bounds fill-height)))
        

        [corner-p layout-line] (next-p parent child-bounds layout-line)

        move-vec (gpt/to-vec (origin child-bounds) corner-p)

        modifiers
        (-> []
            #_(cond-> fill-width (d/concat-vec (:modifiers fill-width)))
            #_(cond-> fill-height (d/concat-vec (:modifiers fill-height)))
            (conj {:type :move :vector move-vec}))]

    [modifiers layout-line]))

