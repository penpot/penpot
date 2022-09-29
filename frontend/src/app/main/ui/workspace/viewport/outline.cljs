;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.outline
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.util.object :as obj]
   [app.util.path.format :as upf]
   [clojure.set :as set]
   [rumext.v2 :as mf]
   [rumext.v2.util :refer [map->obj]]))

(mf/defc outline
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")
        zoom (obj/get props "zoom" 1)

        color (unchecked-get props "color")
        transform (gsh/transform-str shape)
        path? (= :path (:type shape))
        path-data
        (mf/use-memo
         (mf/deps shape)
         #(when path?
            (or (ex/ignoring (upf/format-path (:content shape)))
                "")))

        {:keys [x y width height selrect]} shape

        outline-type (case (:type shape)
                       :circle "ellipse"
                       :path "path"
                       "rect")

        common {:fill "none"
                :stroke color
                :strokeWidth (/ 2 zoom)
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
                 "var(--color-primary)" "var(--color-component-highlight)")]
    (for [shape shapes]
      [:& outline {:key (str "outline-" (:id shape))
                   :shape shape
                   :zoom zoom
                   :color color}])))

(defn- show-outline?
  [shape]
  (and (not (:hidden shape))
       (not (:blocked shape))))

(mf/defc shape-outlines
  {::mf/wrap-props false}
  [props]
  (let [selected    (or (obj/get props "selected") #{})
        hover       (or (obj/get props "hover") #{})
        highlighted (or (obj/get props "highlighted") #{})

        objects     (obj/get props "objects")
        edition     (obj/get props "edition")
        zoom        (obj/get props "zoom")

        lookup      (d/getf objects)
        edition?    (fn [o] (= edition o))

        shapes      (-> #{}
                        (into (comp (remove edition?)
                                    (keep lookup)
                                    (filter show-outline?))
                              (set/union selected hover))
                        (into (comp (remove edition?)
                                    (keep lookup))
                              highlighted))]

    [:g.outlines
     [:& shape-outlines-render {:shapes shapes :zoom zoom}]]))
