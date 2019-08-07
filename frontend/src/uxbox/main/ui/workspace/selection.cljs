;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.selection
  "Multiple selection handlers component."
  (:require
   [beicon.core :as rx]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [rumext.core :as mx]
   [uxbox.main.constants :as c]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as streams]
   [uxbox.main.user-events :as uev]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

;; --- Refs & Constants

(def ^:private +circle-props+
  {:r 6
   :style {:fillOpacity "1"
           :strokeWidth "1px"
           :vectorEffect "non-scaling-stroke"}
   :fill "#31e6e0"
   :stroke "#28c4d4"})

(defn- focus-selected-shapes
  [state]
  (let [selected (get-in state [:workspace :selected])]
    (mapv #(get-in state [:shapes %]) selected)))

(def ^:private selected-shapes-ref
  "A customized version of `refs/selected-shapes` that
  additionally resolves the shapes to the real object
  instead of just return a set of ids."
  (-> (l/lens focus-selected-shapes)
      (l/derive st/state)))

(def ^:private selected-modifers-ref
  "A customized version of `refs/selected-modifiers`
  that instead of focus to one concrete id, it focuses
  on the whole map."
  (-> (l/key :modifiers)
      (l/derive refs/workspace)))

;; --- Resize Implementation
;; TODO: this function need to be refactored

;; (defrecord StartResizeSelected [vid ids shape]
;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [pid (get-in state [:workspace :current])
;;           wst (get-in state [:workspace pid])


(defn- start-resize
  [vid ids shape]
  (letfn [(on-resize [shape [point lock?]]
            (let [result (geom/resize-shape vid shape point lock?)
                  scale (geom/calculate-scale-ratio shape result)
                  mtx (geom/generate-resize-matrix vid shape scale)
                  xfm (map #(udw/apply-temporal-resize % mtx))]
              (apply st/emit! (sequence xfm ids))))

          (on-end []
            (apply st/emit! (map udw/apply-resize ids)))

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

    (let [shape  (->> (geom/shape->rect-shape shape)
                      (geom/size))
          stoper (->> streams/events
                      (rx/filter uev/mouse-up?)
                      (rx/take 1))
          stream (->> streams/canvas-mouse-position
                      (rx/take-until stoper)
                      (rx/map apply-zoom)
                      (rx/mapcat apply-grid-alignment)
                      (rx/with-latest vector streams/mouse-position-ctrl)
                      (rx/map normalize-proportion-lock))]
      (rx/subscribe stream (partial on-resize shape) nil on-end))))

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
            :strokeWidth "1px"
            :vectorEffect "non-scaling-stroke"}
    :fill "#31e6e0"
    :stroke "#28c4d4"
    :cx cx
    :cy cy}])

(mf/defc controls
  [{:keys [shape zoom on-click] :as props}]
  (let [{:keys [x1 y1 width height]} shape
        radius (if (> (max width height) handler-size-threshold) 6.0 4.0)]
    [:g.controls
     [:rect.main {:x x1 :y y1
                  :width width
                  :height height
                  :stroke-dasharray (str (/ 5.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#333" :fill "transparent"
                          :stroke-opacity "1"}}]
     [:& control-item {:class "top"
                       :on-click #(on-click :top %)
                       :r (/ radius zoom)
                       :cx (+ x1 (/ width 2))
                       :cy (- y1 2)}]
     [:& control-item {:on-click #(on-click :right %)
                       :r (/ radius zoom)
                       :cy (+ y1 (/ height 2))
                       :cx (+ x1 width 1)
                       :class "right"}]
     [:& control-item {:on-click #(on-click :bottom %)
                       :r (/ radius zoom)
                       :cx (+ x1 (/ width 2))
                       :cy (+ y1 height 2)
                       :class "bottom"}]
     [:& control-item {:on-click #(on-click :left %)
                       :r (/ radius zoom)
                       :cy (+ y1 (/ height 2))
                       :cx (- x1 3)
                       :class "left"}]
     [:& control-item {:on-click #(on-click :top-left %)
                       :r (/ radius zoom)
                       :cx x1
                       :cy y1
                       :class "top-left"}]
     [:& control-item {:on-click #(on-click :top-right %)
                      :r (/ radius zoom)
                      :cx (+ x1 width)
                      :cy y1
                      :class "top-right"}]
     [:& control-item {:on-click #(on-click :bottom-left %)
                       :r (/ radius zoom)
                       :cx x1
                       :cy (+ y1 height)
                       :class "bottom-left"}]
     [:& control-item {:on-click #(on-click :bottom-right %)
                       :r (/ radius zoom)
                       :cx (+ x1 width)
                       :cy (+ y1 height)
                       :class "bottom-right"}]]))

;; --- Selection Handlers (Component)

;; (defn get-path-edition-stoper
;;   [stream]
;;   (letfn [(stoper-event? [{:keys [type shift] :as event}]
;;             (and (uev/mouse-event? event) (= type :up)))]
;;     (rx/merge
;;      (rx/filter stoper-event? stream)
;;      (->> stream
;;           (rx/filter #(= % ::uev/interrupt))
;;           (rx/take 1)))))

;; (defn start-path-edition
;;   [shape-id index]
;;   (letfn [(on-move [delta]
;;             (st/emit! (uds/update-path shape-id index delta)))]
;;     (let [stoper (get-path-edition-stoper streams/events)
;;           stream (rx/take-until stoper streams/mouse-position-deltas)]
;;       (when @refs/selected-alignment
;;         (st/emit! (uds/initial-path-point-align shape-id index)))
;;       (rx/subscribe stream on-move))))

;; (mx/defc path-edition-selection-handlers
;;   [{:keys [id segments modifiers] :as shape} zoom]
;;   (letfn [(on-click [index event]
;;             (dom/stop-propagation event)
;;             (start-path-edition id index))]

;;     (let [{:keys [displacement]} modifiers
;;           segments (if displacement
;;                      (map #(gpt/transform % displacement) segments)
;;                      segments)]

;;       [:g.controls
;;        (for [[index {:keys [x y]}] (map-indexed vector segments)]
;;          [:circle {:cx x :cy y
;;                    :r (/ 6.0 zoom)
;;                    :key index
;;                    :on-click (partial on-click index)
;;                    :fill "#31e6e0"
;;                    :stroke "#28c4d4"
;;                    :style {:cursor "pointer"}}])])))

(mf/defc multiple-selection-handlers
  [{:keys [shapes modifiers zoom] :as props}]
  (let [shape (->> shapes
                   (map #(assoc % :modifiers (get modifiers (:id %))))
                   (map #(geom/selection-rect %))
                   (geom/shapes->rect-shape)
                   (geom/selection-rect))
        on-click #(do (dom/stop-propagation %2)
                      (start-resize %1 (map :id shapes) shape))]
    [:& controls {:shape shape
                  :zoom zoom
                  :on-click on-click}]))

(mf/defc single-selection-handlers
  [{:keys [shape zoom] :as props}]
  (let [on-click #(do (dom/stop-propagation %2)
                      (start-resize %1 #{(:id shape)} shape))
        shape (geom/selection-rect shape)]
    [:& controls {:shape shape :zoom zoom :on-click on-click}]))

;; (mx/defc text-edition-selection-handlers
;;   {:mixins [mx/static]}
;;   [{:keys [id] :as shape} zoom]
;;   (let [{:keys [x1 y1 width height] :as shape} (geom/selection-rect shape)]
;;     [:g.controls
;;      [:rect.main {:x x1 :y y1
;;                   :width width
;;                   :height height
;;                   ;; :stroke-dasharray (str (/ 5.0 zoom) "," (/ 5 zoom))
;;                   :style {:stroke "#333"
;;                           :stroke-width "0.5"
;;                           :stroke-opacity "0.5"
;;                           :fill "transparent"}}]]))

(defn- focus-shapes
  [selected]
  (mapv #(get-in @st/state [:shapes %]) selected))

(mf/defc selection-handlers
  [{:keys [wst] :as props}]
  (let [shapes (focus-shapes (:selected wst))
        edition? (:edition wst)
        modifiers (:modifiers wst)
        zoom (:zoom wst 1)
        num (count shapes)
        {:keys [id type] :as shape} (first shapes)]

    (cond
      (zero? num)
      nil

      (> num 1)
      [:& multiple-selection-handlers {:shapes shapes
                                       :modifiers modifiers
                                       :zoom zoom}]

      ;; (and (= type :text) edition?)
      ;; (-> (assoc shape :modifiers (get modifiers id))
      ;;     (text-edition-selection-handlers zoom))

      ;; (= type :path)
      ;; (if (= edition? (:id shape))
      ;;   (-> (assoc shape :modifiers (get modifiers id))
      ;;       (path-edition-selection-handlers zoom))
      ;;   (-> (assoc shape :modifiers (get modifiers id))
      ;;       (single-selection-handlers zoom)))

      :else
      [:& single-selection-handlers
       {:shape (assoc shape :modifiers (get modifiers id))
        :zoom zoom}])))
