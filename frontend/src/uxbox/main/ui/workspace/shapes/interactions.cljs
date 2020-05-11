;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.shapes.interactions
  "Visually show shape interactions in workspace"
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.util.data :as dt]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.ui.keyboard :as kbd]
   ))

(defn- get-click-interaction
  [shape]
  (first (filter #(= (:event-type %) :click) (:interactions shape))))

(defn- on-mouse-down-unselected
  [event {:keys [id type] :as shape} selected]
  (do
    (dom/stop-propagation event)
    (if (empty? selected)
      (st/emit! (dw/select-shape id)
                (dw/start-move-selected))

      (if (kbd/shift? event)
        (st/emit! (dw/select-shape id))
        (st/emit! dw/deselect-all
                  (dw/select-shape id)
                  (dw/start-move-selected))))))

;; TODO: add more mouse behavior, to create interactions by drag&drop
;; (defn- on-mouse-down
;;   [event {:keys [id type] :as shape}]
;;   (let [selected @refs/selected-shapes
;;         selected? (contains? selected id)
;;         drawing? @refs/selected-drawing-tool
;;         button (.-which (.-nativeEvent event))]
;;     (when-not (:blocked shape)
;;       (cond
;;         (not= 1 button)
;;         nil
;;
;;         drawing?
;;         nil
;;
;;         (= type :frame)
;;         (when selected?
;;           (dom/stop-propagation event)
;;           (st/emit! (dw/start-move-selected)))
;;
;;         (and (not selected?) (empty? selected))
;;         (do
;;           (dom/stop-propagation event)
;;           (st/emit! dw/deselect-all
;;                     (dw/select-shape id)
;;                     (dw/start-move-selected)))
;;
;;         (and (not selected?) (not (empty? selected)))
;;         (do
;;           (dom/stop-propagation event)
;;           (if (kbd/shift? event)
;;             (st/emit! (dw/select-shape id))
;;             (st/emit! dw/deselect-all
;;                       (dw/select-shape id)
;;                       (dw/start-move-selected))))
;;         :else
;;         (do
;;           (dom/stop-propagation event)
;;           (st/emit! (dw/start-move-selected)))))))

(mf/defc interaction-path
  [{:keys [orig-shape dest-shape selected selected?] :as props}]
  (let [orig-rect (geom/selection-rect-shape orig-shape)
        dest-rect (geom/selection-rect-shape dest-shape)

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

        orig-dx (if (= orig-pos :right) 100 -100)
        dest-dx (if (= dest-pos :right) 100 -100)

        orig-y (+ (:y orig-rect) (/ (:height orig-rect) 2))
        dest-y (+ (:y dest-rect) (/ (:height dest-rect) 2))

        path ["M" orig-x orig-y "C" (+ orig-x orig-dx) orig-y (+ dest-x dest-dx) dest-y dest-x dest-y]
        pdata (str/join " " path)

        arrow-path (if (= dest-pos :left)
                     ["M" (- dest-x 5) dest-y "l 8 0 l -4 -4 m 4 4 l -4 4"]
                     ["M" (+ dest-x 5) dest-y "l -8 0 l 4 -4 m -4 4 l 4 4"])
        arrow-pdata (str/join " " arrow-path)]

    (if-not selected?
      [:path {:stroke "#B1B2B5"
              :fill "transparent"
              :stroke-width 2
              :d pdata
              :on-mouse-down #(on-mouse-down-unselected % orig-shape selected)}]

      [:g
       [:path {:stroke "#31EFB8"
               :fill "transparent"
               :stroke-width 2
               :d pdata}]
       [:circle {:cx orig-x
                 :cy orig-y
                 :r 4
                 :stroke "#31EFB8"
                 :stroke-width 2
                 :fill "#FFFFFF"}]
       [:circle {:cx dest-x
                 :cy dest-y
                 :r 8
                 :stroke "#31EFB8"
                 :stroke-width 2
                 :fill "#FFFFFF"}]
       [:path {:stroke "#31EFB8"
               :fill "transparent"
               :stroke-width 2
               :d arrow-pdata}]])))

(mf/defc interactions
  [{:keys [selected] :as props}]
  (let [data (mf/deref refs/workspace-data)
        objects (:objects data)
        active-shapes (filter #(first (get-click-interaction %)) (vals objects))
        selected-shapes (map #(get objects %) selected)]
    [:*
      (for [shape active-shapes]
        (let [interaction (get-click-interaction shape)
              dest-shape (get objects (:destination interaction))
              selected? (contains? selected (:id shape))]
          (when-not selected?
            [:& interaction-path {:key (:id shape)
                                  :orig-shape shape
                                  :dest-shape dest-shape
                                  :selected selected
                                  :selected? false}])))

      (for [shape selected-shapes]
        (let [interaction (get-click-interaction shape)
              dest-shape (get objects (:destination interaction))]
          (when dest-shape
            [:& interaction-path {:key (:id shape)
                                  :orig-shape shape
                                  :dest-shape dest-shape
                                  :selected selected
                                  :selected? true}])))]))

