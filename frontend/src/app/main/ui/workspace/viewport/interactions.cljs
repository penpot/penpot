;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.interactions
  "Visually show shape interactions in workspace"
  (:require
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.outline :refer [outline]]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   ))

(defn- get-click-interaction
  [shape]
  (first (filter #(= (:event-type %) :click) (:interactions shape))))

(defn- on-mouse-down
  [event {:keys [id] :as shape}]
  (dom/stop-propagation event)
  (st/emit! (dw/select-shape id))
  (st/emit! (dw/start-create-interaction)))

(defn connect-to-shape
  "Calculate the best position to draw an interaction line
  between two shapes"
  [orig-shape dest-shape]
  (let [orig-rect (:selrect orig-shape)
        dest-rect (:selrect dest-shape)

        orig-x-left (:x orig-rect)
        orig-x-right (+ orig-x-left (:width orig-rect))
        orig-x-center (+ orig-x-left (/ (:width orig-rect) 2))

        dest-x-left (:x dest-rect)
        dest-x-right (+ dest-x-left (:width dest-rect))
        dest-x-center (+ dest-x-left (/ (:width dest-rect) 2))

        orig-pos (if (<= orig-x-right dest-x-left) :right
                   (if (>= orig-x-left dest-x-right) :left
                     (if (<= orig-x-center dest-x-center) :left :right)))
        dest-pos (if (<= orig-x-right dest-x-left) :left
                   (if (>= orig-x-left dest-x-right) :right
                     (if (<= orig-x-center dest-x-center) :left :right)))

        orig-x (if (= orig-pos :right) orig-x-right orig-x-left)
        dest-x (if (= dest-pos :right) dest-x-right dest-x-left)

        orig-y (+ (:y orig-rect) (/ (:height orig-rect) 2))
        dest-y (+ (:y dest-rect) (/ (:height dest-rect) 2))]

    [orig-pos orig-x orig-y dest-pos dest-x dest-y]))


(defn connect-to-point
  "Calculate the best position to draw an interaction line
  between one shape and one point"
  [orig-shape dest-point]
  (let [orig-rect (:selrect orig-shape)

        orig-x-left (:x orig-rect)
        orig-x-right (+ orig-x-left (:width orig-rect))
        orig-x-center (+ orig-x-left (/ (:width orig-rect) 2))

        dest-x (:x dest-point)
        dest-y (:y dest-point)

        orig-pos (if (<= orig-x-right dest-x) :right
                   (if (>= orig-x-left dest-x) :left
                     (if (<= orig-x-center dest-x) :right :left)))
        dest-pos (if (<= orig-x-right dest-x) :left
                   (if (>= orig-x-left dest-x) :right
                     (if (<= orig-x-center dest-x) :right :left)))

        orig-x (if (= orig-pos :right) orig-x-right orig-x-left)
        orig-y (+ (:y orig-rect) (/ (:height orig-rect) 2))]

    [orig-pos orig-x orig-y dest-pos dest-x dest-y]))


(mf/defc interaction-marker
  [{:keys [x y arrow-dir zoom] :as props}]
  (let [arrow-pdata (case arrow-dir
                      :right "M -5 0 l 8 0 l -4 -4 m 4 4 l -4 4"
                      :left "M 5 0 l -8 0 l 4 -4 m -4 4 l 4 4"
                      [])
        inv-zoom (/ 1 zoom)]
    [:*
      [:circle {:cx 0
                :cy 0
                :r 8
                :stroke "#31EFB8"
                :stroke-width 2
                :fill "#FFFFFF"
                :transform (str
                             "scale(" inv-zoom ", " inv-zoom ") "
                             "translate(" (* zoom x) ", " (* zoom y) ")")}]
      (when arrow-dir
        [:path {:stroke "#31EFB8"
                :fill "none"
                :stroke-width 2
                :d arrow-pdata
                :transform (str
                             "scale(" inv-zoom ", " inv-zoom ") "
                             "translate(" (* zoom x) ", " (* zoom y) ")")}])]))


(mf/defc interaction-path
  [{:keys [orig-shape dest-shape dest-point selected? zoom] :as props}]
  (let [[orig-pos orig-x orig-y dest-pos dest-x dest-y]
        (if dest-shape
          (connect-to-shape orig-shape dest-shape)
          (connect-to-point orig-shape dest-point))

        orig-dx (if (= orig-pos :right) 100 -100)
        dest-dx (if (= dest-pos :right) 100 -100)

        path ["M" orig-x orig-y "C" (+ orig-x orig-dx) orig-y (+ dest-x dest-dx) dest-y dest-x dest-y]
        pdata (str/join " " path)

        arrow-dir (if (= dest-pos :left) :right :left)]

    (if-not selected?
      [:path {:stroke "#B1B2B5"
              :fill "none"
              :pointer-events "visible"
              :stroke-width (/ 2 zoom)
              :d pdata
              :on-mouse-down #(on-mouse-down % orig-shape)}]

      [:g {:on-mouse-down #(on-mouse-down % orig-shape)}
       [:path {:stroke "#31EFB8"
               :fill "none"
               :pointer-events "visible"
               :stroke-width (/ 2 zoom)
               :d pdata}]
       [:& interaction-marker {:x orig-x
                               :y orig-y
                               :arrow-dir nil
                               :zoom zoom}]
       [:& interaction-marker {:x dest-x
                               :y dest-y
                               :arrow-dir arrow-dir
                               :zoom zoom}]

       (when dest-shape
         [:& outline {:shape dest-shape
                      :color "#31EFB8"}])])))


(mf/defc interaction-handle
  [{:keys [shape zoom] :as props}]
  (let [shape-rect (:selrect shape)
        handle-x (+ (:x shape-rect) (:width shape-rect))
        handle-y (+ (:y shape-rect) (/ (:height shape-rect) 2))]
    [:g {:on-mouse-down #(on-mouse-down % shape)}
     [:& interaction-marker {:x handle-x
                             :y handle-y
                             :arrow-dir :right
                             :zoom zoom}]]))


(mf/defc interactions
  [{:keys [selected] :as props}]
  (let [local (mf/deref refs/workspace-local)
        zoom (mf/deref refs/selected-zoom)
        current-transform (:transform local)
        objects (mf/deref refs/workspace-page-objects)
        active-shapes (filter #(first (get-click-interaction %)) (vals objects))
        selected-shapes (map #(get objects %) selected)
        draw-interaction-to (:draw-interaction-to local)
        draw-interaction-to-frame (:draw-interaction-to-frame local)
        first-selected (first selected-shapes)]

    [:g.interactions
     [:g.non-selected
      (for [shape active-shapes]
        (let [interaction (get-click-interaction shape)
              dest-shape (get objects (:destination interaction))
              selected? (contains? selected (:id shape))]
          (when-not (or selected? (not dest-shape))
            [:& interaction-path {:key (:id shape)
                                  :orig-shape shape
                                  :dest-shape dest-shape
                                  :selected selected
                                  :selected? false
                                  :zoom zoom}])))]

     [:g.selected
      (if (and draw-interaction-to first-selected)
        [:& interaction-path {:key "interactive"
                              :orig-shape first-selected
                              :dest-point draw-interaction-to
                              :dest-shape draw-interaction-to-frame
                              :selected? true
                              :zoom zoom}]

        (for [shape selected-shapes]
          (let [interaction (get-click-interaction shape)
                dest-shape (get objects (:destination interaction))]
            (if dest-shape
              [:& interaction-path {:key (:id shape)
                                    :orig-shape shape
                                    :dest-shape dest-shape
                                    :selected selected
                                    :selected? true
                                    :zoom zoom}]
              (when (not (#{:move :rotate} current-transform))
                [:& interaction-handle {:key (:id shape)
                                        :shape shape
                                        :selected selected
                                        :zoom zoom}])))))]]))

