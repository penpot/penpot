;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.text
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as geom]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.text.embed :as ste]
   [app.main.ui.shapes.text.styles :as sts]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc render-text
  {::mf/wrap-props false}
  [props]
  (let [node  (obj/get props "node")
        text  (:text node)
        style (sts/generate-text-styles node)]
    [:span {:style style}
     (if (= text "") "\u00A0" text)]))

(mf/defc render-root
  {::mf/wrap-props false}
  [props]
  (let [node     (obj/get props "node")
        embed?   (obj/get props "embed-fonts?")
        children (obj/get props "children")
        shape    (obj/get props "shape")
        style    (sts/generate-root-styles shape node)]
    [:div.root.rich-text
     {:style style
      :xmlns "http://www.w3.org/1999/xhtml"}
     (when embed?
       [ste/embed-fontfaces-style {:node node}])
     children]))

(mf/defc render-paragraph-set
  {::mf/wrap-props false}
  [props]
  (let [node     (obj/get props "node")
        children (obj/get props "children")
        shape    (obj/get props "shape")
        style    (sts/generate-paragraph-set-styles shape)]
    [:div.paragraph-set {:style style} children]))

(mf/defc render-paragraph
  {::mf/wrap-props false}
  [props]
  (let [node     (obj/get props "node")
        shape    (obj/get props "shape")
        children (obj/get props "children")
        style    (sts/generate-paragraph-styles shape node)
        dir      (:text-direction node "auto")]
    [:p.paragraph {:style style :dir dir} children]))

;; -- Text nodes
(mf/defc render-node
  {::mf/wrap-props false}
  [props]
  (let [{:keys [type text children] :as node} (obj/get props "node")]
    (if (string? text)
      [:> render-text props]
      (let [component (case type
                        "root" render-root
                        "paragraph-set" render-paragraph-set
                        "paragraph" render-paragraph
                        nil)]
        (when component
          [:> component props
           (for [[index node] (d/enumerate children)]
             (let [props (-> (obj/clone props)
                             (obj/set! "node" node)
                             (obj/set! "index" index)
                             (obj/set! "key" index))]
               [:> render-node props]))])))))

(defn- retrieve-colors
  [shape]
  (let [colors (->> (:content shape)
                    (tree-seq map? :children)
                    (into #{"#000000"} (comp (map :fill-color) (filter string?))))]
    (apply str (interpose "," colors))))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [{:keys [id x y width height content] :as shape} (obj/get props "shape")
        grow-type (obj/get props "grow-type") ;; This is only needed in workspace
        embed-fonts? (mf/use-ctx muc/embed-ctx)
        ;; We add 8px to add a padding for the exporter
        ;; width (+ width 8)
        ]
    [:foreignObject {:x x
                     :y y
                     :id id
                     :data-colors (retrieve-colors shape)
                     :transform (geom/transform-matrix shape)
                     :width  (if (#{:auto-width} grow-type) 100000 width)
                     :height (if (#{:auto-height :auto-width} grow-type) 100000 height)
                     :style (-> (obj/new) (attrs/add-layer-props shape))
                     :ref ref}
     [:& render-node {:index 0
                      :shape shape
                      :node content
                      :embed-fonts? embed-fonts?}]]))
