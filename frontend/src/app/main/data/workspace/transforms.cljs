;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.transforms
  "Events related with shapes transformations"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gslf]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.math :as mth]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [app.util.dom :as dom]
   [beicon.core :as rx]
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
      (update state :workspace-local dissoc :transform :duplicate-move-started? false))))


;; -- Resize --------------------------------------------------------

(defn start-resize
  "Enter mouse resize mode, until mouse button is released."
  [handler ids shape]
  (letfn [(resize
           [shape initial layout [point lock? center? point-snap]]
            (let [{:keys [width height]} (:selrect shape)
                  {:keys [rotation]} shape

                  shape-center (gsh/center-shape shape)
                  shape-transform (:transform shape)
                  shape-transform-inverse (:transform-inverse shape)

                  rotation (or rotation 0)

                  initial (gmt/transform-point-center initial shape-center shape-transform-inverse)
                  initial (fix-init-point initial handler shape)

                  point (gmt/transform-point-center (if (= rotation 0) point-snap point)
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
                  scalev (-> (gpt/divide (gpt/add shapev deltav) shapev)
                             (gpt/no-zeros))

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
                  (cond-> (gmt/transform-point-center handler-origin shape-center shape-transform)
                    (some? displacement)
                    (gpt/add displacement))

                  ;; When the horizontal/vertical scale a flex children with auto/fill
                  ;; we change it too fixed
                  set-fix-width?
                  (not (mth/close? (:x scalev) 1))

                  set-fix-height?
                  (not (mth/close? (:y scalev) 1))

                  modifiers
                  (-> (ctm/empty)

                      (cond-> displacement
                        (ctm/move displacement))

                      (ctm/resize scalev resize-origin shape-transform shape-transform-inverse)

                      (cond-> set-fix-width?
                        (ctm/change-property :layout-item-h-sizing :fix))

                      (cond-> set-fix-height?
                        (ctm/change-property :layout-item-v-sizing :fix))

                      (cond-> scale-text
                        (ctm/scale-content (:x scalev))))

                  modif-tree (dwm/create-modif-tree ids modifiers)]
              (rx/of (dwm/set-modifiers modif-tree scale-text))))

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
              stopper (rx/filter ms/mouse-up? stream)
              layout  (:workspace-layout state)
              page-id (:current-page-id state)
              focus   (:workspace-focus-selected state)
              zoom    (get-in state [:workspace-local :zoom] 1)
              objects (wsh/lookup-page-objects state page-id)
              resizing-shapes (map #(get objects %) ids)]

          (rx/concat
           (->> ms/mouse-position
                (rx/filter some?)
                (rx/with-latest-from ms/mouse-position-shift ms/mouse-position-alt)
                (rx/map normalize-proportion-lock)
                (rx/switch-map (fn [[point _ _ :as current]]
                                 (->> (snap/closest-snap-point page-id resizing-shapes objects layout zoom focus point)
                                      (rx/map #(conj current %)))))
                (rx/mapcat (partial resize shape initial-position layout))
                (rx/take-until stopper))
           (rx/of (dwm/apply-modifiers)
                  (finish-transform))))))))

(defn update-dimensions
  "Change size of shapes, from the sideber options form.
  Will ignore pixel snap used in the options side panel"
  [ids attr value]
  (dm/assert! (number? value))
  (dm/assert!
   "expected valid coll of uuids"
   (every? uuid? ids))
  (dm/assert!
   "expected valid attr"
   (contains? #{:width :height} attr))
  (ptk/reify ::update-dimensions
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)
            get-modifier
            (fn [shape] (ctm/change-dimensions-modifiers shape attr value))

            modif-tree
            (-> (dwm/build-modif-tree ids objects get-modifier)
                (gsh/set-objects-modifiers objects))]

        (assoc state :workspace-modifiers modif-tree)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwm/apply-modifiers)))))

(defn change-orientation
  "Change orientation of shapes, from the sidebar options form.
  Will ignore pixel snap used in the options side panel"
  [ids orientation]
  (dm/assert!
   "expected valid coll of uuids"
   (every? uuid? ids))
  (dm/assert!
   "expected valid orientation"
   (contains? #{:horiz :vert} orientation))

  (ptk/reify ::change-orientation
    ptk/UpdateEvent
    (update [_ state]
      (let [objects     (wsh/lookup-page-objects state)

            get-modifier
            (fn [shape] (ctm/change-orientation-modifiers shape orientation))

            modif-tree
            (-> (dwm/build-modif-tree ids objects get-modifier)
                (gsh/set-objects-modifiers objects))]

        (assoc state :workspace-modifiers modif-tree)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwm/apply-modifiers)))))

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
                   (dwm/set-rotation-modifiers delta-angle shapes group-center))))
              (rx/take-until stoper))
         (rx/of (dwm/apply-modifiers)
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
                             (dwm/set-rotation-modifiers delta [shape])))]
        (rx/concat
         (rx/from (->> ids (map #(get objects %)) (map rotate-shape)))
         (rx/of (dwm/apply-modifiers)))))))


;; -- Move ----------------------------------------------------------

(declare start-move)
(declare start-move-duplicate)
(declare move-shapes-to-frame)
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
                             (dws/duplicate-selected false true))

                      ;; Otherwise just plain old move
                      (rx/of (start-move initial selected))))))
                (rx/take-until stopper))))))))

(defn- start-move-duplicate
  [from-position]
  (ptk/reify ::start-move-duplicate
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :transform] :move)
          (assoc-in [:workspace-local :duplicate-move-started?] true)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? ::dws/duplicate-selected))
           (rx/take 1)
           (rx/map #(start-move from-position))))))

(defn set-ghost-displacement
  [move-vector]
  (ptk/reify ::set-ghost-displacement
    ptk/EffectEvent
    (effect [_ _ _]
      (when-let [node (dom/get-element-by-class "ghost-outline")]
        (dom/set-property! node "transform" (gmt/translate-matrix move-vector))))))

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
             duplicate-move-started? (get-in state [:workspace-local :duplicate-move-started?] false)
             stopper (rx/filter ms/mouse-up? stream)
             layout  (get state :workspace-layout)
             zoom    (get-in state [:workspace-local :zoom] 1)
             focus   (:workspace-focus-selected state)

             exclude-frames
             (into #{}
                   (filter (partial cph/frame-shape? objects))
                   (cph/selected-with-children objects selected))

             exclude-frames-siblings
             (into exclude-frames
                   (comp (mapcat (partial cph/get-siblings-ids objects))
                         (filter (partial ctl/any-layout-immediate-child-id? objects)))
                   selected)

             position (->> ms/mouse-position
                           (rx/map #(gpt/to-vec from-position %)))

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
           (let [move-stream
                 (->> position
                      ;; We ask for the snap position but we continue even if the result is not available
                      (rx/with-latest vector snap-delta)

                      ;; We try to use the previous snap so we don't have to wait for the result of the new
                      (rx/map snap/correct-snap-point)

                      (rx/with-latest vector ms/mouse-position-mod)

                      (rx/map
                       (fn [[move-vector mod?]]
                         (let [position       (gpt/add from-position move-vector)
                               exclude-frames (if mod? exclude-frames exclude-frames-siblings)
                               target-frame   (ctst/top-nested-frame objects position exclude-frames)
                               flex-layout?   (ctl/flex-layout? objects target-frame)
                               grid-layout?   (ctl/grid-layout? objects target-frame)
                               drop-index     (cond
                                                flex-layout? (gslf/get-drop-index target-frame objects position)
                                                grid-layout? (gslg/get-drop-index target-frame objects position))]
                           [move-vector target-frame drop-index])))

                      (rx/take-until stopper))]

             (rx/merge
              ;; Temporary modifiers stream
              (->> move-stream
                   (rx/with-latest-from ms/mouse-position-shift)
                   (rx/map
                    (fn [[[move-vector target-frame drop-index] shift?]]
                      (let [x-disp? (> (mth/abs (:x move-vector)) (mth/abs (:y move-vector)))
                            [move-vector snap-ignore-axis]
                            (cond
                              (and shift? x-disp?)
                              [(assoc move-vector :y 0) :y]

                              shift?
                              [(assoc move-vector :x 0) :x]

                              :else
                              [move-vector nil])]

                        (-> (dwm/create-modif-tree ids (ctm/move-modifiers move-vector))
                            (dwm/build-change-frame-modifiers objects selected target-frame drop-index)
                            (dwm/set-modifiers false false {:snap-ignore-axis snap-ignore-axis}))))))

              (->> move-stream
                      (rx/with-latest-from ms/mouse-position-alt)
                      (rx/filter (fn [[_ alt?]] alt?))
                      (rx/take 1)
                      (rx/mapcat
                        (fn [[_ alt?]]
                          (if (and (not duplicate-move-started?) alt?)
                            (rx/of (start-move-duplicate from-position)
                                   (dws/duplicate-selected false true))
                          (rx/empty)))))

              (->> move-stream
                   (rx/map (comp set-ghost-displacement first)))

              ;; Last event will write the modifiers creating the changes
              (->> move-stream
                   (rx/last)
                   (rx/mapcat
                    (fn [[_ target-frame drop-index]]
                      (let [undo-id (js/Symbol)]
                        (rx/of (dwu/start-undo-transaction undo-id)
                               (move-shapes-to-frame ids target-frame drop-index)
                               (dwm/apply-modifiers {:undo-transation? false})
                               (finish-transform)
                               (dwu/commit-undo-transaction undo-id))))))))))))))

(def valid-directions
  #{:up :down :right :left})

(defn reorder-selected-layout-child
  [direction]
  (ptk/reify ::reorder-layout-child
    ptk/WatchEvent
    (watch [it state _]
      (let [selected (wsh/lookup-selected state {:omit-blocked? true})
            objects (wsh/lookup-page-objects state)
            page-id (:current-page-id state)

            get-new-position
            (fn [parent-id position]
              (let [parent (get objects parent-id)]
                (cond
                  (ctl/flex-layout? parent)
                  (if (or
                       (and (ctl/reverse? parent)
                            (or (= direction :left)
                                (= direction :up)))
                       (and (not (ctl/reverse? parent))
                            (or (= direction :right)
                                (= direction :down))))
                    (dec position)
                    (+ position 2))

                  ;; TODO: GRID
                  (ctl/grid-layout? parent)
                  nil
                  )))

            add-children-position
            (fn [[parent-id children]]
              (let [children+position
                    (->> children
                         (keep #(let [new-position (get-new-position
                                                    parent-id
                                                    (cph/get-position-on-parent objects %))]
                                  (when new-position
                                    (vector % new-position))))
                         (sort-by second >))]
                [parent-id children+position]))

            change-parents-and-position
            (->> selected
                 (group-by #(dm/get-in objects [% :parent-id]))
                 (map add-children-position)
                 (into {}))

            changes
            (->> change-parents-and-position
                 (reduce
                  (fn [changes [parent-id children]]
                    (->> children
                         (reduce
                          (fn [changes [child-id index]]
                            (pcb/change-parent changes parent-id
                                               [(get objects child-id)]
                                               index))
                          changes)))
                  (-> (pcb/empty-changes it page-id)
                      (pcb/with-objects objects))))
            undo-id (js/Symbol)]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (ptk/data-event :layout/update selected)
         (dwu/commit-undo-transaction undo-id))))))

(defn nudge-selected-shapes
  "Move shapes a fixed increment in one direction, from a keyboard action."
  [direction shift?]

  (let [same-event (js/Symbol "same-event")]
    (ptk/reify ::nudge-selected-shapes
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
                                 (rx/filter (ptk/type? ::nudge-selected-shapes))
                                 (rx/filter #(= direction (deref %))))

                stopper
                (->> move-events
                     ;; We stop when there's been 1s without movement or after 250ms after a key-up
                     (rx/switch-map #(rx/merge
                                      (rx/timer 1000)
                                      (->> stream
                                           (rx/filter ms/key-up?)
                                           (rx/delay 250))))
                     (rx/take 1))

                scale (if shift? (gpt/point (or (:big nudge) 10)) (gpt/point (or (:small nudge) 1)))
                mov-vec (gpt/multiply (get-displacement direction) scale)]

            (rx/concat
             (rx/merge
              (->> move-events
                   (rx/scan #(gpt/add %1 mov-vec) (gpt/point 0 0))
                   (rx/map #(dwm/create-modif-tree selected (ctm/move-modifiers %)))
                   (rx/map #(dwm/set-modifiers % false true))
                   (rx/take-until stopper))
              (rx/of (nudge-selected-shapes direction shift?)))

             (rx/of (dwm/apply-modifiers)
                    (finish-transform))))
          (rx/empty))))))

(defn move-selected
  "Move shapes a fixed increment in one direction, from a keyboard action."
  [direction shift?]
  (dm/assert! (contains? valid-directions direction))
  (dm/assert! (boolean? shift?))

  (ptk/reify ::move-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            selected (wsh/lookup-selected state {:omit-blocked? true})
            selected-shapes (->> selected (map (d/getf objects)))]
        (if (every? #(and (ctl/any-layout-immediate-child? objects %)
                          (not (ctl/layout-absolute? %)))
                    selected-shapes)
          (rx/of (reorder-selected-layout-child direction))
          (rx/of (nudge-selected-shapes direction shift?)))))))

(defn update-position
  "Move shapes to a new position, from the sidebar options form."
  [id position]
  (js/console.log "DEBUG" (pr-str position))
  (dm/assert! (uuid? id))

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

            modif-tree (dwm/create-modif-tree [id] (ctm/move-modifiers delta))]

        (rx/of (dwm/set-modifiers modif-tree false true)
               (dwm/apply-modifiers))))))

(defn- move-shapes-to-frame
  [ids frame-id drop-index]
  (ptk/reify ::move-shapes-to-frame
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            layout?  (get-in objects [frame-id :layout])
            lookup   (d/getf objects)

            shapes (->> ids (cph/clean-loops objects) (keep lookup))

            moving-shapes
            (cond->> shapes
              (not layout?)
              (remove #(= (:frame-id %) frame-id))

              layout?
              (remove #(and (= (:frame-id %) frame-id)
                            (not= (:parent-id %) frame-id))))

            ordered-indexes (cph/order-by-indexed-shapes objects (map :id moving-shapes))
            moving-shapes (map (d/getf objects) ordered-indexes)

            all-parents
            (reduce (fn [res id]
                      (into res (cph/get-parent-ids objects id)))
                    (d/ordered-set)
                    ids)

            find-all-empty-parents
            (fn recursive-find-empty-parents [empty-parents]
              (let [all-ids   (into empty-parents ids)
                    contains? (partial contains? all-ids)
                    xform     (comp (map lookup)
                                    (filter cph/group-shape?)
                                    (remove #(->> (:shapes %) (remove contains?) seq))
                                    (map :id))
                    parents   (into #{} xform all-parents)]
                (if (= empty-parents parents)
                  empty-parents
                  (recursive-find-empty-parents parents))))

            empty-parents
            ;; Any empty parent whose children are moved to another frame should be deleted
            (if (empty? moving-shapes)
              #{}
              (into (d/ordered-set) (find-all-empty-parents #{})))

            ;; Not move absolute shapes that won't change parent
            moving-shapes
            (->> moving-shapes
                 (remove (fn [shape]
                           (and (ctl/layout-absolute? shape)
                                (= frame-id (:parent-id shape))))))
            moving-shapes-ids
            (map :id moving-shapes)

            changes
            (-> (pcb/empty-changes it page-id)
                (pcb/with-objects objects)
                ;; Remove layout-item properties when moving a shape outside a layout
                (cond-> (not (ctl/any-layout? objects frame-id))
                  (pcb/update-shapes moving-shapes-ids ctl/remove-layout-item-data))
                (pcb/update-shapes moving-shapes-ids #(cond-> % (cph/frame-shape? %) (assoc :hide-in-viewer true)))
                (pcb/change-parent frame-id moving-shapes drop-index)
                (pcb/remove-objects empty-parents))]

        (when (and (some? frame-id) (d/not-empty? changes))
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
      (let [objects   (wsh/lookup-page-objects state)
            selected  (wsh/lookup-selected state {:omit-blocked? true})
            shapes    (map #(get objects %) selected)
            selrect   (gsh/selection-rect shapes)
            center    (gsh/center-selrect selrect)
            modifiers (dwm/create-modif-tree selected (ctm/resize-modifiers (gpt/point -1.0 1.0) center))]
        (rx/of (dwm/apply-modifiers {:modifiers modifiers}))))))

(defn flip-vertical-selected []
  (ptk/reify ::flip-vertical-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects   (wsh/lookup-page-objects state)
            selected  (wsh/lookup-selected state {:omit-blocked? true})
            shapes    (map #(get objects %) selected)
            selrect   (gsh/selection-rect shapes)
            center    (gsh/center-selrect selrect)
            modifiers (dwm/create-modif-tree selected (ctm/resize-modifiers (gpt/point 1.0 -1.0) center))]
        (rx/of (dwm/apply-modifiers {:modifiers modifiers}))))))
