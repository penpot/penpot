;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.transforms
  "Events related with shapes transformations"
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.refs :as refs]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; -- Declarations

(declare set-modifiers)
(declare set-rotation)
(declare apply-modifiers)

;; -- Helpers

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

(defn finish-transform [state]
  (update state :workspace-local dissoc :transform))

;; -- RESIZE
(defn start-resize
  [handler initial ids shape]
  (letfn [(resize [shape initial resizing-shapes [point lock? point-snap]]
            (let [{:keys [width height]} (:selrect shape)
                  {:keys [rotation]} shape
                  shapev (-> (gpt/point width height))

                  ;; Vector modifiers depending on the handler
                  handler-modif (let [[x y] (handler-modifiers handler)] (gpt/point x y))

                  ;; Difference between the origin point in the coordinate system of the rotation
                  deltav (-> (gpt/to-vec initial (if (= rotation 0) point-snap point))
                             (gpt/transform (gmt/rotate-matrix (- rotation)))
                             (gpt/multiply handler-modif))

                  ;; Resize vector
                  scalev (gpt/divide (gpt/add shapev deltav) shapev)

                  scalev (if lock?
                           (let [v (cond
                                     (#{:right :left} handler) (:x scalev)
                                     (#{:top :bottom} handler) (:y scalev)
                                     :else (max (:x scalev) (:y scalev)))]
                             (gpt/point v v))

                           scalev)

                  shape-transform (:transform shape (gmt/matrix))
                  shape-transform-inverse (:transform-inverse shape (gmt/matrix))

                  shape-center (gsh/center-shape shape)

                  ;; Resize origin point given the selected handler
                  origin  (-> (handler-resize-origin (:selrect shape) handler)
                              (gsh/transform-point-center shape-center shape-transform))]

              (rx/of (set-modifiers ids
                                    {:resize-vector scalev
                                     :resize-origin origin
                                     :resize-transform shape-transform
                                     :resize-transform-inverse shape-transform-inverse}
                                    false))))

          ;; Unifies the instantaneous proportion lock modifier
          ;; activated by Shift key and the shapes own proportion
          ;; lock flag that can be activated on element options.
          (normalize-proportion-lock [[point shift?]]
            (let [proportion-lock? (:proportion-lock shape)]
              [point (or proportion-lock? shift?)]))]
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
              zoom    (get-in state [:workspace-local :zoom] 1)
              objects (dwc/lookup-page-objects state page-id)
              resizing-shapes (map #(get objects %) ids)
              text-shapes-ids (->> resizing-shapes
                                   (filter #(= :text (:type %)))
                                   (map :id))]
          (rx/concat
           (rx/of (dwc/update-shapes text-shapes-ids #(assoc % :grow-type :fixed)))
           (->> ms/mouse-position
                (rx/with-latest vector ms/mouse-position-shift)
                (rx/map normalize-proportion-lock)
                (rx/switch-map (fn [[point :as current]]
                               (->> (snap/closest-snap-point page-id resizing-shapes layout zoom point)
                                    (rx/map #(conj current %)))))
                (rx/mapcat (partial resize shape initial-position resizing-shapes))
                (rx/take-until stoper))
           (rx/of (apply-modifiers ids)
                  finish-transform)))))))


(defn start-rotate
  [shapes]
  (ptk/reify ::start-rotate
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :transform] :rotate)))

    ptk/WatchEvent
    (watch [_ state stream]
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
                          (set-rotation delta-angle shapes group-center))))
              (rx/take-until stoper))
         (rx/of (apply-modifiers (map :id shapes))
                finish-transform))))))

;; -- MOVE

(declare start-move)
(declare start-move-duplicate)

(defn start-move-selected
  []
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [initial  (deref ms/mouse-position)
            selected (get-in state [:workspace-local :selected])
            stopper  (rx/filter ms/mouse-up? stream)]
        (->> ms/mouse-position
             (rx/take-until stopper)
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
                         dws/duplicate-selected)
                  ;; Otherwise just plain old move
                  (rx/of (start-move initial selected))))))))))

(defn start-move-duplicate [from-position]
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter (ptk/type? ::dws/duplicate-selected))
           (rx/first)
           (rx/map #(start-move from-position))))))

(defn calculate-frame-for-move [ids]
  (ptk/reify ::calculate-frame-for-move
    ptk/WatchEvent
    (watch [_ state stream]
      (let [position @ms/mouse-position
            page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            frame-id (cp/frame-id-by-position objects position)

            moving-shapes (->> ids
                               (map #(get objects %))
                               (remove #(= (:frame-id %) frame-id)))

            rch [{:type :mov-objects
                  :page-id page-id
                  :parent-id frame-id
                  :shapes (mapv :id moving-shapes)}]

            moving-shapes-by-frame-id (group-by :frame-id moving-shapes)

            uch (->> moving-shapes-by-frame-id
                     (mapv (fn [[frame-id shapes]]
                             {:type :mov-objects
                              :page-id page-id
                              :parent-id frame-id
                              :shapes (mapv :id shapes)})))]

        (when-not (empty? rch)
          (rx/of dwc/pop-undo-into-transaction
                 (dwc/commit-changes rch uch {:commit-local? true})
                 (dwc/commit-undo-transaction)
                 (dwc/expand-collapse frame-id)))))))

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
             objects (dwc/lookup-page-objects state page-id)
             ids     (if (nil? ids) (get-in state [:workspace-local :selected]) ids)
             shapes  (mapv #(get objects %) ids)
             stopper (rx/filter ms/mouse-up? stream)
             layout  (get state :workspace-layout)
             zoom    (get-in state [:workspace-local :zoom] 1)


             position (->> ms/mouse-position
                           (rx/take-until stopper)
                           (rx/map #(gpt/to-vec from-position %)))

             snap-delta (->> position
                             (rx/switch-map #(snap/closest-snap-move page-id shapes objects layout zoom %)))]
         (if (empty? shapes)
           (rx/empty)
           (rx/concat
            (->> snap-delta
                 (rx/with-latest vector position)
                 (rx/map (fn [[delta pos]] (-> (gpt/add pos delta) (gpt/round 0))))
                 (rx/map gmt/translate-matrix)
                 (rx/map #(fn [state] (assoc-in state [:workspace-local :modifiers] {:displacement %}))))

            (rx/of (set-modifiers ids)
                   (apply-modifiers ids)
                   (calculate-frame-for-move ids)
                   (fn [state] (update state :workspace-local dissoc :modifiers))
                   finish-transform))))))))

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
  [direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(s/def ::direction #{:up :down :right :left})

(defn move-selected
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
          (let [selected (get-in state [:workspace-local :selected])
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
                   (rx/map gmt/translate-matrix)
                   (rx/map #(fn [state] (assoc-in state [:workspace-local :modifiers] {:displacement %}))))
              (rx/of (move-selected direction shift?)))

             (rx/of (set-modifiers selected)
                    (apply-modifiers selected)
                    (fn [state] (-> state
                                    (update :workspace-local dissoc :modifiers)
                                    (update :workspace-local dissoc :current-move-selected)))
                    finish-transform)))
            (rx/empty))))))


;; -- Apply modifiers

(defn set-modifiers
  ([ids] (set-modifiers ids nil true))
  ([ids modifiers] (set-modifiers ids modifiers true))
  ([ids modifiers recurse-frames?]
   (us/verify (s/coll-of uuid?) ids)
   (ptk/reify ::set-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [modifiers (or modifiers (get-in state [:workspace-local :modifiers] {}))
             page-id (:current-page-id state)
             objects (dwc/lookup-page-objects state page-id)

             not-frame-id?
             (fn [shape-id]
               (let [shape (get objects shape-id)]
                 (or recurse-frames? (not (= :frame (:type shape))))))

             ;; For each shape updates the modifiers given as arguments
             update-shape
             (fn [objects shape-id]
               (update-in objects [shape-id :modifiers] #(merge % modifiers)))

             ;; ID's + Children but remove frame children if the flag is set to false
             ids-with-children (concat ids (mapcat #(cp/get-children % objects)
                                                   (filter not-frame-id? ids)))]

         (d/update-in-when state [:workspace-data :pages-index page-id :objects]
                           #(reduce update-shape % ids-with-children)))))))


;; Set-rotation is custom because applies different modifiers to each
;; shape adjusting their position.

(defn set-rotation
  ([delta-rotation shapes]
   (set-rotation delta-rotation shapes (-> shapes gsh/selection-rect gsh/center-selrect)))

  ([delta-rotation shapes center]
   (letfn [(rotate-shape [objects angle shape center]
             (update-in objects [(:id shape) :modifiers] merge (gsh/rotation-modifiers center shape angle)))

           (rotate-around-center [objects angle center shapes]
             (reduce #(rotate-shape %1 angle %2 center) objects shapes))

           (set-rotation [objects]
             (let [id->obj #(get objects %)
                   get-children (fn [shape] (map id->obj (cp/get-children (:id shape) objects)))
                   shapes (concat shapes (mapcat get-children shapes))]
               (rotate-around-center objects delta-rotation center shapes)))]

     (ptk/reify ::set-rotation
       ptk/UpdateEvent
       (update [_ state]
         (let [page-id (:current-page-id state)]
           (d/update-in-when state [:workspace-data :pages-index page-id :objects] set-rotation)))))))

(defn increase-rotation [ids rotation]
  (ptk/reify ::increase-rotation
    ptk/WatchEvent
    (watch [_ state stream]

      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            rotate-shape (fn [shape]
                           (let [delta (- rotation (:rotation shape))]
                             (set-rotation delta [shape])))]
        (rx/concat
         (rx/from (->> ids (map #(get objects %)) (map rotate-shape)))
         (rx/of (apply-modifiers ids)))))))

(defn apply-modifiers
  [ids]
  (us/verify (s/coll-of uuid?) ids)
  (ptk/reify ::apply-modifiers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)

            objects0 (get-in state [:workspace-file :data :pages-index page-id :objects])
            objects1 (get-in state [:workspace-data :pages-index page-id :objects])

            ;; ID's + Children ID's
            ids-with-children (d/concat [] (mapcat #(cp/get-children % objects1) ids) ids)

            ;; For each shape applies the modifiers by transforming the objects
            update-shape #(update %1 %2 gsh/transform-shape)
            objects2 (reduce update-shape objects1 ids-with-children)

            regchg   {:type :reg-objects
                      :page-id page-id
                      :shapes (vec ids)}

            ;; we need to generate redo chages from current
            ;; state (with current temporal values) to new state but
            ;; the undo should be calculated from clear current
            ;; state (without temporal values in it, for this reason
            ;; we have 3 different objects references).

            rchanges (conj (dwc/generate-changes page-id objects1 objects2) regchg)
            uchanges (conj (dwc/generate-changes page-id objects2 objects0) regchg)]

        (rx/of (dwc/start-undo-transaction)
               (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dwc/commit-undo-transaction))))))

;; --- Update Dimensions

;; Event mainly used for handling user modification of the size of the
;; object from workspace sidebar options inputs.

(defn update-dimensions
  [ids attr value]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify #{:width :height} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-dimensions
    ptk/UpdateEvent
    (update [_ state]

      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)

            update-children
            (fn [objects ids modifiers]
              (reduce #(assoc-in %1 [%2 :modifiers] modifiers) objects ids))

            ;; For each shape updates the modifiers given as arguments
            update-shape
            (fn [objects shape-id]
              (let [shape (get objects shape-id)
                    modifier (gsh/resize-modifiers shape attr value)]
                (-> objects
                    (assoc-in [shape-id :modifiers] modifier)
                    (cond-> (not (= :frame (:type shape)))
                      (update-children (cp/get-children shape-id objects) modifier)))))]

        (d/update-in-when
         state
         [:workspace-data :pages-index page-id :objects]
         #(reduce update-shape % ids))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            ids (d/concat [] ids (mapcat #(cp/get-children % objects) ids))]
        (rx/of (apply-modifiers ids))))))

(defn flip-horizontal-selected []
  (ptk/reify ::flip-horizontal-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [objects  (dwc/lookup-page-objects state)
            selected (get-in state [:workspace-local :selected])
            shapes   (map #(get objects %) selected)
            selrect  (gsh/selection-rect (->> shapes (map gsh/transform-shape)))
            origin   (gpt/point (:x selrect) (+ (:y selrect) (/ (:height selrect) 2)))]

        (rx/of (set-modifiers selected
                              {:resize-vector (gpt/point -1.0 1.0)
                               :resize-origin origin
                               :displacement (gmt/translate-matrix (gpt/point (- (:width selrect)) 0))}
                              false)
               (apply-modifiers selected))))))

(defn flip-vertical-selected []
  (ptk/reify ::flip-vertical-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [objects  (dwc/lookup-page-objects state)
            selected (get-in state [:workspace-local :selected])
            shapes   (map #(get objects %) selected)
            selrect  (gsh/selection-rect (->> shapes (map gsh/transform-shape)))
            origin   (gpt/point (+ (:x selrect) (/ (:width selrect) 2)) (:y selrect))]

        (rx/of (set-modifiers selected
                              {:resize-vector (gpt/point 1.0 -1.0)
                               :resize-origin origin
                               :displacement (gmt/translate-matrix (gpt/point 0 (- (:height selrect))))}
                              false)
               (apply-modifiers selected))))))
