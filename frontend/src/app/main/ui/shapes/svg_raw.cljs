;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.svg-raw
  (:require
   [app.common.data :as cd]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.shapes.attrs :as usa]
   [app.util.data :as ud]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

;; Graphic tags
(defonce graphic-element? #{:circle :ellipse :image :line :path :polygon :polyline :rect :text :use})

;; Context to store a re-mapping of the ids
(def svg-ids-ctx (mf/create-context nil))

(defn set-styles [attrs shape]
  (let [custom-attrs (usa/extract-style-attrs shape)
        attrs (cond-> attrs
                (string? (:style attrs)) usvg/clean-attrs)
        style (obj/merge! (clj->js (:style attrs {}))
                          (obj/get custom-attrs "style"))]
    (-> (clj->js attrs)
        (obj/merge! custom-attrs)
        (obj/set! "style" style))))

(defn translate-shape [attrs shape]
  (let [transform (str (usvg/svg-transform-matrix shape)
                       " "
                       (:transform attrs ""))]
    (cond-> attrs
      (and (:svg-viewbox shape) (graphic-element? (-> shape :content :tag)))
      (assoc :transform transform))))

(mf/defc svg-root
  {::mf/wrap-props false}
  [props]

  (let [shape    (unchecked-get props "shape")
        children (unchecked-get props "children")

        {:keys [x y width height]} shape
        {:keys [tag attrs] :as content} (:content shape)

        ids-mapping (mf/use-memo #(usvg/generate-id-mapping content))

        attrs (-> (set-styles attrs shape)
                  (obj/set! "x" x)
                  (obj/set! "y" y)
                  (obj/set! "width" width)
                  (obj/set! "height" height)
                  (obj/set! "preserveAspectRatio" "none"))]

    [:& (mf/provider svg-ids-ctx) {:value ids-mapping}
     [:g.svg-raw {:transform (gsh/transform-matrix shape)}
      [:> "svg" attrs children]]]))

(mf/defc svg-element
  {::mf/wrap-props false}
  [props]
  (let [shape    (unchecked-get props "shape")
        children (unchecked-get props "children")

        {:keys [content]} shape
        {:keys [attrs tag]} content

        ids-mapping (mf/use-ctx svg-ids-ctx)

        attrs (mf/use-memo #(usvg/replace-attrs-ids attrs ids-mapping))

        attrs (translate-shape attrs shape)
        element-id (get-in content [:attrs :id])
        attrs (cond-> (set-styles attrs shape)
                (and element-id (contains? ids-mapping element-id))
                (obj/set! "id" (get ids-mapping element-id)))

        {:keys [x y width height]} (:selrect shape)]
    [:> (name tag) attrs children]))

(defn svg-raw-shape [shape-wrapper]
  (mf/fnc svg-raw-shape
    {::mf/wrap-props false}
    [props]

    (let [frame  (unchecked-get props "frame")
          shape  (unchecked-get props "shape")
          childs (unchecked-get props "childs")

          {:keys [content]} shape
          {:keys [tag]} content

          svg-root? (and (map? content) (= tag :svg))
          svg-tag?  (map? content)
          svg-leaf? (string? content)]

      (cond
        svg-root?
        [:& svg-root {:shape shape}
         (for [item childs]
           [:& shape-wrapper {:frame frame :shape item :key (:id item)}])]

        svg-tag?
        [:& svg-element {:shape shape}
         (for [item childs]
           [:& shape-wrapper {:frame frame :shape item :key (:id item)}])]

        svg-leaf?
        content

        :else nil))))


