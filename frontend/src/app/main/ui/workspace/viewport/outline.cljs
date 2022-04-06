;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.outline
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.util.object :as obj]
   [app.util.path.format :as upf]
   [clojure.set :as set]
   [rumext.alpha :as mf]))

(mf/defc outline
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")
        zoom  (obj/get props "zoom" 1)
        color (obj/get props "color")

        {:keys [x y width height selrect type content]} shape

        path-data
        (mf/with-memo [shape]
          (when ^boolean (cph/path-shape? shape)
            (or (ex/ignoring (upf/format-path content)) "")))

        outline-type
        (case type
          :circle "ellipse"
          :path "path"
          "rect")

        props
        (mf/with-memo [shape]
          (let [transform (gsh/transform-matrix shape)
                props #js {:fill "none"
                           :stroke color
                           :strokeWidth (/ 1 zoom)
                           :pointerEvents "none"
                           :transform transform}]
            (case type
              :circle
              (obj/merge! props
                          #js {:cx (+ x (/ width 2))
                               :cy (+ y (/ height 2))
                               :rx (/ width 2)
                               :ry (/ height 2)})
              :path
              (obj/merge! props
                          #js {:d path-data
                               :transform nil})

              (obj/merge! props
                          #js {:x (:x selrect)
                               :y (:y selrect)
                               :width (:width selrect)
                               :height (:height selrect)}))))]

    [:> outline-type props]))

(mf/defc shape-outlines-render
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["shapes" "zoom"]))]}
  [props]
  (let [shapes (obj/get props "shapes")
        zoom   (obj/get props "zoom")
        color  (if (or (> (count shapes) 1)
                       (nil? (:shape-ref (first shapes))))
                 "var(--color-primary)"
                 "var(--color-component-highlight)")]
    (for [shape shapes]
      [:& outline {:key (str "outline-" (:id shape))
                   :shape (gsh/transform-shape shape)
                   :zoom zoom
                   :color color}])))

(mf/defc shape-outlines
  {::mf/wrap-props false}
  [props]
  (let [selected     (or (obj/get props "selected") #{})
        hover        (or (obj/get props "hover") #{})
        objects      (obj/get props "objects")
        edition      (obj/get props "edition")
        zoom         (obj/get props "zoom")

        transform    (mf/deref refs/current-transform)

        selected     (hooks/use-equal-memo selected)
        hover        (hooks/use-equal-memo hover)

        outline-ids  (mf/with-memo [selected hover]
                       (->> (set/union selected hover)
                            (cph/clean-loops objects)))

        show-outline?
        (mf/use-fn
         (fn [shape]
           (and (not (:hidden shape))
                (not (:blocked shape)))))

        shapes (into []
                     (comp
                      (filter #(not= edition %))
                      (keep (d/getf objects))
                      (filter show-outline?))
                     outline-ids)]

    [:g.outlines {:display (when (some? transform) "none")}
     [:& shape-outlines-render
      {:shapes shapes
       :zoom zoom}]]))
