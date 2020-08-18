(ns app.main.ui.workspace.snap-points
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [app.main.refs :as refs]
   [app.main.snap :as snap]
   [app.util.geom.snap-points :as sp]
   [app.common.geom.point :as gpt]))

(def ^:private line-color "#D383DA")

(mf/defc snap-point
  [{:keys [point zoom]}]
  (let [{:keys [x y]} point
        cross-width (/ 3 zoom)]
    [:g
     [:line {:x1 (- x cross-width)
             :y1 (- y cross-width)
             :x2 (+ x cross-width)
             :y2 (+ y cross-width)
             :style {:stroke line-color :stroke-width (str (/ 1 zoom))}}]
     [:line {:x1 (- x cross-width)
             :y1 (+ y cross-width)
             :x2 (+ x cross-width)
             :y2 (- y cross-width)
             :style {:stroke line-color :stroke-width (str (/ 1 zoom))}}]]))

(mf/defc snap-line
  [{:keys [snap point zoom]}]
  [:line {:x1 (:x snap)
          :y1 (:y snap)
          :x2 (:x point)
          :y2 (:y point)
          :style {:stroke line-color :stroke-width (str (/ 1 zoom))}
          :opacity 0.4}])

(defn get-snap
  [coord {:keys [shapes page-id filter-shapes]}]
  (->> (rx/from shapes)
       (rx/flat-map (fn [shape]
                      (->> (sp/shape-snap-points shape)
                           (map #(vector (:frame-id shape) %)))))
       (rx/flat-map (fn [[frame-id point]]
                      (->> (snap/get-snap-points page-id frame-id filter-shapes point coord)
                           (rx/map #(vector point % coord)))))
       (rx/reduce conj [])))

(mf/defc snap-feedback
  [{:keys [shapes page-id filter-shapes zoom] :as props}]
  (let [state (mf/use-state [])
        subject (mf/use-memo #(rx/subject))

        ;; We use sets to store points/lines so there are no points/lines repeated
        ;; can cause problems with react keys
        snap-points (into #{} (mapcat (fn [[point snaps coord]]
                                        (cons point snaps))
                                      @state))
        snap-lines (into #{} (mapcat (fn [[point snaps coord]]
                                       (when (not-empty snaps) (map #(vector point %) snaps))) @state))]

    (mf/use-effect
     (fn []
       (let [sub
             (->> subject
                  (rx/switch-map #(rx/combine-latest
                                   concat
                                   (get-snap :y %)
                                   (get-snap :x %)))
                  (rx/subs #(reset! state %)))]

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

