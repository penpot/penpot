(ns uxbox.main.data.workspace.transforms
  "Events related with shapes transformations"
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.common.data :as d]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.geom :as geom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.main.data.helpers :as helpers]
   [uxbox.main.data.workspace.common :refer [IBatchedChange IUpdateGroup] :as common]))

;; -- Declarations

(declare set-modifiers)
(declare set-rotation)
(declare apply-modifiers)

;; -- Helpers

(defn- apply-zoom
  [point]
  (gpt/divide point (gpt/point @refs/selected-zoom)))

;; For each of the 8 handlers gives the modifier for resize
;; for example, right will only grow in the x coordinate and left
;; will grow in the inverse of the x coordinate
(def ^:private handler-modifiers
  {:right        [ 1  0]
   :bottom       [ 0  1]
   :left         [-1  0]
   :top          [ 0 -1]
   :top-right    [ 1 -1]
   :top-left     [-1 -1]
   :bottom-right [ 1  1]
   :bottom-left  [-1  1]})

;; Given a handler returns the coordinate origin for resizes
;; this is the opposite of the handler so for right we want the
;; left side as origin of the resize
;; sx, sy => start x/y
;; mx, my => middle x/y
;; ex, ey => end x/y
(defn- handler-resize-origin [{sx :x sy :y :keys [width height]} handler]
  (let [mx (+ sx (/ width 2))
        my (+ sy (/ height 2))
        ex (+ sx width)
        ey (+ sy height)
        
        [x y] (case handler
                :right [sx my]
                :bottom [mx sy]
                :left [ex my]
                :top [mx ey]
                :top-right [sx ey]
                :top-left [ex ey]
                :bottom-right [sx sy]
                :bottom-left [ex sy])]
    (gpt/point x y)))

;; -- RESIZE
(defn start-resize
  [handler ids shape objects]
  (letfn [(resize [shape initial [point lock?]]
            (let [frame (get objects (:frame-id shape))
                  {:keys [width height rotation]} shape

                  center (gpt/center shape)
                  shapev (-> (gpt/point width height))

                  ;; Vector modifiers depending on the handler
                  handler-modif (let [[x y] (handler-modifiers handler)] (gpt/point x y))

                  ;; Difference between the origin point in the coordinate system of the rotation
                  deltav (-> (gpt/subtract point initial)
                             (gpt/transform (gmt/rotate-matrix (- rotation)))
                             (gpt/multiply handler-modif))

                  ;; Resize vector
                  scalev (gpt/divide (gpt/add shapev deltav) shapev)

                  shape-transform (:transform shape (gmt/matrix))
                  shape-transform-inverse (:transform-inverse shape (gmt/matrix))

                  ;; Resize origin point given the selected handler
                  origin  (-> (handler-resize-origin shape handler)
                              (geom/transform-shape-point shape shape-transform))]
              
              (rx/of (set-modifiers ids {:resize-vector scalev
                                         :resize-origin origin
                                         :resize-transform shape-transform
                                         :resize-transform-inverse shape-transform-inverse}))))

          ;; Unifies the instantaneous proportion lock modifier
          ;; activated by Ctrl key and the shapes own proportion
          ;; lock flag that can be activated on element options.
          (normalize-proportion-lock [[point ctrl?]]
            (let [proportion-lock? (:proportion-lock shape)]
              [point (or proportion-lock? ctrl?)]))

          ;; Applies alginment to point if it is currently
          ;; activated on the current workspace
          ;; (apply-grid-alignment [point]
          ;;   (if @refs/selected-alignment
          ;;     (uwrk/align-point point)
          ;;     (rx/of point)))
          ]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [initial (apply-zoom @ms/mouse-position)
              shape  (geom/shape->rect-shape shape)
              stoper (rx/filter ms/mouse-up? stream)]
          (rx/concat
           (->> ms/mouse-position
                (rx/map apply-zoom)
                ;; (rx/mapcat apply-grid-alignment)
                (rx/with-latest vector ms/mouse-position-ctrl)
                (rx/map normalize-proportion-lock)
                (rx/mapcat (partial resize shape initial))
                (rx/take-until stoper))
           (rx/of (apply-modifiers ids))))))))


;; -- ROTATE
(defn start-rotate
  [shapes]
  (ptk/reify ::start-rotate
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter ms/mouse-up? stream)
            group  (geom/selection-rect shapes)
            group-center (gpt/center group)
            initial-angle (gpt/angle (apply-zoom @ms/mouse-position) group-center)
            calculate-angle (fn [pos ctrl?]
                              (let [angle (- (gpt/angle pos group-center) initial-angle)
                                    angle (if (neg? angle) (+ 360 angle) angle)
                                    modval (mod angle 90)
                                    angle (if ctrl?
                                            (if (< 50 modval)
                                              (+ angle (- 90 modval))
                                              (- angle modval))
                                            angle)
                                    angle (if (= angle 360)
                                            0
                                            angle)]
                                angle))]
        (rx/concat
         (->> ms/mouse-position
              (rx/map apply-zoom)
              (rx/with-latest vector ms/mouse-position-ctrl)
              (rx/map (fn [[pos ctrl?]]
                        (let [delta-angle (calculate-angle pos ctrl?)]
                          (set-rotation delta-angle shapes))))
              (rx/take-until stoper))
         (rx/of (apply-modifiers (map :id shapes))))))))

;; -- MOVE

(defn start-move-selected []
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            stoper (rx/filter ms/mouse-up? stream)
            zero-point? #(= % (gpt/point 0 0))
            initial (apply-zoom @ms/mouse-position)
            position @ms/mouse-position]
        (rx/concat
         (->> ms/mouse-position
              (rx/map apply-zoom)
              (rx/filter (complement zero-point?))
              (rx/map #(gpt/subtract % initial))
              (rx/map #(set-modifiers selected {:displacement (gmt/translate-matrix %)}))
              (rx/take-until stoper))
         (rx/of (apply-modifiers selected)))))))

(defn- get-displacement-with-grid
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction options]
  (let [grid-x (:grid-x options 10)
        grid-y (:grid-y options 10)
        x-mod (mod (:x shape) grid-x)
        y-mod (mod (:y shape) grid-y)]
    (case direction
      :up (gpt/point 0 (- (if (zero? y-mod) grid-y y-mod)))
      :down (gpt/point 0 (- grid-y y-mod))
      :left (gpt/point (- (if (zero? x-mod) grid-x x-mod)) 0)
      :right (gpt/point (- grid-x x-mod) 0))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(defn move-selected
  [direction align?]
  (us/verify ::direction direction)
  (us/verify boolean? align?)

  (ptk/reify ::move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (:current-page-id state)
            selected (get-in state [:workspace-local :selected])
            options (get-in state [:workspace-data pid :options])
            shapes (map #(get-in state [:workspace-data pid :objects %]) selected)
            shape (geom/shapes->rect-shape shapes)
            displacement (if align?
                           (get-displacement-with-grid shape direction options)
                           (get-displacement shape direction))]
        (rx/of (set-modifiers selected displacement)
               (apply-modifiers selected))))))


;; -- Apply modifiers

(defn set-modifiers
  ([ids modifiers] (set-modifiers ids modifiers true))
  ([ids modifiers recurse-frames?]
   (us/verify (s/coll-of uuid?) ids)
   (ptk/reify ::set-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [page-id (:current-page-id state)
             objects (get-in state [:workspace-data page-id :objects])
             not-frame-id? (fn [shape-id]
                             (let [shape (get objects shape-id)]
                               (or recurse-frames? (not (= :frame (:type shape))))))

             ;; ID's + Children but remove frame children if the flag is set to false
             ids-with-children (concat ids (mapcat #(helpers/get-children % objects) (filter not-frame-id? ids)))

             ;; For each shape updates the modifiers given as arguments
             update-shape (fn [state shape-id]
                            (update-in
                             state
                             [:workspace-data page-id :objects shape-id :modifiers]
                             #(merge % modifiers)))]
         (reduce update-shape state ids-with-children))))))

;; Set-rotation is custom because applies different modifiers to each shape adjusting their position
(defn set-rotation
  [delta-rotation shapes]
  (ptk/reify ::set-rotation
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (letfn [(calculate-displacement [shape angle center]
                  (let [shape-center (geom/center shape)]
                    (-> (gmt/matrix)
                        (gmt/rotate angle center)
                        (gmt/rotate (- angle) shape-center))))

                (rotate-shape [state angle shape center]
                  (let [objects (get-in state [:workspace-data page-id :objects])
                        path [:workspace-data page-id :objects (:id shape) :modifiers]
                        ds (calculate-displacement shape angle center)]
                    (-> state
                        (assoc-in (conj path :rotation) angle)
                        (assoc-in (conj path :displacement) ds))))


                (rotate-around-center [state angle center shapes]
                  (reduce #(rotate-shape %1 angle %2 center) state shapes))]

          (let [center (-> shapes geom/selection-rect gpt/center)
                objects (get-in state [:workspace-data page-id :objects])
                id->obj #(get objects %)
                get-children (fn [shape] (map id->obj (helpers/get-children (:id shape) objects)))
                shapes (concat shapes (mapcat get-children shapes))]
            (rotate-around-center state delta-rotation center shapes)))))))

(defn apply-modifiers
  [ids]
  (us/verify (s/coll-of uuid?) ids)
  (ptk/reify ::apply-modifiers
    IUpdateGroup
    (get-ids [_] ids)

    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])

            ;; ID's + Children
            ids-with-children (concat ids (mapcat #(helpers/get-children % objects) ids))

            ;; For each shape applies the modifiers by transforming the objects
            update-shape
            (fn [state shape-id]
              (update-in state [:workspace-data page-id :objects shape-id] geom/transform-shape))]

        (reduce update-shape state ids-with-children)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)]
        (rx/of (common/diff-and-commit-changes page-id)
               (common/rehash-shape-frame-relationship ids))))))
