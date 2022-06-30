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
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.common :as cpc]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.data.workspace.guides :as dwg]
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

(declare clear-local-transform)
(declare set-objects-modifiers)
(declare get-ignore-tree)

(defn- set-modifiers
  ([ids]
   (set-modifiers ids nil false))

  ([ids modifiers]
   (set-modifiers ids modifiers false))

  ([ids modifiers ignore-constraints]
   (set-modifiers ids modifiers ignore-constraints false))

  ([ids modifiers ignore-constraints ignore-snap-pixel]
   (us/verify (s/coll-of uuid?) ids)
   (ptk/reify ::set-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [modifiers   (or modifiers (get-in state [:workspace-local :modifiers] {}))
             page-id     (:current-page-id state)
             objects     (wsh/lookup-page-objects state page-id)
             ids         (into #{} (remove #(get-in objects [% :blocked] false)) ids)
             layout      (get state :workspace-layout)
             snap-pixel? (and (not ignore-snap-pixel) (contains? layout :snap-pixel-grid))

             setup-modifiers
             (fn [state id]
               (let [shape (get objects id)]
                 (update state :workspace-modifiers
                         #(set-objects-modifiers % objects shape modifiers ignore-constraints snap-pixel?))))]

         (reduce setup-modifiers state ids))))))

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
                          (mapcat #(cph/get-children objects (:id %)))
                          (concat shapes)
                          (filter #((cpc/editable-attrs (:type %)) :rotation)))

             update-shape
             (fn [modifiers shape]
               (let [rotate-modifiers (gsh/rotation-modifiers shape center angle)]
                 (assoc-in modifiers [(:id shape) :modifiers] rotate-modifiers)))]

         (update state :workspace-modifiers #(reduce update-shape % shapes)))))))

(defn- update-grow-type
  [shape old-shape]
  (let [auto-width? (= :auto-width (:grow-type shape))
        auto-height? (= :auto-height (:grow-type shape))

        changed-width? (not (mth/close? (:width shape) (:width old-shape)))
        changed-height? (not (mth/close? (:height shape) (:height old-shape)))

        change-to-fixed? (or (and auto-width? (or changed-height? changed-width?))
                             (and auto-height? changed-height?))]
    (cond-> shape
      change-to-fixed?
      (assoc :grow-type :fixed))))

(defn- apply-modifiers
  ([ids]
   (apply-modifiers ids nil))

  ([ids {:keys [undo-transation?] :or {undo-transation? true}}]
   (us/verify (s/coll-of uuid?) ids)
   (ptk/reify ::apply-modifiers
     ptk/WatchEvent
     (watch [_ state _]
       (let [objects           (wsh/lookup-page-objects state)
             ids-with-children (into (vec ids) (mapcat #(cph/get-children-ids objects %)) ids)
             object-modifiers  (get state :workspace-modifiers)
             shapes            (map (d/getf objects) ids)
             ignore-tree       (->> (map #(get-ignore-tree object-modifiers objects %) shapes)
                                    (reduce merge {}))]

         (rx/concat
          (if undo-transation?
            (rx/of (dwu/start-undo-transaction))
            (rx/empty))
          (rx/of (dwg/move-frame-guides ids-with-children)
                 (dch/update-shapes
                  ids-with-children
                  (fn [shape]
                    (let [modif (get object-modifiers (:id shape))
                          text-shape? (cph/text-shape? shape)]
                      (-> shape
                          (merge modif)
                          (gsh/transform-shape)
                          (cond-> text-shape?
                            (update-grow-type shape)))))
                  {:reg-objects? true
                   :ignore-tree ignore-tree
                   ;; Attributes that can change in the transform. This way we don't have to check
                   ;; all the attributes
                   :attrs [:selrect
                           :points
                           :x
                           :y
                           :width
                           :height
                           :content
                           :transform
                           :transform-inverse
                           :rotation
                           :position-data
                           :flip-x
                           :flip-y
                           :grow-type]})
                 (clear-local-transform))
          (if undo-transation?
            (rx/of (dwu/commit-undo-transaction))
            (rx/empty))))))))

(defn- check-delta
  "If the shape is a component instance, check its relative position respect the
  root of the component, and see if it changes after applying a transformation."
  [shape root transformed-shape transformed-root objects]
  (let [root
        (cond
          (:component-root? shape)
          shape

          (nil? root)
          (cph/get-root-shape objects shape)

          :else root)

        transformed-root
        (cond
          (:component-root? transformed-shape)
          transformed-shape

          (nil? transformed-root)
          (cph/get-root-shape objects transformed-shape)

          :else transformed-root)

        shape-delta
        (when root
          (gpt/point (- (gsh/left-bound shape) (gsh/left-bound root))
                     (- (gsh/top-bound shape) (gsh/top-bound root))))

        transformed-shape-delta
        (when transformed-root
          (gpt/point (- (gsh/left-bound transformed-shape) (gsh/left-bound transformed-root))
                     (- (gsh/top-bound transformed-shape) (gsh/top-bound transformed-root))))

        ;; There are cases in that the coordinates change slightly (e.g. when
        ;; rounding to pixel, or when recalculating text positions in different
        ;; zoom levels). To take this into account, we ignore movements smaller
        ;; than 1 pixel.
        distance (if (and shape-delta transformed-shape-delta)
                   (gpt/distance-vector shape-delta transformed-shape-delta)
                   (gpt/point 0 0))

        ignore-geometry? (and (< (:x distance) 1) (< (:y distance) 1))]

    [root transformed-root ignore-geometry?]))

(defn set-pixel-precision
  "Adjust modifiers so they adjust to the pixel grid"
  [modifiers shape]

  (if (some? (:resize-transform modifiers))
    ;; If we're working with a rotation we don't handle pixel precision because
    ;; the transformation won't have the precision anyway
    modifiers

    (let [center (gsh/center-shape shape)
          base-bounds (-> (:points shape) (gsh/points->rect))

          raw-bounds
          (-> (gsh/transform-bounds (:points shape) center modifiers)
              (gsh/points->rect))

          flip-x? (neg? (get-in modifiers [:resize-vector :x]))
          flip-y? (or (neg? (get-in modifiers [:resize-vector :y]))
                      (neg? (get-in modifiers [:resize-vector-2 :y])))

          path? (= :path (:type shape))
          vertical-line? (and path? (<= (:width raw-bounds) 0.01))
          horizontal-line? (and path? (<= (:height raw-bounds) 0.01))

          target-width (if vertical-line?
                         (:width raw-bounds)
                         (max 1 (mth/round (:width raw-bounds))))

          target-height (if horizontal-line?
                          (:height raw-bounds)
                          (max 1 (mth/round (:height raw-bounds))))

          target-p (cond-> (gpt/round (gpt/point raw-bounds))
                     flip-x?
                     (update :x + target-width)

                     flip-y?
                     (update :y + target-height))

          ratio-width (/ target-width (:width raw-bounds))
          ratio-height (/ target-height (:height raw-bounds))

          modifiers
          (-> modifiers
              (d/without-nils)
              (d/update-in-when
               [:resize-vector :x] #(* % ratio-width))

              ;; If the resize-vector-2 modifier arrives means the resize-vector
              ;; will only resize on the x axis
              (cond-> (nil? (:resize-vector-2 modifiers))
                (d/update-in-when
                 [:resize-vector :y] #(* % ratio-height)))

              (d/update-in-when
               [:resize-vector-2 :y] #(* % ratio-height)))

          origin (get modifiers :resize-origin)
          origin-2 (get modifiers :resize-origin-2)

          resize-v  (get modifiers :resize-vector)
          resize-v-2  (get modifiers :resize-vector-2)
          displacement  (get modifiers :displacement)

          target-p-inv
          (-> target-p
              (gpt/transform
               (cond-> (gmt/matrix)
                 (some? displacement)
                 (gmt/multiply (gmt/inverse displacement))

                 (and (some? resize-v) (some? origin))
                 (gmt/scale (gpt/inverse resize-v) origin)

                 (and (some? resize-v-2) (some? origin-2))
                 (gmt/scale (gpt/inverse resize-v-2) origin-2))))

          delta-v (gpt/subtract target-p-inv (gpt/point base-bounds))

          modifiers
          (-> modifiers
              (d/update-when :displacement #(gmt/multiply (gmt/translate-matrix delta-v) %))
              (cond-> (nil? (:displacement modifiers))
                (assoc :displacement (gmt/translate-matrix delta-v))))]
      modifiers)))

(defn- set-objects-modifiers
  [modif-tree objects shape modifiers ignore-constraints snap-pixel?]
  (letfn [(set-modifiers-rec
            [modif-tree shape modifiers]

            (let [children (map (d/getf objects) (:shapes shape))
                  transformed-rect (gsh/transform-selrect (:selrect shape) modifiers)

                  set-child
                  (fn [snap-pixel? modif-tree child]
                    (let [child-modifiers (gsh/calc-child-modifiers shape child modifiers ignore-constraints transformed-rect)
                          child-modifiers (cond-> child-modifiers snap-pixel? (set-pixel-precision child))]
                      (cond-> modif-tree
                        (not (gsh/empty-modifiers? child-modifiers))
                        (set-modifiers-rec child child-modifiers))))

                  modif-tree
                  (-> modif-tree
                      (assoc-in [(:id shape) :modifiers] modifiers))

                  resize-modif?
                  (or (:resize-vector modifiers) (:resize-vector-2 modifiers))]

              (reduce (partial set-child (and snap-pixel? resize-modif?)) modif-tree children)))]

    (let [modifiers (cond-> modifiers snap-pixel? (set-pixel-precision shape))]
      (set-modifiers-rec modif-tree shape modifiers))))

(defn- get-ignore-tree
  "Retrieves a map with the flag `ignore-geometry?` given a tree of modifiers"
  ([modif-tree objects shape]
   (get-ignore-tree modif-tree objects shape nil nil {}))

  ([modif-tree objects shape root transformed-root ignore-tree]
   (let [children (map (d/getf objects) (:shapes shape))

         shape-id (:id shape)
         transformed-shape (gsh/transform-shape (merge shape (get modif-tree shape-id)))

         [root transformed-root ignore-geometry?]
         (check-delta shape root transformed-shape transformed-root objects)

         ignore-tree (assoc ignore-tree shape-id ignore-geometry?)

         set-child
         (fn [ignore-tree child]
           (get-ignore-tree modif-tree objects child root transformed-root ignore-tree))]

     (reduce set-child ignore-tree children))))

(defn- clear-local-transform []
  (ptk/reify ::clear-local-transform
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :workspace-modifiers)
          (dissoc ::current-move-selected)))))

;; -- Resize --------------------------------------------------------

(defn start-resize
  "Enter mouse resize mode, until mouse button is released."
  [handler ids shape]
  (letfn [(resize [shape initial layout [point lock? center? point-snap]]
            (let [{:keys [width height]} (:selrect shape)
                  {:keys [rotation]} shape

                  shape-center (gsh/center-shape shape)
                  shape-transform (:transform shape)
                  shape-transform-inverse (:transform-inverse shape)

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
      (watch [_ state stream]
        (let [initial-position @ms/mouse-position
              stoper  (rx/filter ms/mouse-up? stream)
              layout  (:workspace-layout state)
              page-id (:current-page-id state)
              focus   (:workspace-focus-selected state)
              zoom    (get-in state [:workspace-local :zoom] 1)
              objects (wsh/lookup-page-objects state page-id)
              resizing-shapes (map #(get objects %) ids)]
          (rx/concat
           (->> ms/mouse-position
                (rx/with-latest-from ms/mouse-position-shift ms/mouse-position-alt)
                (rx/map normalize-proportion-lock)
                (rx/switch-map (fn [[point _ _ :as current]]
                                 (->> (snap/closest-snap-point page-id resizing-shapes objects layout zoom focus point)
                                      (rx/map #(conj current %)))))
                (rx/mapcat (partial resize shape initial-position layout))
                (rx/take-until stoper))
           (rx/of (apply-modifiers ids)
                  (finish-transform))))))))

(defn update-dimensions
  "Change size of shapes, from the sideber options form.
  Will ignore pixel snap used in the options side panel"
  [ids attr value]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify #{:width :height} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-dimensions
    ptk/UpdateEvent
    (update [_ state]
      (let [objects     (wsh/lookup-page-objects state)
            layout      (get state :workspace-layout)
            snap-pixel? (contains? layout :snap-pixel-grid)

            update-modifiers
            (fn [state id]
              (let [shape     (get objects id)
                    modifiers (gsh/resize-modifiers shape attr value)]
                (-> state
                    (update :workspace-modifiers
                            #(set-objects-modifiers % objects shape modifiers false (and snap-pixel? (int? value)))))))]
        (reduce update-modifiers state ids)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (apply-modifiers ids)))))

(defn change-orientation
  "Change orientation of shapes, from the sidebar options form.
  Will ignore pixel snap used in the options side panel"
  [ids orientation]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify #{:horiz :vert} orientation)
  (ptk/reify ::change-orientation
    ptk/UpdateEvent
    (update [_ state]
      (let [objects     (wsh/lookup-page-objects state)
            layout      (get state :workspace-layout)
            snap-pixel? (contains? layout :snap-pixel-grid)

            update-modifiers
            (fn [state id]
              (let [shape     (get objects id)
                    modifiers (gsh/change-orientation-modifiers shape orientation)]
                (-> state
                    (update :workspace-modifiers
                            #(set-objects-modifiers % objects shape modifiers false snap-pixel?)))))]
        (reduce update-modifiers state ids)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (apply-modifiers ids)))))

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

            calculate-angle
            (fn [pos mod? shift?]
              (let [angle (- (gpt/angle pos group-center) initial-angle)
                    angle (if (neg? angle) (+ 360 angle) angle)
                    angle (if (= angle 360)
                            0
                            angle)
                    angle (if mod?
                            (* (mth/floor (/ angle 45)) 45)
                            angle)
                    angle (if shift?
                            (* (mth/floor (/ angle 15)) 15)
                            angle)]
                angle))]
        (rx/concat
         (->> ms/mouse-position
              (rx/with-latest vector ms/mouse-position-mod)
              (rx/with-latest vector ms/mouse-position-shift)
              (rx/map
               (fn [[[pos mod?] shift?]]
                 (let [delta-angle (calculate-angle pos mod? shift?)]
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
  ([]
   (start-move-selected nil false))

  ([id shift?]
   (ptk/reify ::start-move-selected
     ptk/WatchEvent
     (watch [_ state stream]
       (let [initial  (deref ms/mouse-position)

             stopper  (rx/filter ms/mouse-up? stream)
             zoom    (get-in state [:workspace-local :zoom] 1)

             ;; We toggle the selection so we don't have to wait for the event
             selected
             (cond-> (wsh/lookup-selected state {:omit-blocked? true})
               (some? id)
               (d/toggle-selection id shift?))]

         (when (or (d/not-empty? selected) (some? id))
           (->> ms/mouse-position
                (rx/map #(gpt/to-vec initial %))
                (rx/map #(gpt/length %))
                (rx/filter #(> % (/ 10 zoom)))
                (rx/take 1)
                (rx/with-latest vector ms/mouse-position-alt)
                (rx/mapcat
                 (fn [[_ alt?]]
                   (rx/concat
                    (if (some? id)
                      (rx/of (dws/select-shape id shift?))
                      (rx/empty))

                    (if alt?
                      ;; When alt is down we start a duplicate+move
                      (rx/of (start-move-duplicate initial)
                             (dws/duplicate-selected false))

                      ;; Otherwise just plain old move
                      (rx/of (start-move initial selected))))))
                (rx/take-until stopper))))))))

(defn- start-move-duplicate
  [from-position]
  (ptk/reify ::start-move-duplicate
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :transform] :move)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? ::dws/duplicate-selected))
           (rx/take 1)
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
             focus   (:workspace-focus-selected state)

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
                                 (->> (snap/closest-snap-move page-id shapes objects layout zoom focus pos)
                                      (rx/map #(vector pos %)))))))]
         (if (empty? shapes)
           (rx/of (finish-transform))
           (rx/concat
            (->> position
                 ;; We ask for the snap position but we continue even if the result is not available
                 (rx/with-latest vector snap-delta)
                 ;; We try to use the previous snap so we don't have to wait for the result of the new
                 (rx/map snap/correct-snap-point)
                 (rx/map #(hash-map :displacement (gmt/translate-matrix %)))
                 (rx/map (partial set-modifiers ids))
                 (rx/take-until stopper))

            (rx/of (dwu/start-undo-transaction)
                   (calculate-frame-for-move ids)
                   (apply-modifiers ids {:undo-transation? false})
                   (finish-transform)
                   (dwu/commit-undo-transaction)))))))))

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
        (if (nil? (get state ::current-move-selected))
          (-> state
              (assoc-in [:workspace-local :transform] :move)
              (assoc ::current-move-selected same-event))
          state))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= same-event (get state ::current-move-selected))
          (let [selected (wsh/lookup-selected state {:omit-blocked? true})
                nudge (get-in state [:profile :props :nudge] {:big 10 :small 1})
                move-events (->> stream
                                 (rx/filter (ptk/type? ::move-selected))
                                 (rx/filter #(= direction (deref %))))
                stopper (->> move-events
                             (rx/debounce 100)
                             (rx/take 1))
                scale (if shift? (gpt/point (:big nudge)) (gpt/point (:small nudge)))
                mov-vec (gpt/multiply (get-displacement direction) scale)]

            (rx/concat
             (rx/merge
              (->> move-events
                   (rx/scan #(gpt/add %1 mov-vec) (gpt/point 0 0))
                   (rx/map #(hash-map :displacement (gmt/translate-matrix %)))
                   (rx/map (partial set-modifiers selected))
                   (rx/take-until stopper))
              (rx/of (move-selected direction shift?)))

             (rx/of (apply-modifiers selected)
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
            delta (gpt/subtract pos cpos)
            displ   (gmt/translate-matrix delta)]

        (rx/of (set-modifiers [id] {:displacement displ} false true)
               (apply-modifiers [id]))))))

(defn check-frame-move?
  [target-frame-id objects position shape]

  (let [current-frame (get objects (:frame-id shape))]
    ;; If the current frame contains the point and it's a child of the target
    (and (gsh/has-point? current-frame position)
         (cph/is-child? objects target-frame-id (:id current-frame)))))

(defn- calculate-frame-for-move
  [ids]
  (ptk/reify ::calculate-frame-for-move
    ptk/WatchEvent
    (watch [it state _]
      (let [position @ms/mouse-position
            page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            frame-id (cph/frame-id-by-position objects position)

            moving-shapes (->> ids
                               (cph/clean-loops objects)
                               (keep #(get objects %))
                               (remove (partial check-frame-move? frame-id objects position)))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects)
                        (pcb/change-parent frame-id moving-shapes))]

        (when-not (empty? changes)
          (rx/of (dch/commit-changes changes)
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
