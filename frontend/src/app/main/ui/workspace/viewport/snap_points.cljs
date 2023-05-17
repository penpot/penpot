;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.snap-points
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.main.snap :as snap]
   [app.util.geom.snap-points :as sp]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private line-color "var(--color-snap)")
(def ^:private line-opacity 0.6)
(def ^:private line-width 1)

;; Configuration for debug
;; (def ^:private line-color "red")
;; (def ^:private line-opacity 1 )
;; (def ^:private line-width 2)

(mf/defc snap-point
  [{:keys [point zoom]}]
  (let [{:keys [x y]} point
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
  [:line {:x1 (:x snap)
          :y1 (:y snap)
          :x2 (:x point)
          :y2 (:y point)
          :style {:stroke line-color :stroke-width (str (/ line-width zoom))}
          :opacity line-opacity}])

(defn get-snap
  [coord {:keys [shapes page-id remove-snap? zoom]}]
  (let [bounds (gsh/selection-rect shapes)
        frame-id  (snap/snap-frame-id shapes)]

    (->> (rx/of bounds)
         (rx/flat-map
          (fn [bounds]
            (->> (sp/selrect-snap-points bounds)
                 (map #(vector frame-id %)))))

         (rx/flat-map
          (fn [[frame-id point]]
            (->> (snap/get-snap-points page-id frame-id remove-snap? zoom point coord)
                 (rx/map #(mapcat second %))
                 (rx/map #(map :pt %))
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
  [{:keys [shapes remove-snap? zoom modifiers] :as props}]
  (let [state (mf/use-state [])
        subject (mf/use-memo #(rx/subject))

        ;; We use sets to store points/lines so there are no points/lines repeated
        ;; can cause problems with react keys
        snap-points (into #{} (mapcat add-point-to-snaps) @state)

        snap-lines (->> (into (process-snap-lines @state :x)
                              (process-snap-lines @state :y))
                        (into #{}))]
    (mf/use-effect
     (fn []
       (let [sub (->> subject
                      (rx/switch-map
                       (fn [props]
                         (->> (get-snap :y props)
                              (rx/combine-latest (get-snap :x props)))))

                      (rx/map
                       (fn [result]
                         (apply d/concat-vec (seq result))))

                      (rx/subs
                       (fn [data]
                         (let [rs (filter (fn [[_ snaps _]] (> (count snaps) 0)) data)]
                           (reset! state rs)))))]

         ;; On unmount callback
         #(rx/dispose! sub))))

    (mf/use-effect
     (mf/deps shapes remove-snap? modifiers)
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
  [{:keys [layout zoom objects selected page-id drawing focus] :as props}]
  (dm/assert! (set? selected))
  (let [shapes  (into [] (keep (d/getf objects)) selected)

        filter-shapes
        (into selected (mapcat #(cph/get-children-ids objects %)) selected)

        remove-snap-base?
        (mf/with-memo [layout filter-shapes objects focus]
          (snap/make-remove-snap layout filter-shapes objects focus))

        remove-snap?
        (mf/use-callback
         (mf/deps remove-snap-base?)
         (fn [{:keys [type grid] :as snap}]
           (or (remove-snap-base? snap)
               (and (= type :layout) (= grid :square))
               (= type :guide))))

        shapes    (if drawing [drawing] shapes)
        frame-id (snap/snap-frame-id shapes)]
    (when-not (ctl/any-layout? objects frame-id)
      [:& snap-feedback {:shapes shapes
                         :page-id page-id
                         :remove-snap? remove-snap?
                         :zoom zoom}])))

