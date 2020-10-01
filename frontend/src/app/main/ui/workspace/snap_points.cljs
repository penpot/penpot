;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.snap-points
  (:require
   [app.common.math :as mth]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.refs :as refs]
   [app.main.snap :as snap]
   [app.util.geom.snap-points :as sp]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(def ^:private line-color "#D383DA")
(def ^:private line-opacity 0.6)
(def ^:private line-width 1)

;; Configuration for debug
;; (def ^:private line-color "red")
;; (def ^:private line-opacity 1 )
;; (def ^:private line-width 2)

(mf/defc snap-point
  [{:keys [point zoom]}]
  (let [{:keys [x y]} point
        x (mth/round x)
        y (mth/round y)
        cross-width (/ 3 zoom)]
    [:g
     [:line {:x1 (- x cross-width)
             :y1 (- y cross-width)
             :x2 (+ x cross-width)
             :y2 (+ y cross-width)
             :style {:stroke line-color :stroke-width (str (/ line-width zoom))}}]
     [:line {:x1 (- x cross-width)
             :y1 (+ y cross-width)
             :x2 (+ x cross-width)
             :y2 (- y cross-width)
             :style {:stroke line-color :stroke-width (str (/ line-width zoom))}}]]))

(mf/defc snap-line
  [{:keys [snap point zoom]}]
  [:line {:x1 (mth/round (:x snap))
          :y1 (mth/round (:y snap))
          :x2 (mth/round (:x point))
          :y2 (mth/round (:y point))
          :style {:stroke line-color :stroke-width (str (/ line-width zoom))}
          :opacity line-opacity}])

(defn get-snap
  [coord {:keys [shapes page-id filter-shapes]}]
  (let [shape (if (> (count shapes) 1)
                (->> shapes (map gsh/transform-shape) gsh/selection-rect)
                (->> shapes (first)))

        frame-id (snap/snap-frame-id shapes)]

    (->> (rx/of shape)
         (rx/flat-map (fn [shape]
                        (->> (sp/shape-snap-points shape)
                             (map #(vector frame-id %)))))
         (rx/flat-map (fn [[frame-id point]]
                        (->> (snap/get-snap-points page-id frame-id filter-shapes point coord)
                             (rx/map #(vector point % coord)))))
         (rx/reduce conj []))))

(defn- flip
  "Function that reverses the x/y coordinates to their counterpart"
  [coord]
  (if (= coord :x) :y :x))

(defn add-point-to-snaps
  [[point snaps coord]]
  (let [normalize-coord #(assoc % coord (get point coord))]
    (cons point (map normalize-coord snaps))))


(defn- process-snap-lines
  "Gets the snaps for a coordinate and creates lines with a fixed coordinate"
  [snaps coord]
  (->> snaps
       ;; only snap on the `coord` coordinate
       (filter #(= (nth % 2) coord))
       ;; we add the point so the line goes from the point to the snap
       (mapcat add-point-to-snaps)
       ;; We flatten because it's a list of from-to points
       (flatten)
       ;; Put together the points of the coordinate
       (group-by coord)
       ;; Keep only the other coordinate
       (d/mapm #(map (flip coord) %2))
       ;; Finally get the max/min and this will define the line to draw
       (d/mapm #(vector (apply min %2) (apply max %2)))
       ;; Change the structure to retrieve a list of lines from/todo
       (map (fn [[fixedv [minv maxv]]] [(hash-map coord fixedv (flip coord) minv)
                                        (hash-map coord fixedv (flip coord) maxv)]))))

(mf/defc snap-feedback
  [{:keys [shapes page-id filter-shapes zoom] :as props}]
  (let [state (mf/use-state [])
        subject (mf/use-memo #(rx/subject))

        ;; We use sets to store points/lines so there are no points/lines repeated
        ;; can cause problems with react keys
        snap-points (->> @state
                         (mapcat add-point-to-snaps)
                         (into #{}))

        snap-lines (into (process-snap-lines @state :x)
                         (process-snap-lines @state :y))]

    (mf/use-effect
     (fn []
       (let [sub (->> subject
                      (rx/switch-map #(rx/combine-latest
                                       d/concat
                                       (get-snap :y %)
                                       (get-snap :x %)))
                      (rx/subs #(let [rs (filter (fn [[_ snaps _]] (> (count snaps) 0)) %)]
                                  (reset! state rs))))]

         ;; On unmount callback
         #(rx/dispose! sub))))

    (mf/use-effect
     (mf/deps shapes)
     (fn []
       (rx/push! subject props)))

    [:g.snap-feedback
     (for [[from-point to-point] snap-lines]
       [:& snap-line {:key (str "line-" (:x from-point)
                                "-" (:y from-point)
                                "-" (:x to-point)
                                "-" (:y to-point) "-")
                      :snap from-point
                      :point to-point
                      :zoom zoom}])
     (for [point snap-points]
       [:& snap-point {:key (str "point-" (:x point)
                                 "-" (:y point))
                       :point point
                       :zoom zoom}])]))

(mf/defc snap-points
  {::mf/wrap [mf/memo]}
  [{:keys [layout zoom selected page-id drawing transform] :as props}]
  (let [shapes        (mf/deref (refs/objects-by-id selected))
        filter-shapes (mf/deref refs/selected-shapes-with-children)
        filter-shapes (fn [id]
                        (if (= id :layout)
                          (or (not (contains? layout :display-grid))
                              (not (contains? layout :snap-grid)))
                          (or (filter-shapes id)
                              (not (contains? layout :dynamic-alignment)))))
        ;; current-transform (mf/deref refs/current-transform)
        ;; snap-data (mf/deref refs/workspace-snap-data)
        shapes    (if drawing [drawing] shapes)]
    (when (or drawing transform)
      [:& snap-feedback {:shapes shapes
                         :page-id page-id
                         :filter-shapes filter-shapes
                         :zoom zoom}])))

