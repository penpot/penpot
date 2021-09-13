;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.interactions
  "Visually show shape interactions in workspace"
  (:require
   [app.common.data :as d]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.outline :refer [outline]]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn- on-mouse-down
  [event index {:keys [id] :as shape}]
  (dom/stop-propagation event)
  (st/emit! (dw/select-shape id))
  (st/emit! (dw/start-edit-interaction index)))

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
  [{:keys [x y stroke action-type arrow-dir zoom] :as props}]
  (let [icon-pdata (case action-type
                     :navigate (case arrow-dir
                                 :right "M -5 0 l 8 0 l -4 -4 m 4 4 l -4 4"
                                 :left "M 5 0 l -8 0 l 4 -4 m -4 4 l 4 4"
                                 nil)
                     :open-overlay (case arrow-dir
                                     ;; TODO: have a different icon for open overlay?
                                     :right "M -5 0 l 8 0 l -4 -4 m 4 4 l -4 4"
                                     :left "M 5 0 l -8 0 l 4 -4 m -4 4 l 4 4"
                                     nil)

                     :close-overlay "M -4 -4 L 4 4 M -4 4 L 4 -4"

                     nil)
        inv-zoom (/ 1 zoom)]
    [:*
      [:circle {:cx 0
                :cy 0
                :r 8
                :stroke stroke
                :stroke-width 2
                :fill "#FFFFFF"
                :transform (str
                             "scale(" inv-zoom ", " inv-zoom ") "
                             "translate(" (* zoom x) ", " (* zoom y) ")")}]
      (when icon-pdata
        [:path {:stroke stroke
                :fill "none"
                :stroke-width 2
                :d icon-pdata
                :transform (str
                             "scale(" inv-zoom ", " inv-zoom ") "
                             "translate(" (* zoom x) ", " (* zoom y) ")")}])]))


(mf/defc interaction-path
  [{:keys [index orig-shape dest-shape dest-point selected? action-type zoom] :as props}]
  (let [[orig-pos orig-x orig-y dest-pos dest-x dest-y]
        (cond
          dest-shape
          (connect-to-shape orig-shape dest-shape)

          dest-point
          (connect-to-point orig-shape dest-point)

          :else
          (connect-to-point orig-shape
                            {:x (+ (:x2 (:selrect orig-shape)) 100)
                             :y (- (:y1 (:selrect orig-shape)) 50)}))

        orig-dx (if (= orig-pos :right) 100 -100)
        dest-dx (if (= dest-pos :right) 100 -100)

        path ["M" orig-x orig-y "C" (+ orig-x orig-dx) orig-y (+ dest-x dest-dx) dest-y dest-x dest-y]
        pdata (str/join " " path)

        arrow-dir (if (= dest-pos :left) :right :left)]

    (if-not selected?
      [:g {:on-mouse-down #(on-mouse-down % index orig-shape)}
       [:path {:stroke "#B1B2B5"
               :fill "none"
               :pointer-events "visible"
               :stroke-width (/ 2 zoom)
               :d pdata}]
       (when (and (not dest-shape)
                  (= action-type :close-overlay))
         [:& interaction-marker {:index index
                                 :x dest-x
                                 :y dest-y
                                 :stroke "#B1B2B5"
                                 :action-type action-type
                                 :arrow-dir arrow-dir
                                 :zoom zoom}])]

      [:g {:on-mouse-down #(on-mouse-down % index orig-shape)}
       [:path {:stroke "#31EFB8"
               :fill "none"
               :pointer-events "visible"
               :stroke-width (/ 2 zoom)
               :d pdata}]
       [:& interaction-marker {:index index
                               :x orig-x
                               :y orig-y
                               :stroke "#31EFB8"
                               :zoom zoom}]
       [:& interaction-marker {:index index
                               :x dest-x
                               :y dest-y
                               :stroke "#31EFB8"
                               :action-type action-type
                               :arrow-dir arrow-dir
                               :zoom zoom}]

       (when dest-shape
         [:& outline {:shape dest-shape
                      :color "#31EFB8"}])])))


(mf/defc interaction-handle
  [{:keys [index shape zoom] :as props}]
  (let [shape-rect (:selrect shape)
        handle-x (+ (:x shape-rect) (:width shape-rect))
        handle-y (+ (:y shape-rect) (/ (:height shape-rect) 2))]
    [:g {:on-mouse-down #(on-mouse-down % index shape)}
     [:& interaction-marker {:x handle-x
                             :y handle-y
                             :stroke "#31EFB8"
                             :action-type :navigate
                             :arrow-dir :right
                             :zoom zoom}]]))


(mf/defc interactions
  [{:keys [selected] :as props}]
  (let [local (mf/deref refs/workspace-local)
        zoom (mf/deref refs/selected-zoom)
        current-transform (:transform local)
        objects (mf/deref refs/workspace-page-objects)
        active-shapes (filter #(seq (:interactions %)) (vals objects))
        selected-shapes (map #(get objects %) selected)
        editing-interaction-index (:editing-interaction-index local)
        draw-interaction-to (:draw-interaction-to local)
        draw-interaction-to-frame (:draw-interaction-to-frame local)
        first-selected (first selected-shapes)]

    [:g.interactions
     [:g.non-selected
      (for [shape active-shapes]
        (for [[index interaction] (d/enumerate (:interactions shape))]
          (let [dest-shape (get objects (:destination interaction))
                selected? (contains? selected (:id shape))]
            (when-not selected?
              [:& interaction-path {:key (str (:id shape) "-" index)
                                    :index index
                                    :orig-shape shape
                                    :dest-shape dest-shape
                                    :selected selected
                                    :selected? false
                                    :action-type (:action-type interaction)
                                    :zoom zoom}]))))]

     [:g.selected
      (when (and draw-interaction-to first-selected)
        [:& interaction-path {:key "interactive"
                              :index nil
                              :orig-shape first-selected
                              :dest-point draw-interaction-to
                              :dest-shape draw-interaction-to-frame
                              :selected? true
                              :action-type :navigate
                              :zoom zoom}])
      (for [shape selected-shapes]
        (if (seq (:interactions shape))
          (for [[index interaction] (d/enumerate (:interactions shape))]
            (when-not (= index editing-interaction-index)
              (let [dest-shape (get objects (:destination interaction))]
                [:& interaction-path {:key (str (:id shape) "-" index)
                                      :index index
                                      :orig-shape shape
                                      :dest-shape dest-shape
                                      :selected selected
                                      :selected? true
                                      :action-type (:action-type interaction)
                                      :zoom zoom}])))
          (when (not (#{:move :rotate} current-transform))
            [:& interaction-handle {:key (:id shape)
                                    :index nil
                                    :shape shape
                                    :selected selected
                                    :zoom zoom}])))]]))

