;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.zoom
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.align :as gal]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.viewport-wasm :as dwvw]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- split?
  "True while the compare mode puts main beside the branch
  (`app.main.data.workspace.compare`): main takes the first half of the
  viewport, and the page shows in the second one."
  [state]
  (= :side (get-in state [:workspace-compare :view])))

(defn- get-split-extent
  "The rect of the page as main has it, which main shows beside the
  branch (`:main-extent` of the compare mode); nil when none is shown."
  [state]
  (when (split? state)
    (get-in state [:workspace-compare :main-extent])))

(defn room-center
  "The point of the page at the middle of the room the canvas shows it
  in: the middle of `vbox`, or of its second half while main takes the
  first one (`split`)."
  [{:keys [x y width height]} split]
  (gpt/point (+ x (* width (if split 0.75 0.5)))
             (+ y (/ height 2))))

(defn fit-rect
  "`local` (the `:workspace-local` entry) with the zoom and the viewbox
  that fit `rect`, with `padding` around it, in the room the canvas
  shows the page in: the whole viewport, or its second half while main
  takes the first one (`split`)."
  [{:keys [vport] :as local} rect padding split]
  (let [room  (cond-> vport split (update :width / 2))
        srect (gal/adjust-to-viewport room rect {:padding padding :min-zoom 0.01})
        zoom  (/ (:width room) (:width srect))
        vbox  (if split
                ;; `srect` is centred on `rect`, and so is the second half
                (let [width  (/ (:width vport) zoom)
                      height (/ (:height vport) zoom)]
                  (grc/make-rect (- (+ (:x srect) (/ (:width srect) 2)) (* width 0.75))
                                 (- (+ (:y srect) (/ (:height srect) 2)) (/ height 2))
                                 width
                                 height))
                srect)]
    (-> local
        (assoc :zoom zoom)
        (assoc :zoom-inverse (/ 1 zoom))
        (update :vbox merge vbox))))

(defn impl-update-zoom
  "`local` at `zoom` (a number, or a fn of the zoom it has), with the
  point `center` of the page kept where it is on the screen. Without a
  `center`, the middle of the room the canvas shows the page in stays
  (`room-center`), which takes the second half of the viewport while
  main takes the first one (`split`)."
  [{:keys [vbox] :as local} center zoom & {:keys [split]}]
  (let [new-zoom (if (fn? zoom) (zoom (:zoom local)) zoom)
        old-zoom (:zoom local)
        center   (if center center (room-center vbox split))
        scale    (/ old-zoom new-zoom)
        mtx      (gmt/scale-matrix (gpt/point scale) center)
        vbox'    (gsh/transform-rect vbox mtx)]
    (-> local
        (assoc :zoom new-zoom)
        (assoc :zoom-inverse (/ 1 new-zoom))
        (update :vbox merge (select-keys vbox' [:x :y :width :height])))))

(defn increase-zoom
  ([]
   (increase-zoom ::auto))
  ([center]
   (ptk/reify ::increase-zoom
     ev/PerformanceEvent

     ptk/UpdateEvent
     (update [_ state]
       (if (dwvw/render-context-lost? state)
         state
         (let [center (if (= center ::auto) @ms/mouse-position center)]
           (update state :workspace-local
                   #(impl-update-zoom % center (fn [z] (min (* z 1.3) 200)) :split (split? state))))))

     ptk/EffectEvent
     (effect [_ state _]
       (dwvw/maybe-sync-workspace-local-viewport! state)))))

(defn decrease-zoom
  ([]
   (decrease-zoom ::auto))
  ([center]
   (ptk/reify ::decrease-zoom
     ev/PerformanceEvent

     ptk/UpdateEvent
     (update [_ state]
       (if (dwvw/render-context-lost? state)
         state
         (let [center (if (= center ::auto) @ms/mouse-position center)]
           (update state :workspace-local
                   #(impl-update-zoom % center (fn [z] (max (/ z 1.3) 0.01)) :split (split? state))))))

     ptk/EffectEvent
     (effect [_ state _]
       (dwvw/maybe-sync-workspace-local-viewport! state)))))

(defn set-zoom
  ([scale]
   (set-zoom nil scale))
  ([center scale]
   (ptk/reify ::set-zoom
     ev/PerformanceEvent

     ptk/UpdateEvent
     (update [_ state]
       (if (dwvw/render-context-lost? state)
         state
         (let [vp     (dm/get-in state [:workspace-local :vbox])
               center (d/nilv center (room-center vp (split? state)))]
           (update state :workspace-local
                   #(impl-update-zoom % center (fn [z] (-> (* z scale)
                                                           (max 0.01)
                                                           (min 200))))))))

     ptk/EffectEvent
     (effect [_ state _]
       (dwvw/maybe-sync-workspace-local-viewport! state)))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (if (dwvw/render-context-lost? state)
        state
        (update state :workspace-local
                #(impl-update-zoom % nil 1 :split (split? state)))))

    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

(def zoom-to-fit-all
  (ptk/reify ::zoom-to-fit-all
    ptk/UpdateEvent
    (update [_ state]
      (if (dwvw/render-context-lost? state)
        state
        (let [page-id (:current-page-id state)
              objects (dsh/lookup-page-objects state page-id)
              shapes  (cfh/get-immediate-children objects)
              srect   (gsh/shapes->rect shapes)
              ;; main beside the branch can reach further than it
              main    (get-split-extent state)
              srect   (if (some? main) (grc/join-rects [srect main]) srect)]
          (if (empty? shapes)
            state
            (update state :workspace-local fit-rect srect 160 (split? state))))))

    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

(defn center-on-shape
  "Pan the viewport to center on the shape with the given id without changing zoom."
  [id]
  (ptk/reify ::center-on-shape
    ptk/UpdateEvent
    (update [_ state]
      (if (dwvw/render-context-lost? state)
        state
        (let [page-id (:current-page-id state)
              objects (dsh/lookup-page-objects state page-id)
              shape   (get objects id)
              srect   (:selrect shape)]
          (if (nil? srect)
            state
            (update state :workspace-local
                    (fn [{:keys [vbox] :as local}]
                      (let [cx    (+ (:x srect) (/ (:width srect) 2))
                            cy    (+ (:y srect) (/ (:height srect) 2))
                            new-x (- cx (/ (:width vbox) 2))
                            new-y (- cy (/ (:height vbox) 2))]
                        (update local :vbox assoc :x new-x :y new-y))))))))

    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

(def zoom-to-selected-shape
  (ptk/reify ::zoom-to-selected-shape
    ptk/UpdateEvent
    (update [_ state]
      (if (dwvw/render-context-lost? state)
        state
        (let [selected (dsh/lookup-selected state)]
          (if (empty? selected)
            state
            (let [page-id (:current-page-id state)
                  objects (dsh/lookup-page-objects state page-id)
                  srect   (->> selected
                               (map #(get objects %))
                               (gsh/shapes->rect))]
              (update state :workspace-local fit-rect srect 40 (split? state)))))))

    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

(defn fit-to-shapes
  [ids]
  (ptk/reify ::fit-to-shapes
    ptk/UpdateEvent
    (update [_ state]
      (if (or (dwvw/render-context-lost? state) (empty? ids))
        state
        (let [page-id (:current-page-id state)
              objects (dsh/lookup-page-objects state page-id)
              srect   (->> ids
                           (map #(get objects %))
                           (gsh/shapes->rect))]

          (update state :workspace-local fit-rect srect 40 (split? state)))))

    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

(defn start-zooming [pt]
  (ptk/reify ::start-zooming
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (->> stream (rx/filter (ptk/type? ::finish-zooming)))]
        (when (and (not (dwvw/render-context-lost? state))
                   (not (get-in state [:workspace-local :zooming])))
          (rx/concat
           (rx/of (fn [s] (dwvw/maybe-view-interaction-start! s) s))
           (rx/of #(-> % (assoc-in [:workspace-local :zooming] true)))
           (->> stream
                (rx/filter mse/pointer-event?)
                (rx/filter #(some? (mse/get-pointer-movement %)))
                (rx/map mse/get-pointer-movement)
                (rx/take-until stopper)
                (rx/map (fn [delta]
                          (let [scale (+ 1 (/ (:y delta) 100))] ;; this number may be adjusted after user testing
                            (set-zoom pt scale)))))))))))

(defn finish-zooming []
  (ptk/reify ::finish-zooming
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :zooming)))
    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-view-interaction-end! state))))
