(ns uxbox.shapes
  (:require [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.math :as mth]
            [uxbox.state :as st]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +hierarchy+
  (as-> (make-hierarchy) $
    (derive $ ::rect ::shape)
    (derive $ :builtin/icon ::rect)
    (derive $ :builtin/rect ::rect)
    (derive $ :builtin/line ::shape)
    (derive $ :builtin/circle ::shape)
    (derive $ :builtin/text ::rect)
    (derive $ :builtin/group ::rect)))

(defn shape?
  [type]
  {:pre [(keyword? type)]}
  (isa? +hierarchy+ type ::shape))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dispatch-by-type
  [shape & params]
  (:type shape))

(defmulti move
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti move'
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti resize
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti resize'
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti size
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti rotate
  dispatch-by-type
  :hierarchy #'+hierarchy+)

;; Used for calculate the outer rect that wraps
;; up the whole underlying shape. Mostly used
;; for calculate the shape or shapes selection
;; rectangle.

(defmulti outer-rect'
  dispatch-by-type
  :hierarchy #'+hierarchy+)

;; Used for create the final shape data structure
;; from initial shape data structure and final
;; canvas position.

(defmulti initialize
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti transformation
  dispatch-by-type
  :hierarchy #'+hierarchy+)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Initialize

(defmethod initialize ::shape
  [shape {:keys [x1 y1 x2 y2]}]
  (assoc shape
         :x1 x1
         :y1 y1
         :x2 x2
         :y2 y2))
         ;; :width (- x2 x1)
         ;; :height (- y2 y1)))

(defmethod initialize :builtin/group
  [shape {:keys [x1 y1 x2 y2] :as props}]
  (assoc shape ::initial props))

(defmethod initialize :builtin/line
  [shape {:keys [x1 y1 x2 y2]}]
  (assoc shape
         :x1 x1
         :y1 y1
         :x2 x2
         :y2 y2))

(defmethod initialize :builtin/circle
  [shape {:keys [x1 y1 x2 y2]}]
  (assoc shape
         :cx x1
         :cy y1
         :rx (mth/abs (- x2 x1))
         :ry (mth/abs (- y2 y1))))

;; FIXME: lock mode

(defmethod resize :builtin/line
  [shape {:keys [x y] :as pos}]
  (assoc shape :x2 x :y2 y))

(defmethod resize :builtin/circle
  [shape {:keys [x y lock] :as pos}]
  (let [cx (:cx shape)
        cy (:cy shape)

        rx (mth/abs (- x cx))
        ry (mth/abs (- y cy))]
    (if lock
      (assoc shape :rx rx :ry rx)
      (assoc shape :rx rx :ry ry))))

(defmethod resize :builtin/rect
  [shape {:keys [x y lock] :as pos}]
  (if lock
    (assoc shape :x2 x :y2 x)
    (assoc shape :x2 x :y2 y)))

(defmethod resize :builtin/text
  [shape {:keys [x y lock] :as pos}]
  (if lock
    (assoc shape :x2 x :y2 x)
    (assoc shape :x2 x :y2 y)))

(defmethod resize :default
  [shape _]
  (throw (ex-info "Not implemented (resize)" (select-keys shape [:type]))))

(defmethod resize' ::rect
  [shape {:keys [width height] :as size}]
  (let [x1 (:x1 shape)
        y1 (:y1 shape)]
    (assoc shape
           :x2 (+ x1 width)
           :y2 (+ y1 height))))

(defmethod resize' :default
  [shape _]
  (throw (ex-info "Not implemented (resize')" (select-keys shape [:type]))))

(defmethod size ::rect
  [{:keys [x1 y1 x2 y2] :as shape}]
  {:width (- x2 x1)
   :height (- y2 y1)})

(defmethod size :default
  [shape _]
  (throw (ex-info "Not implemented (size)" (select-keys shape [:type]))))

;; Move

(defmethod move ::rect
  [shape {dx :x dy :y}]
  (assoc shape
         :x1 (+ (:x1 shape) dx)
         :y1 (+ (:y1 shape) dy)
         :x2 (+ (:x2 shape) dx)
         :y2 (+ (:y2 shape) dy)))

(defmethod move :builtin/group
  [shape {dx :x dy :y}]
  (assoc shape
         :dx (+ (:dx shape 0) dx)
         :dy (+ (:dy shape 0) dy)))

(defmethod move :builtin/line
  [shape {dx :x dy :y}]
  (assoc shape
         :x1 (+ (:x1 shape) dx)
         :y1 (+ (:y1 shape) dy)
         :x2 (+ (:x2 shape) dx)
         :y2 (+ (:y2 shape) dy)))

(defmethod move :builtin/circle
  [shape {dx :x dy :y}]
  (assoc shape
         :cx (+ (:cx shape) dx)
         :cy (+ (:cy shape) dy)))

(defmethod move :default
  [shape _]
  (throw (ex-info "Not implemented (move)" (select-keys shape [:type]))))

(defmethod move' ::rect
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- (:x1 shape) x) 0)
        dy (if y (- (:y1 shape) y) 0)]
    (move shape (gpt/point dx dy))))

(defmethod move' :builtin/line
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- (:x1 shape) x) 0)
        dy (if y (- (:y1 shape) y) 0)]
    (move shape (gpt/point dx dy))))

(defmethod move' :builtin/circle
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- (:cx shape) x) 0)
        dy (if y (- (:cy shape) y) 0)]
    (move shape (gpt/point dx dy))))

(defmethod move' :default
  [shape _]
  (throw (ex-info "Not implemented (move')" (select-keys shape [:type]))))

(defmethod rotate ::shape
  [shape rotation]
  (assoc shape :rotation rotation))

(declare container-rect)

(defmethod outer-rect' ::shape
  [{:keys [group] :as shape}]
  (as-> shape $
    (assoc $ :x (:x1 shape) :y (:y1 shape))
    (merge $ (size $))))

(defmethod outer-rect' :builtin/line
  [{:keys [x1 y1 x2 y2 group] :as shape}]
  (let [group (get-in @st/state [:shapes-by-id group])
        props {:x (+ x1 (:dx group 0))
               :y (+ y1 (:dy group 0))
               :width (- x2 x1)
               :height (- y2 y1)}]
    (-> (merge shape props)
        (container-rect))))

(defmethod outer-rect' :builtin/circle
  [{:keys [cx cy rx ry group] :as shape}]
  (let [group (get-in @st/state [:shapes-by-id group])
        props {:x (+ (- cx rx) (:dx group 0))
               :y (+ (- cy ry) (:dy group 0))
               :width (* rx 2)
               :height (* ry 2)}]
    (-> (merge shape props)
        (container-rect))))

(defmethod outer-rect' :builtin/group
  [{:keys [id group rotation dx dy] :as shape}]
  (let [shapes (->> (:items shape)
                    (map #(get-in @st/state [:shapes-by-id %]))
                    (map outer-rect'))
        x (apply min (map :x shapes))
        y (apply min (map :y shapes))
        x' (apply max (map (fn [{:keys [x width]}] (+ x width)) shapes))
        y' (apply max (map (fn [{:keys [y height]}] (+ y height)) shapes))
        width (- x' x)
        height (- y' y)
        x (+ x dx)
        y (+ y dy)]
    (as-> shape $
      (merge $ {:width width
                :height height
                :x x
                :y y})
      (container-rect $))))

(defmethod outer-rect' :default
  [shape _]
  (throw (ex-info "Not implemented (outer-rect')" (select-keys shape [:type]))))

(defmethod transformation :builtin/icon
  [{:keys [x1 y1 rotation view-box] :or {rotation 0} :as shape}]
  (let [{:keys [width height]} (size shape)
        orig-width (nth view-box 2)
        orig-height (nth view-box 3)
        scale-x (/ width orig-width)
        scale-y (/ height orig-height)
        center-x (- width (/ width 2))
        center-y (- height (/ height 2))]
    (-> (gmt/matrix)
        (gmt/translate x1 y1)
        (gmt/translate center-x center-y)
        (gmt/rotate rotation)
        (gmt/translate (- center-x) (- center-y))
        (gmt/scale scale-x scale-y))))

(defmethod transformation :builtin/rect
  [{:keys [x1 y1 rotation] :or {rotation 0} :as shape}]
  (let [{:keys [width height]} (size shape)
        center-x (+ x1 (/ width 2))
        center-y (+ y1 (/ height 2))]
    (-> (gmt/matrix)
        (gmt/translate center-x center-y)
        (gmt/rotate rotation)
        (gmt/translate (- center-x) (- center-y)))))


(defmethod transformation :builtin/text
  [{:keys [x1 y1 rotation] :or {rotation 0} :as shape}]
  (let [{:keys [width height]} (size shape)
        center-x (+ x1 (/ width 2))
        center-y (+ y1 (/ height 2))]
    (-> (gmt/matrix)
        (gmt/translate center-x center-y)
        (gmt/rotate rotation)
        (gmt/translate (- center-x) (- center-y)))))


(defmethod transformation :builtin/circle
  [{:keys [cx cy rx ry rotation] :or {rotation 0} :as shape}]
  (-> (gmt/matrix)
      (gmt/translate cx cy)
      (gmt/rotate rotation)
      (gmt/translate (- cx) (- cy))))

(declare outer-rect)

(defmethod transformation :builtin/group
  [{:keys [dx dy rotation items] :or {rotation 0} :as shape}]
  (let [shapes-by-id (get @st/state :shapes-by-id)
        shapes (map #(get shapes-by-id %) items)
        {:keys [x y width height]} (outer-rect shapes)
        center-x (+ x (/ width 2))
        center-y (+ y (/ height 2))]
    (-> (gmt/matrix)
        (gmt/translate (or dx 0) (or dy 0))
        (gmt/translate center-x center-y)
        (gmt/rotate rotation)
        (gmt/translate (- center-x) (- center-y)))))

(defmethod transformation :default
  [shape _]
  (throw (ex-info "Not implemented (transformation)" (select-keys shape [:type]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn apply-rotation
  [[x y :as v] rotation]
  (let [angle (mth/radians rotation)
        rx (- (* x (mth/cos angle))
              (* y (mth/sin angle)))
        ry (+ (* x (mth/sin angle))
              (* y (mth/cos angle)))]
    (let [r [(mth/precision rx 6)
             (mth/precision ry 6)]]
      r)))

(defn container-rect
  [{:keys [x y width height rotation] :as shape}]
  (let [center-x (+ x (/ width 2))
        center-y (+ y (/ height 2))

        angle (mth/radians (or rotation 0))
        x1 (- x center-x)
        y1 (- y center-y)

        x2 (- (+ x width) center-x)
        y2 (- y center-y)

        [rx1 ry1] (apply-rotation [x1 y1] rotation)
        [rx2 ry2] (apply-rotation [x2 y2] rotation)

        [d1 d2] (cond
                  (and (>= rotation 0)
                       (< rotation 90))
                  [(mth/abs ry1)
                   (mth/abs rx2)]

                  (and (>= rotation 90)
                       (< rotation 180))
                  [(mth/abs ry2)
                   (mth/abs rx1)]

                  (and (>= rotation 180)
                       (< rotation 270))
                  [(mth/abs ry1)
                   (mth/abs rx2)]

                  (and (>= rotation 270)
                       (<= rotation 360))
                  [(mth/abs ry2)
                   (mth/abs rx1)])
        final-x (- center-x d2)
        final-y (- center-y d1)
        final-width (* d2 2)
        final-height (* d1 2)]
    (merge shape
           {:x final-x
            :y final-y
            :width final-width
            :height final-height})))

;; (defn group-dimensions
;;   "Given a collection of shapes, calculates the
;;   dimensions of the resultant group."
;;   [shapes]
;;   {:pre [(seq shapes)]}
;;   (let [shapes (map container-rect shapes)
;;         x (apply min (map :x shapes))
;;         y (apply min (map :y shapes))
;;         x' (apply max (map (fn [{:keys [x width]}] (+ x width)) shapes))
;;         y' (apply max (map (fn [{:keys [y height]}] (+ y height)) shapes))
;;         width (- x' x)
;;         height (- y' y)]
;;     {:width width
;;      :height height
;;      :view-box [0 0 width height]
;;      :x x
;;      :y y}))

(defn outer-rect
  [shapes]
  {:pre [(seq shapes)]}
  (let [shapes (map outer-rect' shapes)
        x (apply min (map :x shapes))
        y (apply min (map :y shapes))
        x' (apply max (map (fn [{:keys [x width]}] (+ x width)) shapes))
        y' (apply max (map (fn [{:keys [y height]}] (+ y height)) shapes))
        width (- x' x)
        height (- y' y)]
    {:width width
     :height height
     :x x
     :y y}))

(defn translate-coords
  "Given a shape and initial coords, transform
  it mapping its coords to new provided initial coords."
  ([shape x y]
   (translate-coords shape x y -))
  ([shape x y op]
   (let [x' (:x shape)
         y' (:y shape)]
     (assoc shape :x (op x' x) :y (op y' y)))))

(defn resolve-parent
  "Recursively resolve the real shape parent."
  [{:keys [group] :as shape}]
  (if group
    (resolve-parent (get-in @st/state [:shapes-by-id group]))
    shape))

(defn contained-in?
  "Check if a shape is contained in the
  provided selection rect."
  [shape selrect]
  (let [sx1 (:x selrect)
        sx2 (+ sx1 (:width selrect))
        sy1 (:y selrect)
        sy2 (+ sy1 (:height selrect))
        rx1 (:x shape)
        rx2 (+ rx1 (:width shape))
        ry1 (:y shape)
        ry2 (+ ry1 (:height shape))]
    (and (neg? (- (:y selrect) (:y shape)))
         (neg? (- (:x selrect) (:x shape)))
         (pos? (- (+ (:y selrect)
                     (:height selrect))
                  (+ (:y shape)
                     (:height shape))))
         (pos? (- (+ (:x selrect)
                     (:width selrect))
                  (+ (:x shape)
                     (:width shape)))))))


