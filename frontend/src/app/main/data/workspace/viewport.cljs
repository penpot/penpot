;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.viewport
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.align :as gal]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as gpr]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.data.helpers :as dsh]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn initialize-viewport
  [{:keys [width height] :as size}]

  (assert (gpr/rect? size)
          "expected `size` to be a rect instance")

  (letfn [(update* [{:keys [vport] :as local}]
            (let [wprop (/ (:width vport) width)
                  hprop (/ (:height vport) height)]
              (-> local
                  (assoc :vport size)
                  (update :vbox (fn [vbox]
                                  (-> vbox
                                      (update :width #(/ % wprop))
                                      (update :height #(/ % hprop))
                                      (gpr/update-rect :size)))))))

          (initialize [state local]
            (let [page-id (:current-page-id state)
                  objects (dsh/lookup-page-objects state page-id)
                  shapes  (cfh/get-immediate-children objects)
                  srect   (gsh/shapes->rect shapes)
                  local   (assoc local :vport size :zoom 1 :zoom-inverse 1 :hide-toolbar false)]

              (cond
                (or (not (d/num? (:width srect)))
                    (not (d/num? (:height srect))))
                (assoc local :vbox (assoc size :x 0 :y 0))

                (or (> (:width srect) width)
                    (> (:height srect) height))
                (let [srect (gal/adjust-to-viewport size srect {:padding 40})
                      zoom  (/ (:width size) (:width srect))]

                  (-> local
                      (assoc :zoom zoom)
                      (assoc :zoom-inverse (/ 1 zoom))
                      (update :vbox (fn [vbox]
                                      (-> (merge vbox srect)
                                          (gpr/make-rect))))))

                :else
                (let [vx   (+ (:x srect)
                              (/ (- (:width srect) width) 2))
                      vy   (+ (:y srect)
                              (/ (- (:height srect) height) 2))
                      vbox (-> size
                               (assoc :x vx)
                               (assoc :y vy)
                               (gpr/update-rect :position))]
                  (assoc local :vbox vbox)))))

          (setup [state local]
            (if (and (:vbox local) (:vport local))
              (update* local)
              (initialize state local)))]

    (ptk/reify ::initialize-viewport
      ptk/UpdateEvent
      (update [_ state]
        (update state :workspace-local
                (fn [local]
                  (setup state local)))))))

(defn calculate-centered-viewbox
  "Updates the viewbox coordinates for a given center position"
  [local position]
  (let [vbox (:vbox local)
        nw   (/ (:width vbox) 2)
        nh   (/ (:height vbox) 2)
        nx   (- (:x position) nw)
        ny   (- (:y position) nh)]
    (update local :vbox assoc :x nx :y ny)))

(defn update-viewport-position-center
  [position]
  (assert (gpt/point? position) "expected a point instance for `position` param")

  (ptk/reify ::update-viewport-position-center
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local calculate-centered-viewbox position))))

(defn update-viewport-position
  [{:keys [x y] :or {x identity y identity}}]

  (dm/assert!
   "expected function for `x`"
   (fn? x))
  (dm/assert!
   "expected function for `y`"
   (fn? y))

  (ptk/reify ::update-viewport-position
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :vbox]
                 (fn [vbox]
                   (-> vbox
                       (update :x x)
                       (update :y y)))))))

(defn update-viewport-size
  [resize-type {:keys [width height] :as size}]
  (ptk/reify ::update-viewport-size
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              (fn [{:keys [vport] :as local}]
                (if (or (nil? vport)
                        (mth/almost-zero? width)
                        (mth/almost-zero? height))
                  ;; If we have a resize to zero just keep the old value
                  local
                  (let [wprop (/ (:width vport) width)
                        hprop (/ (:height vport) height)

                        vbox (:vbox local)
                        vbox-x (:x vbox)
                        vbox-y (:y vbox)
                        vbox-width (:width vbox)
                        vbox-height (:height vbox)

                        vbox-width' (/ vbox-width wprop)
                        vbox-height' (/ vbox-height hprop)

                        vbox-x'
                        (case resize-type
                          :left  (+ vbox-x (- vbox-width vbox-width'))
                          :right vbox-x
                          (+ vbox-x (/ (- vbox-width vbox-width') 2)))

                        vbox-y'
                        (case resize-type
                          :top  (+ vbox-y (- vbox-height vbox-height'))
                          :bottom vbox-y
                          (+ vbox-y (/ (- vbox-height vbox-height') 2)))]
                    (-> local
                        (assoc :vport size)
                        (assoc-in [:vbox :x] vbox-x')
                        (assoc-in [:vbox :y] vbox-y')
                        (assoc-in [:vbox :width] vbox-width')
                        (assoc-in [:vbox :height] vbox-height')))))))))

(defn start-panning []
  (ptk/reify ::start-panning
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (->> stream (rx/filter (ptk/type? ::finish-panning)))
            zoom (get-in state [:workspace-local :zoom])]
        (when-not (get-in state [:workspace-local :panning])
          (rx/concat
           (rx/of #(-> % (assoc-in [:workspace-local :panning] true)))
           (->> stream
                (rx/filter mse/pointer-event?)
                (rx/filter #(= :delta (:source %)))
                (rx/take-until stopper)
                ;; Some events are executed in synchronous way like panning with backspace pressed
                (rx/observe-on :af)
                (rx/map (fn [event]
                          (let [delta (dm/get-prop event :pt)]
                            (update-viewport-position {:x #(- % (/ (:x delta) zoom))
                                                       :y #(- % (/ (:y delta) zoom))})))))))))))

(defn finish-panning []
  (ptk/reify ::finish-panning
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :panning)))))
