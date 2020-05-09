(ns uxbox.main.ui.workspace.snap-feedback
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [uxbox.main.refs :as refs]
   [uxbox.util.geom.snap :as snap]
   [uxbox.util.geom.point :as gpt]))

(def ^:private line-color "#D383DA")

(mf/defc snap-point [{:keys [point]}]
  (let [{:keys [x y]} point
        cross-width 3]
    [:g
     [:line {:x1 (- x cross-width)
             :y1 (- y cross-width)
             :x2 (+ x cross-width)
             :y2 (+ y cross-width)
             :style {:stroke line-color :stroke-width "1"}}]
     [:line {:x1 (- x cross-width)
             :y1 (+ y cross-width)
             :x2 (+ x cross-width)
             :y2 (- y cross-width)
             :style {:stroke line-color :stroke-width "1"}}]]))

(mf/defc snap-line [{:keys [snap point]}]
  [:line {:x1 (:x snap)
          :y1 (:y snap)
          :x2 (:x point)
          :y2 (:y point)
          :style {:stroke line-color :stroke-width "1"}
          :opacity 0.4}])

(defn get-snap [coord {:keys [shapes page-id filter-shapes]}]
  (->> (rx/from shapes)
       (rx/flat-map (fn [shape]
                      (->> (snap/shape-snap-points shape)
                           (map #(vector (:frame-id shape) %)))))
       (rx/flat-map (fn [[frame-id point]]
                      (->> (snap/get-snap-points page-id frame-id filter-shapes point coord)
                           (rx/map #(vector point % coord)))))
       (rx/reduce conj [])))

(mf/defc snap-feedback-points
  [{:keys [shapes page-id filter-shapes] :as props}]
  (let [state (mf/use-state [])
        subject (mf/use-memo #(rx/subject))]

    (mf/use-effect
     (fn []
       (->> subject
            (rx/switch-map #(rx/combine-latest
                             concat
                             (get-snap :y %)
                             (get-snap :x %)))
            (rx/subs #(reset! state %)))))

    (mf/use-effect
     (mf/deps shapes)
     (fn []
       (rx/push! subject props)))

    [:g.snap-feedback
     (for [[point snaps coord] @state]
       (if (not-empty snaps)
         [:g.point {:key (str "point-" (:x point) "-" (:y point)  "-" (name coord))}
          [:& snap-point {:key (str "point-" (:x point) "-" (:y point)  "-" (name coord))
                          :point point}]

          (for [snap snaps]
            [:& snap-point {:key (str "snap-" (:x point) "-" (:y point) "-" (:x snap) "-" (:y snap) "-" (name coord))
                            :point snap}])

          (for [snap snaps]
            [:& snap-line {:key (str "line-" (:x point) "-" (:y point) "-" (:x snap) "-" (:y snap) "-" (name coord))
                           :snap snap
                           :point point}])]))]))

(mf/defc snap-feedback [{:keys []}]
  (let [page-id (mf/deref refs/workspace-page-id)
        selected (mf/deref refs/selected-shapes)
        selected-shapes (mf/deref (refs/objects-by-id selected))
        drawing (mf/deref refs/current-drawing-shape)
        filter-shapes (mf/deref refs/selected-shapes-with-children)
        current-transform (mf/deref refs/current-transform)
        snap-data (mf/deref refs/workspace-snap-data)
        shapes (if drawing [drawing] selected-shapes)]
    (when (or drawing current-transform) 
        [:& snap-feedback-points {:shapes shapes
                                  :page-id page-id
                                  :filter-shapes filter-shapes}])))

