;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.svg-raw
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.svg :as csvg]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Graphic tags
(def graphic-element
  #{:svg :circle :ellipse :image :line :path :polygon :polyline :rect :symbol :text :textPath :use})

;; Context to store a re-mapping of the ids
(def svg-ids-ctx (mf/create-context nil))

(mf/defc svg-root
  {::mf/wrap-props false}
  [props]

  (let [shape       (unchecked-get props "shape")
        children    (unchecked-get props "children")

        x           (dm/get-prop shape :x)
        y           (dm/get-prop shape :y)
        w           (dm/get-prop shape :width)
        h           (dm/get-prop shape :height)

        ids-mapping (mf/with-memo [shape]
                      (csvg/generate-id-mapping (:content shape)))

        render-id   (mf/use-ctx muc/render-id)

        props       (mf/with-memo [shape render-id]
                      (-> #js {}
                          (attrs/add-fill-props! shape render-id)
                          (obj/unset! "transform")
                          (obj/set! "x" x)
                          (obj/set! "y" y)
                          (obj/set! "width" w)
                          (obj/set! "height" h)
                          (obj/set! "preserveAspectRatio" "none")))]

    [:& (mf/provider svg-ids-ctx) {:value ids-mapping}
     [:g.svg-raw {:transform (gsh/transform-str shape)}
      [:> "svg" props children]]]))

(mf/defc svg-element
  {::mf/wrap-props false}
  [props]
  (let [shape       (unchecked-get props "shape")
        children    (unchecked-get props "children")

        ids-mapping (mf/use-ctx svg-ids-ctx)
        render-id   (mf/use-ctx muc/render-id)

        tag         (-> shape :content :tag)

        shape
        (mf/with-memo [shape ids-mapping]
          (let [tag (-> shape :content :tag)]
            (-> shape
                (update :svg-attrs csvg/replace-attrs-ids ids-mapping)
                (update :svg-attrs (fn [attrs]
                                     (if (contains? graphic-element tag)
                                       (assoc attrs :transform (str/ffmt "% %"
                                                                         (csvg/svg-transform-matrix shape)
                                                                         (:transform attrs "")))
                                       (dissoc attrs :transform)))))))

        props
        (mf/with-memo [shape render-id]
          (let [element-id (dm/get-in shape [:svg-attrs :id])
                props      (attrs/add-fill-props! #js {} shape render-id)]

            (when (and (some? element-id)
                       (contains? ids-mapping element-id))
              (obj/set! props "id" (get ids-mapping element-id)))

            props))]

    [:> (name tag) props children]))

(defn svg-raw-shape
  [shape-wrapper]
  (mf/fnc svg-raw-shape
    {::mf/wrap-props false}
    [props]

    (let [shape  (unchecked-get props "shape")
          childs (unchecked-get props "childs")

          content (get shape :content)
          tag     (get content :tag)

          svg-root?  (and (map? content) (= tag :svg))
          svg-tag?   (map? content)
          svg-leaf?  (string? content)
          valid-tag? (contains? csvg/svg-tags tag)

          current-svg-root-id (mf/use-ctx muc/current-svg-root-id)

          ;; We need to create a "scope" for svg classes. The root of the imported SVG (first group) will
          ;; be stored in the context and with this we scoped the styles:
          style-content
          (when (= tag :style)
            (dm/str "#shape-" current-svg-root-id "{ " (->> shape :content :content (str/join "\n")) " }"))]

      (cond
        (= tag :style)
        [:style style-content]

        ^boolean svg-root?
        [:& svg-root {:shape shape}
         (for [item childs]
           [:& shape-wrapper {:shape item :key (dm/str (:id item))}])]

        (and ^boolean svg-tag?
             ^boolean valid-tag?)
        [:& svg-element {:shape shape}
         (for [item childs]
           [:& shape-wrapper {:shape item :key (dm/str (:id item))}])]

        ^boolean svg-leaf?
        content))))

