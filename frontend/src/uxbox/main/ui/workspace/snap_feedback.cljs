(ns uxbox.main.ui.workspace.snap-feedback
  (:require
   [rumext.alpha :as mf]
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

(mf/defc snap-feedback []
  (let [selected (mf/deref refs/selected-shapes)
        selected-shapes (mf/deref (refs/objects-by-id selected))
        drawing (mf/deref refs/current-drawing-shape)
        filter-shapes (mf/deref refs/selected-shapes-with-children)
        current-transform (mf/deref refs/current-transform)
        snap-data (mf/deref refs/workspace-snap-data)
        shapes (if drawing [drawing] selected-shapes)]
    (when (or drawing current-transform)
      (for [shape shapes]
        (for [point (snap/shape-snap-points shape)]
          (let [frame-id (:frame-id shape)
                shape-id (:id shape)
                snaps (into #{}
                            (concat 
                             (snap/get-snap-points snap-data frame-id filter-shapes point :x)
                             (snap/get-snap-points snap-data frame-id filter-shapes point :y)))]
            (if (not-empty snaps)
              [:* {:key (str "point-" (:id shape) "-" (:x point) "-" (:y point))}
               [:& snap-point {:point point}]

               (for [snap snaps]
                 [:& snap-point {:key (str "snap-" (:id shape) "-" (:x point) "-" (:y point) "-" (:x snap) "-" (:y snap))
                                 :point snap}])

               (for [snap snaps]
                 [:& snap-line {:key (str "line-" (:id shape) "-" (:x point) "-" (:y point) "-" (:x snap) "-" (:y snap))
                                :snap snap
                                :point point}])])))))))

