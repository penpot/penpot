(ns uxbox.shapes)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static ^:private +hierarchy+
  (as-> (make-hierarchy) $
    (derive $ :builtin/icon ::shape)
    (derive $ :builtin/icon-svg ::shape)
    (derive $ :builtin/group ::shape)))

(defn shape?
  [type]
  {:pre [(keyword? type)]}
  (isa? +hierarchy+ type ::shape))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- dispatch-by-type
  [shape & params]
  (:type shape))

(defmulti -render
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti -render-svg
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti -move
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti -resize
  dispatch-by-type
  :hierarchy #'+hierarchy+)

(defmulti -rotate
  dispatch-by-type
  :hierarchy #'+hierarchy+)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod -move ::shape
  [shape {:keys [dx dy] :as opts}]
  (assoc shape
         :x (+ (:x shape) dx)
         :y (+ (:y shape) dy)))

(defmethod -resize ::shape
  [shape {:keys [width height] :as opts}]
  (assoc shape
         :width width
         :height height))

(defmethod -rotate ::shape
  [shape rotation]
  (assoc shape :rotation rotation))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-size-and-position
  "Given a collection of shapes, calculates the
  dimensions of possible envolving rect.

  Mainly used for calculate the selection
  rect or shapes grop size."
  [shapes]
  (let [x (apply min (map :x shapes))
        y (apply min (map :y shapes))
        x' (apply max (map (fn [{:keys [x width]}] (+ x width)) shapes))
        y' (apply max (map (fn [{:keys [y height]}] (+ y height)) shapes))
        width (- x' x)
        height (- y' y)]
    [width height x y]))

(defn translate-coords
  "Given a shape and initial coords, transform
  it mapping its coords to new provided initial coords."
  [shape x y]
  (let [x' (:x shape)
        y' (:y shape)]
    (assoc shape :x (- x' x) :y (- y' y))))

