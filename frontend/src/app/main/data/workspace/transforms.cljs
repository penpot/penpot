;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.transforms
  "Events related with shapes transformations"
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; -- Helpers --------------------------------------------------------

;; For each of the 8 handlers gives the multiplier for resize
;; for example, right will only grow in the x coordinate and left
;; will grow in the inverse of the x coordinate
(def ^:private handler-multipliers
  {:right        [ 1  0]
   :bottom       [ 0  1]
   :left         [-1  0]
   :top          [ 0 -1]
   :top-right    [ 1 -1]
   :top-left     [-1 -1]
   :bottom-right [ 1  1]
   :bottom-left  [-1  1]})

(defn- handler-resize-origin
  "Given a handler, return the coordinate origin for resizes.
   This is the opposite of the handler so for right we want the
   left side as origin of the resize.

   sx, sy => start x/y
   mx, my => middle x/y
   ex, ey => end x/y
  "
  [{sx :x sy :y :keys [width height]} handler]
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

(defn- fix-init-point
  "Fix the initial point so the resizes are accurate"
  [initial handler shape]
  (let [{:keys [x y width height]} (:selrect shape)]
    (cond-> initial
      (contains? #{:left :top-left :bottom-left} handler)
      (assoc :x x)

      (contains? #{:right :top-right :bottom-right} handler)
      (assoc :x (+ x width))

      (contains? #{:top :top-right :top-left} handler)
      (assoc :y y)

      (contains? #{:bottom :bottom-right :bottom-left} handler)
      (assoc :y (+ y height)))))

(defn finish-transform []
  (ptk/reify ::finish-transform
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :transform))))


;; -- Temporary modifiers -------------------------------------------

;; During an interactive transformation of shapes (e.g. when resizing or rotating
;; a group with the mouse), there are a lot of objects that need to be modified
;; (in this case, the group and all its children).
;;
;; To avoid updating the shapes theirselves, and forcing redraw of all components
;; that depend on the "objects" global state, we set a "modifiers" structure, with
;; the changes that need to be applied, and store it in :workspace-modifiers global
;; variable. The viewport reads this and merges it into the objects list it uses to
;; paint the viewport content, redrawing only the objects that have new modifiers.
;;
;; When the interaction is finished (e.g. user releases mouse button), the
;; apply-modifiers event is done, that consolidates all modifiers into the base
;; geometric attributes of the shapes.

(declare set-modifiers-recursive)
(declare set-local-displacement)
(declare clear-local-transform)

(defn- set-modifiers
  ([ids] (set-modifiers ids nil false))
  ([ids modifiers] (set-modifiers ids modifiers false))
  ([ids modifiers ignore-constraints]
   (us/verify (s/coll-of uuid?) ids)
   (ptk/reify ::set-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [modifiers (or modifiers (get-in state [:workspace-local :modifiers] {}))
             page-id (:current-page-id state)
             objects (wsh/lookup-page-objects state page-id)
             ids     (into #{} (remove #(get-in objects [% :blocked] false)) ids)]

         (reduce (fn [state id]
                     (update state :workspace-modifiers
                             #(set-modifiers-recursive %
                                                       objects
                                                       (get objects id)
                                                       modifiers
                                                       nil
                                                       nil
                                                       ignore-constraints)))
                 state
                 ids))))))

;; Rotation use different algorithm to calculate children modifiers (and do not use child constraints).
(defn- set-rotation-modifiers
  ([angle shapes]
   (set-rotation-modifiers angle shapes (-> shapes gsh/selection-rect gsh/center-selrect)))

  ([angle shapes center]
   (ptk/reify ::set-rotation-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [objects (wsh/lookup-page-objects state)
             shapes  (->> shapes
                          (remove #(get % :blocked false))
                          (mapcat (fn [shape]
                                    (->> (cp/get-children (:id shape) objects)
                                         (map #(get objects %)))))
                          (concat shapes))

             update-shape
             (fn [modifiers shape]
               (let [rotate-modifiers (gsh/rotation-modifiers shape center angle)]
                 (assoc-in modifiers [(:id shape) :modifiers] rotate-modifiers)))]

         (update state :workspace-modifiers #(reduce update-shape % shapes)))))))

(defn- apply-modifiers
  [ids]
  (us/verify (s/coll-of uuid?) ids)
  (ptk/reify ::apply-modifiers
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects           (wsh/lookup-page-objects state)
            children-ids      (->> ids (mapcat #(cp/get-children % objects)))
            ids-with-children (d/concat-vec children-ids ids)
            object-modifiers  (get state :workspace-modifiers)
            ignore-tree       (d/mapm #(get-in %2 [:modifiers :ignore-geometry?]) object-modifiers)]

        (rx/of (dwu/start-undo-transaction)
               (dch/update-shapes
                 ids-with-children
                 (fn [shape]
                   (-> shape
                       (merge (get object-modifiers (:id shape)))
                       (gsh/transform-shape)))
                 {:reg-objects? true
                  :ignore-tree ignore-tree
                  ;; Attributes that can change in the transform. This way we don't have to check
                  ;; all the attributes
                  :attrs [:selrect :points
                          :x :y
                          :width :height
                          :content
                          :transform
                          :transform-inverse
                          :rotation
                          :flip-x
                          :flip-y]})
               (clear-local-transform)
               (dwu/commit-undo-transaction))))))

(defn- check-delta
  "If the shape is a component instance, check its relative position respect the
  root of the component, and see if it changes after applying a transformation."
  [shape root transformed-shape transformed-root objects]
  (let [root (cond
               (:component-root? shape)
               shape

               (nil? root)
               (cp/get-root-shape shape objects)

               :else root)

        transformed-root (cond
                           (:component-root? transformed-shape)
                           transformed-shape

                           (nil? transformed-root)
                           (cp/get-root-shape transformed-shape objects)

                           :else transformed-root)

        shape-delta (when root
                      (gpt/point (- (:x shape) (:x root))
                                 (- (:y shape) (:y root))))

        transformed-shape-delta (when transformed-root
                                  (gpt/point (- (:x transformed-shape) (:x transformed-root))
                                             (- (:y transformed-shape) (:y transformed-root))))

        ignore-geometry? (= shape-delta transformed-shape-delta)]

    [root transformed-root ignore-geometry?]))

(defn- set-modifiers-recursive
  [modif-tree objects shape modifiers root transformed-root ignore-constraints]
  (let [children (map (d/getf objects) (:shapes shape))

        transformed-shape (gsh/transform-shape (assoc shape :modifiers modifiers))

        [root transformed-root ignore-geometry?]
        (check-delta shape root transformed-shape transformed-root objects)

        modifiers (assoc modifiers :ignore-geometry? ignore-geometry?)

        transformed-rect (gsh/calc-transformed-parent-rect shape modifiers)

        set-child
        (fn [modif-tree child]
          (let [child-modifiers
                (gsh/calc-child-modifiers shape child modifiers ignore-constraints transformed-rect)]

            (cond-> modif-tree
              (d/not-empty? (dissoc child-modifiers :ignore-geometry?))
              (set-modifiers-recursive objects
                                       child
                                       child-modifiers
                                       root
                                       transformed-root
                                       ignore-constraints))))

        modif-tree
        (-> modif-tree
            (assoc-in [(:id shape) :modifiers] modifiers))]

    (reduce set-child modif-tree children)))

(defn- set-local-displacement [point]
  (ptk/reify ::start-local-displacement
    ptk/UpdateEvent
    (update [_ state]
      (let [mtx (gmt/translate-matrix point)]
        (-> state
            (assoc-in [:workspace-local :modifiers] {:displacement mtx}))))))

(defn- clear-local-transform []
  (ptk/reify ::clear-local-transform
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :workspace-modifiers)
          (update :workspace-local dissoc :modifiers :current-move-selected)))))


;; -- Resize --------------------------------------------------------

(defn start-resize
  "Enter mouse resize mode, until mouse button is released."
  [handler ids shape]
  (letfn [(resize [shape initial layout [point lock? center? point-snap]]
            (let [{:keys [width height]} (:selrect shape)
                  {:keys [rotation]} shape

                  shape-center (gsh/center-shape shape)
                  shape-transform (:transform shape (gmt/matrix))
                  shape-transform-inverse (:transform-inverse shape (gmt/matrix))

                  rotation (or rotation 0)

                  initial (gsh/transform-point-center initial shape-center shape-transform-inverse)
                  initial (fix-init-point initial handler shape)

                  point (gsh/transform-point-center (if (= rotation 0) point-snap point)
                                                    shape-center shape-transform-inverse)

                  shapev (-> (gpt/point width height))

                  scale-text (:scale-text layout)

                  ;; Force lock if the scale text mode is active
                  lock? (or lock? scale-text)

                  ;; Vector modifiers depending on the handler
                  handler-mult (let [[x y] (handler-multipliers handler)] (gpt/point x y))

                  ;; Difference between the origin point in the coordinate system of the rotation
                  deltav (-> (gpt/to-vec initial point)
                             (gpt/multiply handler-mult))

                  ;; Resize vector
                  scalev (gpt/divide (gpt/add shapev deltav) shapev)

                  scalev (if lock?
                           (let [v (cond
                                     (#{:right :left} handler) (:x scalev)
                                     (#{:top :bottom} handler) (:y scalev)
                                     :else (max (:x scalev) (:y scalev)))]
                             (gpt/point v v))

                           scalev)

                  ;; Resize origin point given the selected handler
                  handler-origin  (handler-resize-origin (:selrect shape) handler)


                  ;; If we want resize from center, displace the shape
                  ;; so it is still centered after resize.
                  displacement
                  (when center?
                    (-> shape-center
                        (gpt/subtract handler-origin)
                        (gpt/multiply scalev)
                        (gpt/add handler-origin)
                        (gpt/subtract shape-center)
                        (gpt/multiply (gpt/point -1 -1))
                        (gpt/transform shape-transform)))

                  resize-origin
                  (cond-> (gsh/transform-point-center handler-origin shape-center shape-transform)
                    (some? displacement)
                    (gpt/add displacement))

                  displacement (when (some? displacement)
                                 (gmt/translate-matrix displacement))]

              (rx/of (set-modifiers ids
                                    {:displacement displacement
                                     :resize-vector scalev
                                     :resize-origin resize-origin
                                     :resize-transform shape-transform
                                     :resize-scale-text scale-text
                                     :resize-transform-inverse shape-transform-inverse}))))

          ;; Unifies the instantaneous proportion lock modifier
          ;; activated by Shift key and the shapes own proportion
          ;; lock flag that can be activated on element options.
          (normalize-proportion-lock [[point shift? alt?]]
            (let [proportion-lock? (:proportion-lock shape)]
              [point (or proportion-lock? shift?) alt?]))]
    (reify
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc-in [:workspace-local :transform] :resize)))

      ptk/WatchEvent
      (watch [it state stream]
        (let [initial-position @ms/mouse-position
              stoper  (rx/filter ms/mouse-up? stream)
              layout  (:workspace-layout state)
              page-id (:current-page-id state)
              zoom    (get-in state [:workspace-local :zoom] 1)
              objects (wsh/lookup-page-objects state page-id)
              resizing-shapes (map #(get objects %) ids)
              text-shapes-ids (->> resizing-shapes
                                   (filter #(= :text (:type %)))
                                   (map :id))]
          (rx/concat
           (rx/of (dch/update-shapes text-shapes-ids #(assoc % :grow-type :fixed)))
           (->> ms/mouse-position
                (rx/with-latest-from ms/mouse-position-shift ms/mouse-position-alt)
                (rx/map normalize-proportion-lock)
                (rx/switch-map (fn [[point _ _ :as current]]
                                 (->> (snap/closest-snap-point page-id resizing-shapes layout zoom point)
                                      (rx/map #(conj current %)))))
                (rx/mapcat (partial resize shape initial-position layout))
                (rx/take-until stoper))
           (rx/of (apply-modifiers ids)
                  (finish-transform))))))))

(defn update-dimensions
  "Change size of shapes, from the sideber options form."
  [ids attr value]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify #{:width :height} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-dimensions
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data :pages-index page-id :objects])]

        (reduce (fn [state id]
                  (let [shape (get objects id)
                        modifiers (gsh/resize-modifiers shape attr value)]
                    (update state :workspace-modifiers
                            #(set-modifiers-recursive %
                                                      objects
                                                      shape
                                                      modifiers
                                                      nil
                                                      nil
                                                      false))))
                state
                ids)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)

            ;; TODO: looks completly redundant operation because
            ;; apply-modifiers already finds all children.
            ids     (d/concat-vec ids (mapcat #(cp/get-children % objects) ids))]
        (rx/of (apply-modifiers ids))))))


;; -- Rotate --------------------------------------------------------

(defn start-rotate
  "Enter mouse rotate mode, until mouse button is released."
  [shapes]
  (ptk/reify ::start-rotate
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :transform] :rotate)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stoper          (rx/filter ms/mouse-up? stream)
            group           (gsh/selection-rect shapes)
            group-center    (gsh/center-selrect group)
            initial-angle   (gpt/angle @ms/mouse-position group-center)
            calculate-angle (fn [pos ctrl?]
                              (let [angle (- (gpt/angle pos group-center) initial-angle)
                                    angle (if (neg? angle) (+ 360 angle) angle)
                                    modval (mod angle 45)
                                    angle (if ctrl?
                                            (if (< 22.5 modval)
                                              (+ angle (- 45 modval))
                                              (- angle modval))
                                            angle)
                                    angle (if (= angle 360)
                                            0
                                            angle)]
                                angle))]
        (rx/concat
         (->> ms/mouse-position
              (rx/with-latest vector ms/mouse-position-ctrl)
              (rx/map (fn [[pos ctrl?]]
                        (let [delta-angle (calculate-angle pos ctrl?)]
                          (set-rotation-modifiers delta-angle shapes group-center))))
              (rx/take-until stoper))
         (rx/of (apply-modifiers (map :id shapes))
                (finish-transform)))))))

(defn increase-rotation
  "Rotate shapes a fixed angle, from a keyboard action."
  [ids rotation]
  (ptk/reify ::increase-rotation
    ptk/WatchEvent
    (watch [_ state _]

      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            rotate-shape (fn [shape]
                           (let [delta (- rotation (:rotation shape))]
                             (set-rotation-modifiers delta [shape])))]
        (rx/concat
         (rx/from (->> ids (map #(get objects %)) (map rotate-shape)))
         (rx/of (apply-modifiers ids)))))))


;; -- Move ----------------------------------------------------------

(declare start-move)
(declare start-move-duplicate)
(declare calculate-frame-for-move)
(declare get-displacement)

(defn start-move-selected
  "Enter mouse move mode, until mouse button is released."
  []
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [initial  (deref ms/mouse-position)
            selected (wsh/lookup-selected state {:omit-blocked? true})
            stopper  (rx/filter ms/mouse-up? stream)]
        (when-not (empty? selected)
          (->> ms/mouse-position
               (rx/map #(gpt/to-vec initial %))
               (rx/map #(gpt/length %))
               (rx/filter #(> % 1))
               (rx/take 1)
               (rx/with-latest vector ms/mouse-position-alt)
               (rx/mapcat
                (fn [[_ alt?]]
                  (if alt?
                    ;; When alt is down we start a duplicate+move
                    (rx/of (start-move-duplicate initial)
                           (dws/duplicate-selected false))
                    ;; Otherwise just plain old move
                    (rx/of (start-move initial selected)))))
               (rx/take-until stopper)))))))


(defn- start-move-duplicate
  [from-position]
  (ptk/reify ::start-move-duplicate
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? ::dws/duplicate-selected))
           (rx/first)
           (rx/map #(start-move from-position))))))

(defn- start-move
  ([from-position] (start-move from-position nil))
  ([from-position ids]
   (ptk/reify ::start-move
     ptk/UpdateEvent
     (update [_ state]
       (-> state
           (assoc-in [:workspace-local :transform] :move)))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page-id (:current-page-id state)
             objects (wsh/lookup-page-objects state page-id)
             selected (wsh/lookup-selected state {:omit-blocked? true})
             ids     (if (nil? ids) selected ids)
             shapes  (mapv #(get objects %) ids)
             stopper (rx/filter ms/mouse-up? stream)
             layout  (get state :workspace-layout)
             zoom    (get-in state [:workspace-local :zoom] 1)

             fix-axis (fn [[position shift?]]
                        (let [delta (gpt/to-vec from-position position)]
                          (if shift?
                            (if (> (mth/abs (:x delta)) (mth/abs (:y delta)))
                              (gpt/point (:x delta) 0)
                              (gpt/point 0 (:y delta)))
                            delta)))

             position (->> ms/mouse-position
                           (rx/with-latest-from ms/mouse-position-shift)
                           (rx/map #(fix-axis %)))

             snap-delta (rx/concat
                         ;; We send the nil first so the stream is not waiting for the first value
                         (rx/of nil)
                         (->> position
                              (rx/throttle 20)
                              (rx/switch-map
                               (fn [pos]
                                 (->> (snap/closest-snap-move page-id shapes objects layout zoom pos)
                                      (rx/map #(vector pos %)))))))]
         (if (empty? shapes)
           (rx/empty)
           (rx/concat
            (->> position
                 (rx/with-latest vector snap-delta)
                 (rx/map snap/correct-snap-point)
                 (rx/map set-local-displacement)
                 (rx/take-until stopper))

            (rx/of (set-modifiers ids)
                   (apply-modifiers ids)
                   (calculate-frame-for-move ids)
                   (finish-transform)))))))))

(s/def ::direction #{:up :down :right :left})

(defn move-selected
  "Move shapes a fixed increment in one direction, from a keyboard action."
  [direction shift?]
  (us/verify ::direction direction)
  (us/verify boolean? shift?)

  (let [same-event (js/Symbol "same-event")]
    (ptk/reify ::move-selected
      IDeref
      (-deref [_] direction)

      ptk/UpdateEvent
      (update [_ state]
        (if (nil? (get-in state [:workspace-local :current-move-selected]))
          (-> state
              (assoc-in [:workspace-local :transform] :move)
              (assoc-in [:workspace-local :current-move-selected] same-event))
          state))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= same-event (get-in state [:workspace-local :current-move-selected]))
          (let [selected (wsh/lookup-selected state {:omit-blocked? true})
                move-events (->> stream
                                 (rx/filter (ptk/type? ::move-selected))
                                 (rx/filter #(= direction (deref %))))
                stopper (->> move-events
                             (rx/debounce 100)
                             (rx/first))
                scale (if shift? (gpt/point 10) (gpt/point 1))
                mov-vec (gpt/multiply (get-displacement direction) scale)]

            (rx/concat
             (rx/merge
              (->> move-events
                   (rx/take-until stopper)
                   (rx/scan #(gpt/add %1 mov-vec) (gpt/point 0 0))
                   (rx/map set-local-displacement))
              (rx/of (move-selected direction shift?)))

             (rx/of (set-modifiers selected)
                    (apply-modifiers selected)
                    (finish-transform))))
            (rx/empty))))))

(s/def ::x number?)
(s/def ::y number?)
(s/def ::position
  (s/keys :opt-un [::x ::y]))

(defn update-position
  "Move shapes to a new position, from the sidebar options form."
  [id position]
  (us/verify ::us/uuid id)
  (us/verify ::position position)
  (ptk/reify ::update-position
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            shape   (get objects id)

            bbox (-> shape :points gsh/points->selrect)

            cpos (gpt/point (:x bbox) (:y bbox))
            pos  (gpt/point (or (:x position) (:x bbox))
                            (or (:y position) (:y bbox)))
            displ   (gmt/translate-matrix (gpt/subtract pos cpos))]
        (rx/of (set-modifiers [id] {:displacement displ})
               (apply-modifiers [id]))))))

(defn- calculate-frame-for-move
  [ids]
  (ptk/reify ::calculate-frame-for-move
    ptk/WatchEvent
    (watch [it state _]
      (let [position @ms/mouse-position
            page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            frame-id (cp/frame-id-by-position objects position)

            moving-shapes (->> ids
                               (cp/clean-loops objects)
                               (map #(get objects %))
                               (remove #(or (nil? %)
                                            (= (:frame-id %) frame-id))))

            rch [{:type :mov-objects
                  :page-id page-id
                  :parent-id frame-id
                  :shapes (mapv :id moving-shapes)}]


            uch (->> moving-shapes
                     (reverse)
                     (mapv (fn [shape]
                             {:type :mov-objects
                              :page-id page-id
                              :parent-id (:parent-id shape)
                              :index (cp/get-index-in-parent objects (:id shape))
                              :shapes [(:id shape)]})))]

        (when-not (empty? uch)
          (rx/of dwu/pop-undo-into-transaction
                 (dch/commit-changes {:redo-changes rch
                                      :undo-changes uch
                                      :origin it})
                 (dwu/commit-undo-transaction)
                 (dwc/expand-collapse frame-id)))))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))


;; -- Flip ----------------------------------------------------------

(defn flip-horizontal-selected []
  (ptk/reify ::flip-horizontal-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects  (wsh/lookup-page-objects state)
            selected (wsh/lookup-selected state {:omit-blocked? true})
            shapes   (map #(get objects %) selected)
            selrect  (gsh/selection-rect (->> shapes (map gsh/transform-shape)))
            origin   (gpt/point (:x selrect) (+ (:y selrect) (/ (:height selrect) 2)))]

        (rx/of (set-modifiers selected
                              {:resize-vector (gpt/point -1.0 1.0)
                               :resize-origin origin
                               :displacement (gmt/translate-matrix (gpt/point (- (:width selrect)) 0))}
                              true)
               (apply-modifiers selected))))))

(defn flip-vertical-selected []
  (ptk/reify ::flip-vertical-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects  (wsh/lookup-page-objects state)
            selected (wsh/lookup-selected state {:omit-blocked? true})
            shapes   (map #(get objects %) selected)
            selrect  (gsh/selection-rect (->> shapes (map gsh/transform-shape)))
            origin   (gpt/point (+ (:x selrect) (/ (:width selrect) 2)) (:y selrect))]

        (rx/of (set-modifiers selected
                              {:resize-vector (gpt/point 1.0 -1.0)
                               :resize-origin origin
                               :displacement (gmt/translate-matrix (gpt/point 0 (- (:height selrect))))}
                              true)
               (apply-modifiers selected))))))


;; -- Transform to path ---------------------------------------------

