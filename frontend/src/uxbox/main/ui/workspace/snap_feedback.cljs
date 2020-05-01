(ns uxbox.main.ui.workspace.snap-feedback
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.util.geom.snap :as snap]
   [uxbox.util.geom.point :as gpt]))

(def ^:private line-color "#D383DA")

(mf/defc snap-feedback []
  (let [selected (mf/deref refs/selected-shapes)
        shapes (mf/deref (refs/objects-by-id selected))
        filter-shapes (mf/deref refs/selected-shapes-with-children)
        current-transform (mf/deref refs/current-transform)
        snap-data (mf/deref refs/workspace-snap-data)]
    (when (not (nil? current-transform))
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
              [:circle {:cx (:x point)
                        :cy (:y point)
                        :r 2
                        :fill line-color}]

              (for [snap snaps]
                [:circle {:key (str "snap-" (:id shape) "-" (:x point) "-" (:y point) "-" (:x snap) "-" (:y snap))
                          :cx (:x snap)
                          :cy (:y snap)
                          :r 2
                          :fill line-color}])

              (for [snap snaps]
                [:line {:key (str "line-" (:id shape) "-" (:x point) "-" (:y point) "-" (:x snap) "-" (:y snap))
                        :x1 (:x snap)
                        :y1 (:y snap)
                        :x2 (:x point)
                        :y2 (:y point)
                        :style {:stroke line-color :stroke-width "1"}
                        :opacity 0.4}])])))))))

