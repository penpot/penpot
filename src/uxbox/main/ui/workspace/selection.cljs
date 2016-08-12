;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.selection
  "Multiple selection handlers component."
  (:require [lentes.core :as l]
            [beicon.core :as rx]
            [uxbox.main.state :as st]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.rlocks :as rlocks]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]))

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
    (mapv #(get-in state [:shapes-by-id %]) selected)))

(def ^:private selected-shapes-ref
  (-> (l/lens focus-selected-shapes)
      (l/derive st/state)))

;; --- Resize

(declare on-resize-start)

(defn watch-resize-actions
  []
  (let [stream (->> rlocks/stream
                    (rx/filter #(= (first %) :shape/resize))
                    (rx/map second))]
    (rx/subscribe stream on-resize-start)))

(defn- on-resize
  [shape vid [delta ctrl?]]
  (let [params {:vid vid :delta (assoc delta :lock ctrl?)}]
    (rs/emit! (uds/update-vertex-position shape params))))

(defn- on-resize-stop
  []
  (rlocks/release! :shape/resize))

(defn- on-resize-start
  [[vid shape]]
  (let [stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        stream (->> wb/mouse-delta-s
                    (rx/take-until stoper)
                    (rx/with-latest-from vector wb/mouse-ctrl-s))]
    (when @wb/alignment-ref
      (rs/emit! (uds/initial-vertext-align shape vid)))
    (rx/subscribe stream (partial on-resize shape vid) nil on-resize-stop)))

;; --- Movement

(declare on-move-start)

(defn watch-move-actions
  []
  (-> (rx/filter #(= (first %) :shape/move) rlocks/stream)
      (rx/subscribe #(run! on-move-start @wb/selected-shapes-ref))))

(defn- on-move-start
  [shape]
  (let [stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        stream (rx/take-until stoper wb/mouse-delta-s)
        on-move #(rs/emit! (uds/move-shape shape %))
        on-stop #(rlocks/release! :shape/move)]
    (when @wb/alignment-ref
      (rs/emit! (uds/initial-align-shape shape)))
    (rx/subscribe stream on-move nil on-stop)))

;; --- Selection Handlers (Component)

(mx/defc multiple-selection-handlers
  [shapes]
  (let [{:keys [width height x y]} (geom/outer-rect-coll shapes)]
    [:g.controls
     [:rect.main {:x x :y y
                  :width width
                  :height height
                  :stroke-dasharray "5,5"
                  :style {:stroke "#333" :fill "transparent"
                          :stroke-opacity "1"}}]]))

(mx/defc single-selection-handlers
  [{:keys [id] :as shape}]
  (letfn [(on-mouse-down [vid event]
            (dom/stop-propagation event)
            (rlocks/acquire! :shape/resize [vid id]))]
    (let [{:keys [x y width height]} (geom/outer-rect shape)]
      [:g.controls
       [:rect.main {:x x :y y :width width :height height :stroke-dasharray "5,5"
                    :style {:stroke "#333" :fill "transparent" :stroke-opacity "1"}}]
       [:circle.top
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :top %)
                :cx (+ x (/ width 2))
                :cy (- y 2)})]
       [:circle.right
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :right %)
                :cy (+ y (/ height 2))
                :cx (+ x width 1)})]
       [:circle.bottom
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :bottom %)
                :cx (+ x (/ width 2))
                :cy (+ y height 2)})]
       [:circle.left
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :left %)
                :cy (+ y (/ height 2))
                :cx (- x 3)})]
       [:circle.top-left
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :top-left %)
                :cx x
                :cy y})]
       [:circle.top-right
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :top-right %)
                :cx (+ x width)
                :cy y})]
       [:circle.bottom-left
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :bottom-left %)
                :cx x
                :cy (+ y height)})]
       [:circle.bottom-right
        (merge +circle-props+
               {:on-mouse-down #(on-mouse-down :bottom-right %)
                :cx (+ x width)
                :cy (+ y height)})]])))

(defn- selection-handlers-will-mount
  [own]
  (assoc own
         ::sub1 (watch-resize-actions)
         ::sub2 (watch-move-actions)))

(defn- selection-handlers-will-unmount
  [own]
  (.close (::sub1 own))
  (.close (::sub2 own))
  (dissoc own ::sub1 ::sub2))

(mx/defc selection-handlers
  {:mixins [mx/reactive mx/static]
   :will-mount selection-handlers-will-mount
   :will-unmount selection-handlers-will-unmount}
  []
  (let [shapes (mx/react selected-shapes-ref)
        shapes-num (count shapes)]
    (cond
      (> shapes-num 1) (multiple-selection-handlers shapes)
      (= shapes-num 1) (single-selection-handlers (first shapes)))))
