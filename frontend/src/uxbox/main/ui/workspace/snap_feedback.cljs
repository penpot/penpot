(ns uxbox.main.ui.workspace.snap-feedback
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.util.geom.snap :as snap]
   [uxbox.util.geom.point :as gpt]))


(def ^:private line-color "#D383DA")

(mf/defc snap-feedback
  [{:keys [shapes] :as props}]
  (let [snap-data (mf/deref refs/workspace-snap-data)]
    (for [shape shapes]
      (for [point (snap/shape-snap-points shape)]
        (let [frame-id (:frame-id shape)
              shape-id (:id shape)

              snaps-x (snap/get-snap-points snap-data frame-id shape-id point :x)
              snaps-y (snap/get-snap-points snap-data frame-id shape-id point :y)]
          (if (or (not-empty snaps-x) (not-empty snaps-y))
            [:* {:key (str "point-" (:id shape) "-" (:x point) "-" (:y point))}
             [:circle {:cx (:x point)
                       :cy (:y point)
                       :r 2
                       :fill line-color}]

             (for [snap (concat snaps-x snaps-y)]
               [:*
                [:circle {:cx (:x snap)
                          :cy (:y snap)
                          :r 2
                          :fill line-color}]
                [:line {:x1 (:x snap)
                        :y1 (:y snap)
                        :x2 (:x point)
                        :y2 (:y point)
                        :style {:stroke line-color :stroke-width "1"}
                        :opacity 0.4}]])
             
             #_(when is-snap-y?
               [:line {:x1 -10000
                       :y1 (:y point)
                       :x2 10000
                       :y2 (:y point)
                       :style {:stroke line-color :stroke-width "1"}
                       :opacity 0.4}])]))))))

