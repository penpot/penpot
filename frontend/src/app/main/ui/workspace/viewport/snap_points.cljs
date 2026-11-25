;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.viewport.snap-points
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.snap :as sp]
   [app.common.types.shape.layout :as ctl]
   [app.main.snap :as snap]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private line-color "var(--color-accent-quaternary)")
(def ^:private line-opacity 0.6)
(def ^:private line-width 1)

;; Configuration for debug
;; (def ^:private line-color "red")
;; (def ^:private line-opacity 1 )
;; (def ^:private line-width 2)

(mf/defc snap-point*
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

(mf/defc snap-line*
  [{:keys [snap point zoom]}]
  [:line {:x1 (:x snap)
          :y1 (:y snap)
          :x2 (:x point)
          :y2 (:y point)
          :style {:stroke line-color :stroke-width (str (/ line-width zoom))}
          :opacity line-opacity}])

(defn- flip
  "Function that reverses the x/y coordinates to their counterpart"
  [coord]
  (if (= coord :x) :y :x))

(defn- add-point-to-snaps
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

(mf/defc snap-feedback*
  {::mf/private true}
  [{:keys [shapes remove-snap zoom modifiers page-id compact]}]
  (let [state (mf/use-state [])
        subject (mf/use-memo #(rx/subject))

        ;; We use sets to store points/lines so there are no points/lines repeated
        ;; can cause problems with react keys
        data        (deref state)
        snap-points (mf/with-memo [data] (into #{} (mapcat add-point-to-snaps) data))
        snap-lines  (mf/with-memo [data]
                      (into (set (process-snap-lines data :x))
                            (process-snap-lines data :y)))]
    (mf/use-effect
     (fn []
       (let [sub (->> subject
                      (rx/switch-map
                       (fn [{:keys [shapes page-id remove-snap zoom compact]}]
                         (let [points (sp/rect->snap-points (gsh/shapes->rect shapes))]
                           (snap/get-feedback-snaps page-id (snap/snap-frame-id shapes)
                                                    remove-snap zoom points compact))))
                      (rx/subs! #(reset! state %)))]

         ;; On unmount callback
         #(rx/dispose! sub))))

    (mf/use-effect
     (mf/deps shapes remove-snap modifiers page-id zoom compact)
     (fn []
       (rx/push! subject {:shapes shapes
                          :page-id page-id
                          :remove-snap remove-snap
                          :zoom zoom :compact compact})))

    [:g.snap-feedback
     (for [[from-point to-point] snap-lines]
       [:> snap-line* {:key (str "line-" (:x from-point)
                                 "-" (:y from-point)
                                 "-" (:x to-point)
                                 "-" (:y to-point) "-")
                       :snap from-point
                       :point to-point
                       :zoom zoom}])
     (for [point snap-points]
       [:> snap-point* {:key (str "point-" (:x point)
                                  "-" (:y point))
                        :point point
                        :zoom zoom}])]))

(mf/defc snap-points*
  {::mf/wrap [mf/memo]}
  [{:keys [layout zoom objects base-objects selected page-id drawing focus]}]
  (dm/assert! (set? selected))
  (let [shapes  (into [] (keep (d/getf objects)) selected)

        filter-shapes
        (mf/with-memo [base-objects selected]
          (into #{} (mapcat #(cfh/get-children-ids-with-self base-objects %)) selected))

        remove-snap-base?
        (mf/with-memo [layout filter-shapes objects focus]
          (snap/make-remove-snap layout filter-shapes objects focus))

        remove-snap
        (mf/use-callback
         (mf/deps remove-snap-base?)
         (fn [snap]
           (or (remove-snap-base? snap)
               (sp/hidden-feedback? snap))))

        compact   (mf/with-memo [layout filter-shapes focus]
                    (snap/compact-query :feedback layout filter-shapes focus))
        shapes    (if drawing [drawing] shapes)
        frame-id (snap/snap-frame-id shapes)]
    (when-not (ctl/any-layout? objects frame-id)
      [:> snap-feedback* {:shapes shapes
                          :page-id page-id
                          :remove-snap remove-snap
                          :compact compact
                          :zoom zoom}])))

