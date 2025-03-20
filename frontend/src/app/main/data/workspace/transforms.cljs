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
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gslf]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.snap :as snap]
   [app.main.streams :as ms]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; -- Helpers --------------------------------------------------------

;; For each of the 8 handlers gives the multiplier for resize
;; for example, right will only grow in the x coordinate and left
;; will grow in the inverse of the x coordinate
(defn get-handler-multiplier
  [handler]
  (case handler
    :right        (gpt/point 1  0)
    :bottom       (gpt/point 0  1)
    :left         (gpt/point -1  0)
    :top          (gpt/point 0 -1)
    :top-right    (gpt/point 1 -1)
    :top-left     (gpt/point -1 -1)
    :bottom-right (gpt/point 1  1)
    :bottom-left  (gpt/point -1  1)))

(defn- get-handler-resize-origin
  "Given a handler, return the coordinate origin for resizes.
   This is the opposite of the handler so for right we want the
   left side as origin of the resize.

   sx, sy => start x/y
   mx, my => middle x/y
   ex, ey => end x/y
  "
  [selrect handler]
  (let [sx     (dm/get-prop selrect :x)
        sy     (dm/get-prop selrect :y)
        width  (dm/get-prop selrect :width)
        height (dm/get-prop selrect :height)
        mx     (+ sx (/ width 2))
        my     (+ sy (/ height 2))
        ex     (+ sx width)
        ey     (+ sy height)]
    (case handler
      :right (gpt/point sx my)
      :bottom (gpt/point mx sy)
      :left (gpt/point ex my)
      :top (gpt/point mx ey)
      :top-right (gpt/point sx ey)
      :top-left (gpt/point ex ey)
      :bottom-right (gpt/point sx sy)
      :bottom-left (gpt/point ex sy))))

(defn- fix-init-point
  "Fix the initial point so the resizes are accurate"
  [initial handler shape]
  (let [selrect (dm/get-prop shape :selrect)
        x       (dm/get-prop selrect :x)
        y       (dm/get-prop selrect :y)
        width   (dm/get-prop selrect :width)
        height  (dm/get-prop selrect :height)]

    (case handler
      :left
      (assoc initial :x x)

      :top
      (assoc initial :y y)

      :top-left
      (-> initial
          (assoc :x x)
          (assoc :y y))

      :bottom-left
      (-> initial
          (assoc :x x)
          (assoc :y (+ y height)))

      :right
      (assoc initial :x (+ x width))

      :top-right
      (-> initial
          (assoc :x (+ x width))
          (assoc :y y))

      :bottom-right
      (-> initial
          (assoc :x (+ x width))
          (assoc :y (+ y height)))

      :bottom
      (assoc initial :y (+ y height)))))

(defn finish-transform []
  (ptk/reify ::finish-transform
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :transform :duplicate-move-started?)
          (dissoc :workspace-selrect-transform)))))

;; -- Resize --------------------------------------------------------

(defn start-resize
  "Enter mouse resize mode, until mouse button is released."
  [handler ids shape]
  (letfn [(resize [shape initial layout [point lock? center? point-snap]]
            (let [selrect  (dm/get-prop shape :selrect)
                  width    (dm/get-prop selrect :width)
                  height   (dm/get-prop selrect :height)
                  rotation (dm/get-prop shape :rotation)

                  shape-center (gsh/shape->center shape)
                  shape-transform (:transform shape)
                  shape-transform-inverse (:transform-inverse shape)

                  rotation (or rotation 0)

                  initial (gmt/transform-point-center initial shape-center shape-transform-inverse)
                  initial (fix-init-point initial handler shape)

                  point (gmt/transform-point-center (if (= rotation 0) point-snap point)
                                                    shape-center shape-transform-inverse)

                  shapev (-> (gpt/point width height))

                  scale-text (contains? layout :scale-text)

                  ;; Force lock if the scale text mode is active
                  lock? (or ^boolean lock?
                            ^boolean scale-text)

                  ;; Difference between the origin point in the
                  ;; coordinate system of the rotation
                  deltav (-> (gpt/to-vec initial point)
                             ;; Vector modifiers depending on the handler
                             (gpt/multiply (get-handler-multiplier handler)))

                  ;; Resize vector
                  scalev (-> (gpt/divide (gpt/add shapev deltav) shapev)
                             (gpt/no-zeros))

                  scalev (if ^boolean lock?
                           (let [v (cond
                                     (or (= handler :right)
                                         (= handler :left))
                                     (dm/get-prop scalev :x)

                                     (or (= handler :top)
                                         (= handler :bottom))
                                     (dm/get-prop scalev :y)

                                     :else
                                     (mth/max (dm/get-prop scalev :x)
                                              (dm/get-prop scalev :y)))]
                             (gpt/point v v))
                           scalev)

                  ;; Resize origin point given the selected handler
                  selrect         (dm/get-prop shape :selrect)
                  handler-origin  (get-handler-resize-origin selrect handler)

                  ;; If we want resize from center, displace the shape
                  ;; so it is still centered after resize.
                  displacement  (when ^boolean center?
                                  (-> shape-center
                                      (gpt/subtract handler-origin)
                                      (gpt/multiply scalev)
                                      (gpt/add handler-origin)
                                      (gpt/subtract shape-center)
                                      (gpt/multiply (gpt/point -1 -1))
                                      (gpt/transform shape-transform)))

                  resize-origin (gmt/transform-point-center handler-origin shape-center shape-transform)
                  resize-origin (if (some? displacement)
                                  (gpt/add resize-origin displacement)
                                  resize-origin)

                  ;; When the horizontal/vertical scale a flex children with auto/fill
                  ;; we change it too fixed
                  set-fix-width?
                  (not (mth/close? (dm/get-prop scalev :x) 1))

                  set-fix-height?
                  (not (mth/close? (dm/get-prop scalev :y) 1))]

              (cond-> (ctm/empty)
                (some? displacement)
                (ctm/move displacement)

                :always
                (ctm/resize scalev resize-origin shape-transform shape-transform-inverse)

                ^boolean set-fix-width?
                (ctm/change-property :layout-item-h-sizing :fix)

                ^boolean set-fix-height?
                (ctm/change-property :layout-item-v-sizing :fix)

                ^boolean scale-text
                (ctm/scale-content (dm/get-prop scalev :x)))))

          ;; Unifies the instantaneous proportion lock modifier
          ;; activated by Shift key and the shapes own proportion
          ;; lock flag that can be activated on element options.
          (normalize-proportion-lock [[point shift? alt?]]
            (let [proportion-lock? (:proportion-lock shape)]
              [point
               (or ^boolean proportion-lock?
                   ^boolean shift?)
               alt?]))]
    (reify
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc-in [:workspace-local :transform] :resize)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [initial-position @ms/mouse-position

              stopper (mse/drag-stopper stream)
              layout  (:workspace-layout state)
              page-id (:current-page-id state)
              focus   (:workspace-focus-selected state)
              zoom    (dm/get-in state [:workspace-local :zoom] 1)
              objects (dsh/lookup-page-objects state page-id)
              shapes  (map (d/getf objects) ids)

              resize-events-stream
              (->> ms/mouse-position
                   (rx/filter some?)
                   (rx/with-latest-from ms/mouse-position-shift ms/mouse-position-alt)
                   (rx/map normalize-proportion-lock)
                   (rx/switch-map
                    (fn [[point _ _ :as current]]
                      (->> (snap/closest-snap-point page-id shapes objects layout zoom focus point)
                           (rx/map #(conj current %)))))
                   (rx/map #(resize shape initial-position layout %))
                   (rx/share))


              modifiers-stream
              (rx/merge
               (->> resize-events-stream
                    (rx/mapcat
                     (fn [modifiers]
                       (let [modif-tree (dwm/create-modif-tree ids modifiers)]
                         (if (features/active-feature? state "render-wasm/v1")
                           (rx/of
                            (dwm/set-selrect-transform modifiers)
                            (dwm/set-wasm-modifiers modif-tree (contains? layout :scale-text)))

                           (rx/of (dwm/set-modifiers modif-tree (contains? layout :scale-text)))))))
                    (rx/take-until stopper))

               ;; The last event we need to use the old method so the elements are correctly positioned until
               ;; all the logic is implemented in wasm
               (if (features/active-feature? state "render-wasm/v1")
                 (->> resize-events-stream
                      (rx/take-until stopper)
                      (rx/last)
                      (rx/map #(dwm/apply-modifiers {:modifiers (dwm/create-modif-tree ids %)
                                                     :ignore-constraints (contains? layout :scale-text)})))
                 (rx/empty)))]

          (rx/concat
           ;; This initial stream waits for some pixels to be move before making the resize
           ;; if you make a click in the border will not make a resize
           (->> ms/mouse-position
                (rx/map #(gpt/to-vec initial-position %))
                (rx/map #(gpt/length %))
                (rx/filter #(> % (/ 10 zoom)))
                (rx/take 1)
                (rx/take-until stopper)
                (rx/mapcat (fn [] modifiers-stream)))

           (rx/of
            (if (features/active-feature? state "render-wasm/v1")
              (dwm/clear-local-transform)
              (dwm/apply-modifiers))
            (finish-transform))))))))

(defn trigger-bounding-box-cloaking
  "Trigger the bounding box cloaking (with default timer of 1sec)

  Used to hide bounding-box of shape after changes in sidebar->measures."
  [ids]
  (dm/assert!
   "expected valid coll of uuids"
   (every? uuid? ids))

  (ptk/reify ::trigger-bounding-box-cloaking
    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/concat
       (rx/of #(assoc-in % [:workspace-local :transform] :move))
       (->> (rx/timer 1000)
            (rx/map (fn []
                      #(assoc-in % [:workspace-local :transform] nil)))
            (rx/take-until
             (rx/filter (ptk/type? ::trigger-bounding-box-cloaking) stream)))))))

(defn update-dimensions
  "Change size of shapes, from the sidebar options form
  (will ignore pixel snap)"
  ([ids attr value] (update-dimensions ids attr value nil))
  ([ids attr value options]
   (assert (number? value))
   (assert (every? uuid? ids)
           "expected valid coll of uuids")
   (assert (contains? #{:width :height} attr)
           "expected valid attr")

   (ptk/reify ::update-dimensions
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id
             (or (get options :page-id)
                 (get state :current-page-id))

             objects
             (dsh/lookup-page-objects state page-id)

             get-modifier
             (fn [shape]
               (ctm/change-dimensions-modifiers shape attr value))

             modif-tree
             (-> (dwm/build-modif-tree ids objects get-modifier)
                 (gm/set-objects-modifiers objects))]

         (rx/of (dwm/apply-modifiers* objects modif-tree nil options)))))))

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
      (let [objects     (dsh/lookup-page-objects state)

            get-modifier
            (fn [shape] (ctm/change-orientation-modifiers shape orientation))

            modif-tree
            (-> (dwm/build-modif-tree ids objects get-modifier)
                (gm/set-objects-modifiers objects))]

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
    (watch [_ state stream]
      (let [stopper         (mse/drag-stopper stream)
            group           (gsh/shapes->rect shapes)
            group-center    (grc/rect->center group)
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
                angle))

            angle-stream
            (->> ms/mouse-position
                 (rx/with-latest-from ms/mouse-position-mod ms/mouse-position-shift)
                 (rx/map
                  (fn [[pos mod? shift?]]
                    (calculate-angle pos mod? shift?)))
                 (rx/share))]
        (rx/concat
         (rx/merge
          (->> angle-stream
               (rx/map
                #(if (features/active-feature? state "render-wasm/v1")
                   (dwm/set-wasm-rotation-modifiers % shapes group-center)
                   (dwm/set-rotation-modifiers % shapes group-center)))
               (rx/take-until stopper))
          (if (features/active-feature? state "render-wasm/v1")
            (->> angle-stream
                 (rx/take-until stopper)
                 (rx/last)
                 (rx/map #(dwm/set-rotation-modifiers % shapes group-center)))
            (rx/empty)))
         (rx/of (dwm/apply-modifiers)
                (finish-transform)))))))

(defn increase-rotation
  "Rotate shapes a fixed angle, from a keyboard action."
  ([ids rotation]
   (increase-rotation ids rotation nil))
  ([ids rotation params & {:as options}]
   (ptk/reify ::increase-rotation
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id (or (:page-id options)
                         (:current-page-id state))
             objects (dsh/lookup-page-objects state page-id)
             shapes  (->> ids (map #(get objects %)))
             options (assoc options :page-id page-id)]
         (rx/concat
          (rx/of (dwm/set-delta-rotation-modifiers rotation shapes (assoc params :page-id page-id)))
          (rx/of (dwm/apply-modifiers options))))))))
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

             stopper (mse/drag-stopper stream {:interrupt? false})
             zoom    (get-in state [:workspace-local :zoom] 1)

             ;; We toggle the selection so we don't have to wait for the event
             selected
             (cond-> (dsh/lookup-selected state {:omit-blocked? true})
               (some? id)
               (d/toggle-selection id shift?))]

         ;; Take the first mouse position and start a move or a duplicate
         (when (or (d/not-empty? selected) (some? id))
           (->> ms/mouse-position
                (rx/map #(gpt/to-vec initial %))
                (rx/map #(gpt/length %))
                (rx/filter #(> % (/ 10 zoom)))
                (rx/take 1)
                (rx/with-latest-from ms/mouse-position-alt)
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

(defn start-move
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
             objects (dsh/lookup-page-objects state page-id)
             selected (dsh/lookup-selected state {:omit-blocked? true})
             ids     (if (nil? ids) selected ids)
             shapes  (into []
                           (comp (map (d/getf objects))
                                 (remove #(let [parent (get objects (:parent-id %))]
                                            (and (ctk/in-component-copy? parent)
                                                 (ctl/any-layout? parent)))))
                           ids)

             duplicate-move-started? (get-in state [:workspace-local :duplicate-move-started?] false)

             stopper (mse/drag-stopper stream)
             layout  (get state :workspace-layout)
             zoom    (get-in state [:workspace-local :zoom] 1)
             focus   (:workspace-focus-selected state)

             exclude-frames
             (into #{}
                   (filter (partial cfh/frame-shape? objects))
                   (cfh/selected-with-children objects selected))

             exclude-frames-siblings
             (into exclude-frames
                   (comp (mapcat (partial cfh/get-siblings-ids objects))
                         (filter (partial ctl/any-layout-immediate-child-id? objects)))
                   selected)

             position (->> ms/mouse-position
                           (rx/map #(gpt/to-vec from-position %)))

             snap-delta
             (rx/concat
              ;; We send the nil first so the stream is not waiting for the first value
              (rx/of nil)
              (->> position
                   ;; FIXME: performance throttle
                   (rx/throttle 20)
                   (rx/switch-map
                    (fn [pos]
                      (->> (snap/closest-snap-move page-id shapes objects layout zoom focus pos)
                           (rx/map #(array pos %)))))))]
         (if (empty? shapes)
           (rx/of (finish-transform))
           (let [move-stream
                 (->> position
                      ;; We ask for the snap position but we continue even if the result is not available
                      (rx/with-latest-from snap-delta)

                      ;; We try to use the previous snap so we don't have to wait for the result of the new
                      (rx/map snap/correct-snap-point)

                      (rx/with-latest-from ms/mouse-position-mod)

                      (rx/map
                       (fn [[move-vector mod?]]
                         (let [position         (gpt/add from-position move-vector)
                               exclude-frames   (if mod? exclude-frames exclude-frames-siblings)
                               target-frame     (ctst/top-nested-frame objects position exclude-frames)
                               [target-frame _] (ctn/find-valid-parent-and-frame-ids target-frame objects shapes)
                               flex-layout?     (ctl/flex-layout? objects target-frame)
                               grid-layout?     (ctl/grid-layout? objects target-frame)
                               drop-index       (when flex-layout? (gslf/get-drop-index target-frame objects position))
                               cell-data        (when (and grid-layout? (not mod?)) (gslg/get-drop-cell target-frame objects position))]
                           (array move-vector target-frame drop-index cell-data))))

                      (rx/take-until stopper))

                 modifiers-stream
                 (->> move-stream
                      (rx/with-latest-from array/conj ms/mouse-position-shift)
                      (rx/map
                       (fn [[move-vector target-frame drop-index cell-data shift?]]
                         (let [x-disp? (> (mth/abs (:x move-vector)) (mth/abs (:y move-vector)))
                               [move-vector snap-ignore-axis]
                               (cond
                                 (and shift? x-disp?)
                                 [(assoc move-vector :y 0) :y]

                                 shift?
                                 [(assoc move-vector :x 0) :x]

                                 :else
                                 [move-vector nil])]
                           [(-> (dwm/create-modif-tree ids (ctm/move-modifiers move-vector))
                                (dwm/build-change-frame-modifiers objects selected target-frame drop-index cell-data))
                            snap-ignore-axis])))
                      (rx/share))]

             (rx/merge
              ;; Temporary modifiers stream
              (->> modifiers-stream
                   (rx/map
                    (fn [[modifiers snap-ignore-axis]]
                      (if (features/active-feature? state "render-wasm/v1")
                        (dwm/set-wasm-modifiers modifiers false false {:snap-ignore-axis snap-ignore-axis})
                        (dwm/set-modifiers modifiers false false {:snap-ignore-axis snap-ignore-axis})))))

              (if (features/active-feature? state "render-wasm/v1")
                (->> modifiers-stream
                     (rx/last)
                     (rx/map
                      (fn [[modifiers snap-ignore-axis]]
                        (dwm/set-modifiers modifiers false false {:snap-ignore-axis snap-ignore-axis}))))
                (rx/empty))

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
                    (fn [[_ target-frame drop-index drop-cell]]
                      (let [undo-id (js/Symbol)]
                        (rx/of (dwu/start-undo-transaction undo-id)
                               (dwm/apply-modifiers {:undo-transation? false})
                               (move-shapes-to-frame ids target-frame drop-index drop-cell)
                               (finish-transform)
                               (dwu/commit-undo-transaction undo-id))))))))))))))

(def valid-directions
  #{:up :down :right :left})

(defn reorder-selected-layout-child
  [direction]
  (ptk/reify ::reorder-layout-child
    ptk/WatchEvent
    (watch [it state _]
      (let [selected (dsh/lookup-selected state {:omit-blocked? true})
            objects (dsh/lookup-page-objects state)
            page-id (:current-page-id state)

            get-move-to-index
            (fn [parent-id position]
              (let [parent (get objects parent-id)]
                (if (or (and (ctl/reverse? parent)
                             (or (= direction :left)
                                 (= direction :up)))
                        (and (not (ctl/reverse? parent))
                             (or (= direction :right)
                                 (= direction :down))))
                  (dec position)
                  (+ position 2))))

            move-flex-children
            (fn [changes parent-id children]
              (->> children
                   ;; Add the position to move the children
                   (map (fn [id]
                          (let [position (cfh/get-position-on-parent objects id)]
                            [id (get-move-to-index parent-id position)])))
                   (sort-by second >)
                   (reduce (fn [changes [child-id index]]
                             (pcb/change-parent changes parent-id [(get objects child-id)] index))
                           changes)))

            move-grid-children
            (fn [changes parent-id children]
              (let [parent (get objects parent-id)

                    key-prop (case direction
                               (:up :down) :row
                               (:right :left) :column)
                    key-comp (case direction
                               (:up :left) <
                               (:down :right) >)

                    {:keys [layout-grid-cells]}
                    (->> children
                         (remove #(ctk/in-component-copy-not-head? (get objects %)))
                         (keep #(ctl/get-cell-by-shape-id parent %))
                         (sort-by key-prop key-comp)
                         (reduce (fn [parent {:keys [id row column row-span column-span]}]
                                   (let [[next-row next-column]
                                         (case direction
                                           :up    [(dec row) column]
                                           :right [row (+ column column-span)]
                                           :down  [(+ row row-span) column]
                                           :left  [row (dec column)])
                                         next-cell (ctl/get-cell-by-position parent next-row next-column)]
                                     (cond-> parent
                                       (some? next-cell)
                                       (ctl/swap-shapes id (:id next-cell)))))
                                 parent))]
                (-> changes
                    (pcb/update-shapes
                     [(:id parent)]
                     (fn [shape]
                       (-> shape
                           (assoc :layout-grid-cells layout-grid-cells)
                           ;; We want the previous objects value
                           (ctl/assign-cells objects))))
                    (pcb/reorder-grid-children [(:id parent)]))))

            changes
            (->> selected
                 (group-by #(dm/get-in objects [% :parent-id]))
                 (reduce
                  (fn [changes [parent-id children]]
                    (cond-> changes
                      (ctl/flex-layout? objects parent-id)
                      (move-flex-children parent-id children)

                      (ctl/grid-layout? objects parent-id)
                      (move-grid-children parent-id children)))

                  (-> (pcb/empty-changes it page-id)
                      (pcb/with-objects objects))))

            undo-id (js/Symbol)]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (ptk/data-event :layout/update {:ids selected})
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
          (let [selected (dsh/lookup-selected state {:omit-blocked? true})
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
                                           (rx/filter kbd/keyboard-event?)
                                           (rx/filter kbd/key-up-event?)
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
      (let [objects (dsh/lookup-page-objects state)
            selected (dsh/lookup-selected state {:omit-blocked? true})
            selected-shapes (->> selected (map (d/getf objects)))]
        (if (every? #(and (ctl/any-layout-immediate-child? objects %)
                          (not (ctl/position-absolute? %)))
                    selected-shapes)
          (rx/of (reorder-selected-layout-child direction))
          (rx/of (nudge-selected-shapes direction shift?)))))))

(defn- calculate-delta
  [position bbox relative-to]
  (let [current  (gpt/point (:x bbox) (:y bbox))
        position (gpt/point (or (some-> (:x position) (+ (dm/get-prop relative-to :x)))
                                (:x bbox))
                            (or (some-> (:y position) (+ (dm/get-prop relative-to :y)))
                                (:y bbox)))]
    (gpt/subtract position current)))

(defn update-position
  "Move shapes to a new position. It will resolve to the current frame
  of the shape, unless given the absolute option. In this case it will
  resolve to the root frame of the page.

  The position is a map that can have a partial position (it means it
  can receive {:x 10}."
  ([id position] (update-position id position nil))
  ([id position options]
   (assert (uuid? id) "expected a valid uuid for `id`")
   (assert (map? position) "expected a valid map for `position`")

   (ptk/reify ::update-position
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id   (or (get options :page-id)
                           (get state :current-page-id))
             objects   (dsh/lookup-page-objects state page-id)
             shape     (get objects id)

             bbox      (-> shape :points grc/points->rect)
             frame     (if (:absolute? options)
                         (cfh/get-frame objects)
                         (cfh/get-parent-frame objects shape))

             delta     (calculate-delta position bbox frame)
             modifiers (dwm/create-modif-tree [id] (ctm/move-modifiers delta))]

         (rx/of (dwm/apply-modifiers {:modifiers modifiers
                                      :page-id page-id
                                      :ignore-constraints false
                                      :ignore-touched (:ignore-touched options)
                                      :ignore-snap-pixel true})))))))

(defn position-shapes
  [shapes]
  (ptk/reify ::position-shapes
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            shapes  (d/index-by :id shapes)

            modif-tree
            (dwm/build-modif-tree
             (keys shapes)
             objects
             (fn [cshape]
               (let [oshape (get shapes (:id cshape))
                     cpos   (-> cshape :points first gpt/point)
                     opos   (-> oshape :points first gpt/point)]
                 (ctm/move-modifiers (gpt/subtract opos cpos)))))]

        (rx/of (dwm/apply-modifiers {:modifiers modif-tree
                                     :ignore-constraints false
                                     :ignore-snap-pixel true}))))))

(defn- cleanup-invalid-moving-shapes [ids objects frame-id]
  (let [lookup   (d/getf objects)
        frame   (get objects frame-id)
        layout?  (:layout frame)

        shapes (->> ids
                    set
                    (cfh/clean-loops objects)
                    (keep lookup)
                    ;;remove shapes inside copies, because we can't change the structure of copies
                    (remove #(ctk/in-component-copy? (get objects (:parent-id %))))
                    ;; remove absolute shapes that won't change parent
                    (remove #(and (ctl/position-absolute? %) (= frame-id (:parent-id %)))))

        shapes
        (cond->> shapes
          (not layout?)
          (remove #(= (:frame-id %) frame-id))

          layout?
          (remove #(and (= (:frame-id %) frame-id)
                        (not= (:parent-id %) frame-id))))]
    (map :id shapes)))

(defn move-shapes-to-frame
  [ids frame-id drop-index cell]
  (ptk/reify ::move-shapes-to-frame
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            data    (dsh/lookup-file-data state)
            ids     (cleanup-invalid-moving-shapes ids objects frame-id)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-page-id page-id)
                        (pcb/with-objects objects)
                        (pcb/with-library-data data)
                        (cls/generate-relocate
                         frame-id
                         drop-index
                         ids
                         :cell cell))]

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

(defn flip-horizontal-selected
  ([]
   (flip-horizontal-selected nil))
  ([ids]
   (ptk/reify ::flip-horizontal-selected
     ptk/WatchEvent
     (watch [_ state _]
       (let [objects   (dsh/lookup-page-objects state)
             selected  (or ids (dsh/lookup-selected state {:omit-blocked? true}))
             shapes    (->> selected
                            (map (d/getf objects))
                            (remove ctk/is-variant-container?))
             selected  (->> shapes (map :id))
             selrect   (gsh/shapes->rect shapes)
             center    (grc/rect->center selrect)
             modifiers (dwm/create-modif-tree selected (ctm/resize-modifiers (gpt/point -1.0 1.0) center))]
         (rx/of (dwm/apply-modifiers {:modifiers modifiers :ignore-snap-pixel true})))))))

(defn flip-vertical-selected
  ([]
   (flip-vertical-selected nil))
  ([ids]
   (ptk/reify ::flip-vertical-selected
     ptk/WatchEvent
     (watch [_ state _]
       (let [objects   (dsh/lookup-page-objects state)
             selected  (or ids (dsh/lookup-selected state {:omit-blocked? true}))
             shapes    (->> selected
                            (map #(get objects %))
                            (remove ctk/is-variant-container?))
             selected  (->> shapes (map :id))
             selrect   (gsh/shapes->rect shapes)
             center    (grc/rect->center selrect)
             modifiers (dwm/create-modif-tree selected (ctm/resize-modifiers (gpt/point 1.0 -1.0) center))]
         (rx/of (dwm/apply-modifiers {:modifiers modifiers :ignore-snap-pixel true})))))))

(defn fit-layout-modifiers
  [objects frame]
  ;; Set temporaly the auto flag and calculate a reflow to resize and position
  (let [objects
        (-> objects
            (assoc-in [(:id frame) :layout-item-h-sizing] :auto)
            (assoc-in [(:id frame) :layout-item-v-sizing] :auto))]
    (gm/set-objects-modifiers {(:id frame) {:modifiers (ctm/reflow-modifiers)}} objects)))

(defn selected-fit-content
  []
  (ptk/reify ::selected-fit-content
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects    (dsh/lookup-page-objects state)
            selected   (dsh/lookup-selected state)
            undo-group (uuid/next)

            modifiers
            (->> selected
                 (map (d/getf objects))
                 (filter cfh/frame-shape?)
                 (reduce
                  (fn [modifiers frame]
                    (if (ctl/any-layout? frame)
                      (merge modifiers (fit-layout-modifiers objects frame))
                      (let [new-modif (gsh/fit-frame-modifiers objects frame)]
                        (cond-> modifiers
                          (some? new-modif)
                          (assoc (:id frame) {:modifiers new-modif})))))
                  {}))]
        (rx/of (dwm/apply-modifiers {:modifiers modifiers :undo-group undo-group}))))))
