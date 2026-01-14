;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.interactions
  "Visually show shape interactions in workspace"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.shape.interactions :as ctsi]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.render :as render]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.workspace.viewport.outline :refer [outline]]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def interactions-ref
  (l/derived #(dm/select-keys % [:editing-interaction-index
                                 :draw-interaction-to
                                 :draw-interaction-to-frame
                                 :move-overlay-to
                                 :move-overlay-index])
             refs/workspace-local =))

(defn- on-pointer-down
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
                                 :right "M -6.5 0 L 5.5 0 M 6.715 0.715 L -0.5 -6.5 M 6.715 -0.715 L -0.365 6.635"
                                 :left "M 6.5 0 l -12 0 m -0.715 0.715 l 6.5 -6.9 m -6 6 l 6 6.35"
                                 nil)

                     :open-overlay "M-5 -5 h7 v7 h-7 z M2 -2 h3.5 v7 h-7 v-2.5"

                     :toggle-overlay "M-5 -5 h7 v7 h-7 z M2 -2 h3.5 v7 h-7 v-2.5"

                     :close-overlay "M -5 -5 L 5 5 M -5 5 L 5 -5"

                     :prev-screen (case arrow-dir
                                    :left "M -6.5 0 l 12 0 l -6 -6 m 6 6 l -6 6"
                                    :right "M 6.5 0 l -12 0 l 6 -6 m -6 6 l 6 6"
                                    nil)

                     :open-url (dm/str "M1 -5 L 3 -7 L 7 -3 L 1 3 L -1 1"
                                       "M-1 5 L -3 7 L -7 3 L -1 -3 L 1 -1")

                     nil)
        inv-zoom (/ 1 zoom)]
    [:*
     [:circle {:cx 0
               :cy 0
               :r (if (some? action-type) 11 4)
               :fill stroke
               :transform (str
                           "scale(" inv-zoom ", " inv-zoom ") "
                           "translate(" (* zoom x) ", " (* zoom y) ")")}]
     (when icon-pdata
       [:path {:fill stroke
               :stroke-width 2
               :stroke "var(--app-white)"
               :d icon-pdata
               :transform (str
                           "scale(" inv-zoom ", " inv-zoom ") "
                           "translate(" (* zoom x) ", " (* zoom y) ")")}])]))


(mf/defc interaction-path
  [{:keys [index level orig-shape dest-shape dest-point selected? action-type zoom] :as props}]
  (let [[orig-pos orig-x orig-y dest-pos dest-x dest-y]
        (cond
          dest-shape
          (connect-to-shape orig-shape dest-shape)

          dest-point
          (connect-to-point orig-shape dest-point)

          :else
          (connect-to-point orig-shape
                            {:x (+ (:x2 (:selrect orig-shape)) (/ 100 zoom))
                             :y (+ (- (:y1 (:selrect orig-shape)) (/ 50 zoom))
                                   (/ (* level 32) zoom))}))

        orig-dx (/ (if (= orig-pos :right) 100 -100) zoom)
        dest-dx (/ (if (= dest-pos :right) 100 -100) zoom)

        path ["M" orig-x orig-y "C" (+ orig-x orig-dx) orig-y (+ dest-x dest-dx) dest-y dest-x dest-y]
        pdata (str/join " " path)

        arrow-dir (if (= dest-pos :left) :right :left)]

    (if-not selected?
      [:g {:on-pointer-down #(on-pointer-down % index orig-shape)}
       [:path {:stroke "var(--df-secondary)"
               :fill "none"
               :pointer-events "visible"
               :stroke-width (/ 2 zoom)
               :d pdata}]
       (when (not dest-shape)
         [:& interaction-marker {:index index
                                 :x dest-x
                                 :y dest-y
                                 :stroke "var(--df-secondary)"
                                 :action-type action-type
                                 :arrow-dir arrow-dir
                                 :zoom zoom}])]

      [:g {:on-pointer-down #(on-pointer-down % index orig-shape)}
       [:path {:stroke "var(--color-accent-tertiary)"
               :fill "none"
               :pointer-events "visible"
               :stroke-width (/ 2 zoom)
               :d pdata}]

       (when dest-shape
         [:& outline {:zoom zoom
                      :shape dest-shape
                      :color "var(--color-accent-tertiary)"}])

       [:& interaction-marker {:index index
                               :x orig-x
                               :y orig-y
                               :stroke "var(--color-accent-tertiary)"
                               :zoom zoom}]
       [:& interaction-marker {:index index
                               :x dest-x
                               :y dest-y
                               :stroke "var(--color-accent-tertiary)"
                               :action-type action-type
                               :arrow-dir arrow-dir
                               :zoom zoom}]])))


(mf/defc interaction-handle
  [{:keys [index shape zoom] :as props}]
  (let [shape-rect (:selrect shape)
        handle-x (+ (:x shape-rect) (:width shape-rect))
        handle-y (+ (:y shape-rect) (/ (:height shape-rect) 2))]
    [:g {:on-pointer-down #(on-pointer-down % index shape)}
     [:& interaction-marker {:x handle-x
                             :y handle-y
                             :stroke "var(--color-accent-tertiary)"
                             :action-type :navigate
                             :arrow-dir :right
                             :zoom zoom}]]))


(mf/defc overlay-marker
  [{:keys [page-id index orig-shape dest-shape position objects hover-disabled?] :as props}]
  (let [start-move-position
        (fn [_]
          (st/emit! (dw/start-move-overlay-pos index)))]

    (when dest-shape
      (let [orig-frame (cfh/get-frame objects orig-shape)
            marker-x   (+ (:x orig-frame) (:x position))
            marker-y   (+ (:y orig-frame) (:y position))
            width      (:width dest-shape)
            height     (:height dest-shape)
            dest-x     (:x dest-shape)
            dest-y     (:y dest-shape)

            shape-wrapper
            (mf/use-memo
             (mf/deps objects)
             #(render/shape-wrapper-factory objects))

            dest-shape-id (:id dest-shape)

            ;; FIXME: broken
            thumbnail-data-ref (mf/use-memo
                                (mf/deps page-id dest-shape-id)
                                #(refs/workspace-thumbnail-by-id dest-shape-id))
            thumbnail-data     (mf/deref thumbnail-data-ref)

            dest-shape (cond-> dest-shape
                         (some? thumbnail-data)
                         (assoc :thumbnail thumbnail-data))]
        [:g {:on-pointer-down start-move-position
             :on-pointer-enter #(reset! hover-disabled? true)
             :on-pointer-leave #(reset! hover-disabled? false)}
         [:g {:transform (gmt/translate-matrix (gpt/point (- marker-x dest-x) (- marker-y dest-y)))}
          [:& (mf/provider muc/render-thumbnails) {:value true}
           [:& (mf/provider embed/context) {:value false}
            [:& shape-wrapper {:shape dest-shape}]]]]
         [:path {:stroke "var(--color-accent-tertiary)"
                 :fill "var(--app-black)"
                 :fill-opacity 0.5
                 :stroke-width 1
                 :d (dm/str "M" marker-x " " marker-y " "
                            "h " width " "
                            "v " height " "
                            "h -" width " z"
                            "M" marker-x " " marker-y " "
                            "l " width " " height " "
                            "M" marker-x " " (+ marker-y height) " "
                            "l " width " -" height " ")}]
         [:circle {:cx (+ marker-x (/ width 2))
                   :cy (+ marker-y (/ height 2))
                   :r 8
                   :fill "var(--color-accent-tertiary)"}]]))))

(mf/defc interactions
  [{:keys [current-transform objects zoom selected hover-disabled? page-id] :as props}]
  (let [active-shapes (into []
                            (comp (filter #(seq (:interactions %))))
                            (vals objects))

        selected-shapes (into []
                              (map (d/getf objects))
                              selected)

        {:keys [editing-interaction-index
                draw-interaction-to
                draw-interaction-to-frame
                move-overlay-to
                move-overlay-index]} (mf/deref interactions-ref)

        first-selected (first selected-shapes)

        calc-level (fn [index interactions]
                     (->> (subvec interactions 0 index)
                          (filter #(nil? (:destination %)))
                          (count)))]

    [:g.interactions
     [:g.non-selected
      (for [shape active-shapes]
        (for [[index interaction] (d/enumerate (:interactions shape))]
          (let [dest-shape (when (ctsi/destination? interaction)
                             (get objects (:destination interaction)))
                selected? (contains? selected (:id shape))
                level (calc-level index (:interactions shape))]
            (when-not selected?
              [:& interaction-path {:key (dm/str "non-selected-" (:id shape) "-" index)
                                    :index index
                                    :level level
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
              (let [dest-shape (when (ctsi/destination? interaction)
                                 (get objects (:destination interaction)))
                    level (calc-level index (:interactions shape))]
                [:g {:key (dm/str "interaction-path-" (:id shape) "-" index)}
                 [:& interaction-path {:index index
                                       :level level
                                       :orig-shape shape
                                       :dest-shape dest-shape
                                       :selected selected
                                       :selected? true
                                       :action-type (:action-type interaction)
                                       :zoom zoom}]
                 (when (and (or (= (:action-type interaction) :open-overlay)
                                (= (:action-type interaction) :toggle-overlay))
                            (= (:overlay-pos-type interaction) :manual))
                   (if (and (some? move-overlay-to)
                            (= move-overlay-index index))
                     [:& overlay-marker {:page-id page-id
                                         :index index
                                         :orig-shape shape
                                         :dest-shape dest-shape
                                         :position move-overlay-to
                                         :objects objects
                                         :hover-disabled? hover-disabled?}]
                     [:& overlay-marker {:page-id page-id
                                         :index index
                                         :orig-shape shape
                                         :dest-shape dest-shape
                                         :position (:overlay-position interaction)
                                         :objects objects
                                         :hover-disabled? hover-disabled?}]))])))
          (when (and shape
                     (not (cfh/unframed-shape? shape))
                     (not (#{:move :rotate} current-transform)))
            [:& interaction-handle {:key (:id shape)
                                    :index nil
                                    :shape shape
                                    :selected selected
                                    :zoom zoom}])))]]))
