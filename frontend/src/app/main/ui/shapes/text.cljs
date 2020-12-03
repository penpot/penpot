;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.text
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.group :refer [mask-id-ctx]]
   [app.common.data :as d]
   [app.common.geom.shapes :as geom]
   [app.common.geom.matrix :as gmt]
   [app.util.object :as obj]
   [app.util.color :as uc]
   [app.main.ui.shapes.text.styles :as sts]
   [app.main.ui.shapes.text.embed :as ste]))

;; -- Text nodes
(mf/defc text-node
  [{:keys [node index shape] :as props}]
  (let [embed-resources? (mf/use-ctx muc/embed-ctx)
        {:keys [type text children]} node
        props #js {:shape shape}
        render-node
        (fn [index node]
          (mf/element text-node {:index index
                                 :node node
                                 :key index
                                 :shape shape}))]

    (if (string? text)
      (let [style (sts/generate-text-styles (clj->js node) props)]
        [:span {:style style
                :className (when (:fill-color-gradient node) "gradient")}
         (if (= text "") "\u00A0" text)])

      (let [children (map-indexed render-node children)]
        (case type
          "root"
          (let [style (sts/generate-root-styles (clj->js node) props)]
            [:div.root.rich-text
             {:key index
              :style style
              :xmlns "http://www.w3.org/1999/xhtml"}
             [:*
              [:style ".gradient { background: var(--text-color); -webkit-text-fill-color: transparent; -webkit-background-clip: text;"]
              (when embed-resources?
                [ste/embed-fontfaces-style {:node node}])]
             children])

          "paragraph-set"
          (let [style (sts/generate-paragraph-set-styles (clj->js node) props)]
            [:div.paragraph-set {:key index :style style} children])

          "paragraph"
          (let [style (sts/generate-paragraph-styles (clj->js node) props)]
            [:p.paragraph {:key index :style style} children])

          nil)))))

(mf/defc text-content
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [root (obj/get props "content")
        shape (obj/get props "shape")]
    [:& text-node {:index 0
                   :node root
                   :shape shape}]))

(defn- retrieve-colors
  [shape]
  (let [colors (->> shape :content
                    (tree-seq map? :children)
                    (into #{} (comp (map :fill) (filter string?))))]
    (if (empty? colors)
      "#000000"
      (apply str (interpose "," colors)))))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [shape     (unchecked-get props "shape")
        selected? (unchecked-get props "selected?")
        grow-type (:grow-type shape)
        mask-id   (mf/use-ctx mask-id-ctx)
        {:keys [id x y width height content]} shape]
    [:foreignObject {:x x
                     :y y
                     :id (:id shape)
                     :data-colors (retrieve-colors shape)
                     :transform (geom/transform-matrix shape)
                     :width  (if (#{:auto-width} grow-type) 10000 width)
                     :height (if (#{:auto-height :auto-width} grow-type) 10000 height)
                     :mask mask-id
                     :ref ref}
     [:& text-content {:shape shape
                       :content (:content shape)}]]))
