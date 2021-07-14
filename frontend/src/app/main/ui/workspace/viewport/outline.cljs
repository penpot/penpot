;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.outline
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.main.refs :as refs]
   [app.util.object :as obj]
   [app.util.path.format :as upf]
   [clojure.set :as set]
   [rumext.alpha :as mf]
   [rumext.util :refer [map->obj]]))

(mf/defc outline
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")
        zoom (obj/get props "zoom" 1)

        color (unchecked-get props "color")
        transform (gsh/transform-matrix shape)
        path? (= :path (:type shape))
        path-data
        (mf/use-memo
         (mf/deps shape)
         #(when path? (upf/format-path (:content shape))))

        {:keys [x y width height selrect]} shape

        outline-type (case (:type shape)
                       :circle "ellipse"
                       :path "path"
                       "rect")

        common {:fill "none"
                :stroke color
                :strokeWidth (/ 1 zoom)
                :pointerEvents "none"
                :transform transform}

        props (case (:type shape)
                :circle
                {:cx (+ x (/ width 2))
                 :cy (+ y (/ height 2))
                 :rx (/ width 2)
                 :ry (/ height 2)}

                :path
                {:d path-data
                 :transform nil}

                {:x (:x selrect)
                 :y (:y selrect)
                 :width (:width selrect)
                 :height (:height selrect)})]

    [:> outline-type (map->obj (merge common props))]))

(mf/defc shape-outlines-render
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["shapes" "zoom"]))]}
  [props]
  (let [shapes (obj/get props "shapes")
        zoom   (obj/get props "zoom")
        color  (if (or (> (count shapes) 1) (nil? (:shape-ref (first shapes))))
                 "#31EFB8" "#00E0FF")]
    (for [shape shapes]
      [:& outline {:key (str "outline-" (:id shape))
                   :shape (gsh/transform-shape shape)
                   :zoom zoom
                   :color color}])))

(mf/defc shape-outlines
  {::mf/wrap-props false}
  [props]
  (let [selected  (or (obj/get props "selected") #{})
        hover     (or (obj/get props "hover") #{})
        objects   (obj/get props "objects")
        edition   (obj/get props "edition")
        zoom      (obj/get props "zoom")

        transform (mf/deref refs/current-transform)

        outlines-ids  (->> (set/union selected hover)
                           (cp/clean-loops objects))

        show-outline? (fn [shape] (and (not (:hidden shape))
                                       (not (:blocked shape))))

        shapes (->> outlines-ids
                    (filter #(not= edition %))
                    (map #(get objects %))
                    (filterv show-outline?)
                    (filter some?))]

    [:g.outlines {:display (when (some? transform) "none")}
     [:& shape-outlines-render {:shapes shapes
                                :zoom zoom}]]))
