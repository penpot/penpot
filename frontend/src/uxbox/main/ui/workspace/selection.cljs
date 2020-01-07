;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.selection
  "Selection handlers component."
  (:require
   [beicon.core :as rx]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]))

;; --- Refs & Constants

(def ^:private +circle-props+
  {:r 6
   :style {:fillOpacity "1"
           :strokeWidth "2px"
           :vectorEffect "non-scaling-stroke"}
   :fill "rgba(49,239,184,.7)"
   :stroke "#31EFB8"})

;; --- Resize Implementation

(defn- start-resize
  [vid ids shape]
  (letfn [(resize [shape [point lock?]]
            (let [result (geom/resize-shape vid shape point lock?)
                  scale (geom/calculate-scale-ratio shape result)
                  mtx (geom/generate-resize-matrix vid shape scale)]
              (rx/of (dw/assoc-temporal-modifier-in-bulk ids mtx))))

          ;; Unifies the instantaneous proportion lock modifier
          ;; activated by Ctrl key and the shapes own proportion
          ;; lock flag that can be activated on element options.
          (normalize-proportion-lock [[point ctrl?]]
            (let [proportion-lock? (:proportion-lock shape)]
              [point (or proportion-lock? ctrl?)]))

          ;; Applies alginment to point if it is currently
          ;; activated on the current workspace
          (apply-grid-alignment [point]
            (if @refs/selected-alignment
              (uwrk/align-point point)
              (rx/of point)))

          ;; Apply the current zoom factor to the point.
          (apply-zoom [point]
            (gpt/divide point @refs/selected-zoom))]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [shape  (->> (geom/shape->rect-shape shape)
                          (geom/size))
              stoper (rx/filter ms/mouse-up? stream)]
          (rx/concat
           (->> ms/mouse-position
                (rx/map apply-zoom)
                (rx/mapcat apply-grid-alignment)
                (rx/with-latest vector ms/mouse-position-ctrl)
                (rx/map normalize-proportion-lock)
                (rx/mapcat (partial resize shape))
                (rx/take-until stoper))
           (rx/of (dw/materialize-temporal-modifier-in-bulk ids)
                  ::dw/page-data-update)))))))

;; --- Controls (Component)

(def ^:private handler-size-threshold
  "The size in pixels that shape width or height
  should reach in order to increase the handler
  control pointer radius from 4 to 6."
  60)

(mf/defc control-item
  [{:keys [class on-click r cy cx] :as props}]
  [:circle
   {:class-name class
    :on-mouse-down on-click
    :r r
    :style {:fillOpacity "1"
            :strokeWidth "2px"
            :vectorEffect "non-scaling-stroke"}
   :fill "rgba(49,239,184,.7)"
   :stroke "#31EFB8"
    :cx cx
    :cy cy}])

(mf/defc controls
  [{:keys [shape zoom on-click] :as props}]
  (let [{:keys [x y width height]} shape
        radius (if (> (max width height) handler-size-threshold) 6.0 4.0)]
    [:g.controls
     [:rect.main {:x x :y y
                  :width width
                  :height height
                  :stroke-dasharray (str (/ 8.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#31EFB8" :fill "transparent"
                          :stroke-opacity "1"}}]
     [:& control-item {:class "top"
                       :on-click #(on-click :top %)
                       :r (/ radius zoom)
                       :cx (+ x (/ width 2))
                       :cy (- y 2)}]
     [:& control-item {:on-click #(on-click :right %)
                       :r (/ radius zoom)
                       :cy (+ y (/ height 2))
                       :cx (+ x width 1)
                       :class "right"}]
     [:& control-item {:on-click #(on-click :bottom %)
                       :r (/ radius zoom)
                       :cx (+ x (/ width 2))
                       :cy (+ y height 2)
                       :class "bottom"}]
     [:& control-item {:on-click #(on-click :left %)
                       :r (/ radius zoom)
                       :cy (+ y (/ height 2))
                       :cx (- x 3)
                       :class "left"}]
     [:& control-item {:on-click #(on-click :top-left %)
                       :r (/ radius zoom)
                       :cx x
                       :cy y
                       :class "top-left"}]
     [:& control-item {:on-click #(on-click :top-right %)
                      :r (/ radius zoom)
                      :cx (+ x width)
                      :cy y
                      :class "top-right"}]
     [:& control-item {:on-click #(on-click :bottom-left %)
                       :r (/ radius zoom)
                       :cx x
                       :cy (+ y height)
                       :class "bottom-left"}]
     [:& control-item {:on-click #(on-click :bottom-right %)
                       :r (/ radius zoom)
                       :cx (+ x width)
                       :cy (+ y height)
                       :class "bottom-right"}]]))

;; --- Selection Handlers (Component)

(mf/defc path-edition-selection-handlers
  [{:keys [shape modifiers zoom] :as props}]
  (letfn [(on-mouse-down [event index]
            (dom/stop-propagation event)
            ;; TODO: this need code ux refactor
            (let [stoper (get-edition-stream-stoper)
                  stream (->> (ms/mouse-position-deltas @ms/mouse-position)
                              (rx/take-until stoper))]
              (when @refs/selected-alignment
                (st/emit! (dw/initial-path-point-align (:id shape) index)))
              (rx/subscribe stream #(on-handler-move % index))))

          (get-edition-stream-stoper []
            (let [stoper? #(and (ms/mouse-event? %) (= (:type %) :up))]
              (rx/merge
               (rx/filter stoper? st/stream)
               (->> st/stream
                    (rx/filter #(= % :interrupt))
                    (rx/take 1)))))

          (on-handler-move [delta index]
            (st/emit! (dw/update-path (:id shape) index delta)))]

    (let [displacement (:displacement modifiers)
          segments (cond->> (:segments shape)
                     displacement (map #(gpt/transform % displacement)))]
      [:g.controls
       (for [[index {:keys [x y]}] (map-indexed vector segments)]
         [:circle {:cx x :cy y
                   :r (/ 6.0 zoom)
                   :key index
                   :on-mouse-down #(on-mouse-down % index)
                   :fill "rgba(49,239,184,.7)"
                   :stroke "#31EFB8"
                   :style {:cursor "pointer"}}])])))

;; TODO: add specs for clarity

(mf/defc multiple-selection-handlers
  [{:keys [shapes selected zoom] :as props}]
  (let [shape (->> shapes
                   (map #(geom/selection-rect %))
                   (geom/shapes->rect-shape)
                   (geom/selection-rect))
        on-click #(do (dom/stop-propagation %2)
                      (st/emit! (start-resize %1 selected shape)))]
    [:& controls {:shape shape
                  :zoom zoom
                  :on-click on-click}]))

(mf/defc text-edition-selection-handlers
  [{:keys [shape zoom] :as props}]
  (let [{:keys [x y width height] :as shape} (geom/selection-rect shape)]
    [:g.controls
     [:rect.main {:x x :y y
                  :width width
                  :height height
                  ;; :stroke-dasharray (str (/ 5.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#31EFB8"
                          :stroke-width "0.5"
                          :stroke-opacity "1"
                          :fill "transparent"}}]]))

(mf/defc single-selection-handlers
  [{:keys [shape zoom] :as props}]
  (let [on-click #(do (dom/stop-propagation %2)
                      (st/emit! (start-resize %1 #{(:id shape)} shape)))
        shape (geom/selection-rect shape)]
    [:& controls {:shape shape :zoom zoom :on-click on-click}]))

(mf/defc selection-handlers
  [{:keys [selected edition zoom] :as props}]
  (let [data   (mf/deref refs/workspace-data)
        shapes (map #(get-in data [:shapes-by-id %]) selected)
        num (count shapes)
        {:keys [id type] :as shape} (first shapes)]
    (cond
      (zero? num)
      nil

      (> num 1)
      [:& multiple-selection-handlers {:shapes shapes
                                       :selected selected
                                       :zoom zoom}]

      (and (= type :text)
           (= edition (:id shape)))
      [:& text-edition-selection-handlers {:shape shape
                                           :zoom zoom}]
      (and (or (= type :path)
               (= type :curve))
           (= edition (:id shape)))
      [:& path-edition-selection-handlers {:shape shape
                                           :zoom zoom}]

      :else
      [:& single-selection-handlers {:shape shape
                                     :zoom zoom}])))
