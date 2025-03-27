;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
   [app.main.data.helpers :as dsh]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn impl-update-zoom
  [{:keys [vbox] :as local} center zoom]
  (let [new-zoom (if (fn? zoom) (zoom (:zoom local)) zoom)
        old-zoom (:zoom local)
        center   (if center center (grc/rect->center vbox))
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
     ptk/UpdateEvent
     (update [_ state]
       (let [center (if (= center ::auto) @ms/mouse-position center)]
         (update state :workspace-local
                 #(impl-update-zoom % center (fn [z] (min (* z 1.3) 200)))))))))

(defn decrease-zoom
  ([]
   (decrease-zoom ::auto))
  ([center]
   (ptk/reify ::decrease-zoom
     ptk/UpdateEvent
     (update [_ state]
       (let [center (if (= center ::auto) @ms/mouse-position center)]
         (update state :workspace-local
                 #(impl-update-zoom % center (fn [z] (max (/ z 1.3) 0.01)))))))))

(defn set-zoom
  ([scale]
   (set-zoom nil scale))
  ([center scale]
   (ptk/reify ::set-zoom
     ptk/UpdateEvent
     (update [_ state]
       (let [vp (dm/get-in state [:workspace-local :vbox])
             x (+ (:x vp) (/ (:width vp) 2))
             y (+ (:y vp) (/ (:height vp) 2))
             center (d/nilv center (gpt/point x y))]
         (update state :workspace-local
                 #(impl-update-zoom % center (fn [z] (-> (* z scale)
                                                         (max 0.01)
                                                         (min 200))))))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              #(impl-update-zoom % nil 1)))))

(def zoom-to-fit-all
  (ptk/reify ::zoom-to-fit-all
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            shapes  (cfh/get-immediate-children objects)
            srect   (gsh/shapes->rect shapes)]
        (if (empty? shapes)
          state
          (update state :workspace-local
                  (fn [{:keys [vport] :as local}]
                    (let [srect (gal/adjust-to-viewport vport srect {:padding 160})
                          zoom  (/ (:width vport) (:width srect))]
                      (-> local
                          (assoc :zoom zoom)
                          (assoc :zoom-inverse (/ 1 zoom))
                          (update :vbox merge srect))))))))))

(def zoom-to-selected-shape
  (ptk/reify ::zoom-to-selected-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (dsh/lookup-selected state)]
        (if (empty? selected)
          state
          (let [page-id (:current-page-id state)
                objects (dsh/lookup-page-objects state page-id)
                srect   (->> selected
                             (map #(get objects %))
                             (gsh/shapes->rect))]
            (update state :workspace-local
                    (fn [{:keys [vport] :as local}]
                      (let [srect (gal/adjust-to-viewport vport srect {:padding 40})
                            zoom  (/ (:width vport) (:width srect))]
                        (-> local
                            (assoc :zoom zoom)
                            (assoc :zoom-inverse (/ 1 zoom))
                            (update :vbox merge srect)))))))))))

(defn fit-to-shapes
  [ids]
  (ptk/reify ::fit-to-shapes
    ptk/UpdateEvent
    (update [_ state]
      (if (empty? ids)
        state
        (let [page-id (:current-page-id state)
              objects (dsh/lookup-page-objects state page-id)
              srect   (->> ids
                           (map #(get objects %))
                           (gsh/shapes->rect))]

          (update state :workspace-local
                  (fn [{:keys [vport] :as local}]
                    (let [srect (gal/adjust-to-viewport
                                 vport srect
                                 {:padding 40})
                          zoom  (/ (:width vport)
                                   (:width srect))]
                      (-> local
                          (assoc :zoom zoom)
                          (assoc :zoom-inverse (/ 1 zoom))
                          (update :vbox merge srect))))))))))

(defn start-zooming [pt]
  (ptk/reify ::start-zooming
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (->> stream (rx/filter (ptk/type? ::finish-zooming)))]
        (when-not (get-in state [:workspace-local :zooming])
          (rx/concat
           (rx/of #(-> % (assoc-in [:workspace-local :zooming] true)))
           (->> stream
                (rx/filter mse/pointer-event?)
                (rx/filter #(= :delta (:source %)))
                (rx/map :pt)
                (rx/take-until stopper)
                (rx/map (fn [delta]
                          (let [scale (+ 1 (/ (:y delta) 100))] ;; this number may be adjusted after user testing
                            (set-zoom pt scale)))))))))))

(defn finish-zooming []
  (ptk/reify ::finish-zooming
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :zooming)))))
