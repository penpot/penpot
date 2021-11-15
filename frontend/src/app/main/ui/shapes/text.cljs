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
    [:span.text-node {:style style}
     (if (= text "") "\u00A0" text)]))

(mf/defc render-root
  {::mf/wrap-props false}
  [props]
  (let [node     (obj/get props "node")
        children (obj/get props "children")
        shape    (obj/get props "shape")
        style    (sts/generate-root-styles shape node)]
    [:div.root.rich-text
     {:style style
      :xmlns "http://www.w3.org/1999/xhtml"}
     children]))

(mf/defc render-paragraph-set
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")
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

(defn- next-color
  "Given a set of colors try to get a color not yet used"
  [colors]
  (assert (set? colors))
  (loop [current-rgb [0 0 0]]
    (let [current-hex (uc/rgb->hex current-rgb)]
      (if (contains? colors current-hex)
        (recur (uc/next-rgb current-rgb))
        current-hex))))

(defn- remap-colors
  "Returns a new content replacing the original colors by their mapped 'simple color'"
  [content color-mapping]

  (cond-> content
    (and (:fill-opacity content) (< (:fill-opacity content) 1.0))
    (-> (assoc :fill-color (get color-mapping [(:fill-color content) (:fill-opacity content)]))
        (assoc :fill-opacity 1.0))

    (some? (:fill-color-gradient content))
    (-> (assoc :fill-color (get color-mapping (:fill-color-gradient content)))
        (assoc :fill-opacity 1.0)
        (dissoc :fill-color-gradient))

    (contains? content :children)
    (update :children #(mapv (fn [node] (remap-colors node color-mapping)) %))))

(defn- fill->color
  "Given a content node returns the information about that node fill color"
  [{:keys [fill-color fill-opacity fill-color-gradient]}]

  (cond
    (some? fill-color-gradient)
    {:type :gradient
     :gradient fill-color-gradient}

    (and (string? fill-color) (some? fill-opacity) (not= fill-opacity 1))
    {:type :transparent
     :hex fill-color
     :opacity fill-opacity}

    (string? fill-color)
    {:type :solid
     :hex fill-color
     :map-to fill-color}))

(defn- retrieve-colors
  "Given a text shape returns a triple with the values:
    - colors used as fills
    - a mapping from simple solid colors to complex ones (transparents/gradients)
    - the inverse of the previous mapping (to restore the value in the SVG)"
  [shape]
  (let [color-data
        (->> (:content shape)
             (tree-seq map? :children)
             (map fill->color)
             (filter some?))

        colors (->> color-data
                    (into #{"#000000"}
                          (comp (filter #(= :solid (:type %)))
                                (map :hex))))

        [colors color-data]
        (loop [colors colors
               head (first color-data)
               tail (rest color-data)
               result []]

          (if (nil? head)
            [colors result]

            (if (= :solid (:type head))
              (recur colors
                     (first tail)
                     (rest tail)
                     (conj result head))

              (let [next-color (next-color colors)
                    head (assoc head :map-to next-color)
                    colors (conj colors next-color)]
                (recur colors
                       (first tail)
                       (rest tail)
                       (conj result head))))))

        color-mapping-inverse
        (->> color-data
             (remove #(= :solid (:type %)))
             (group-by :map-to)
             (d/mapm #(first %2)))

        color-mapping
        (merge
         (->> color-data
              (filter #(= :transparent (:type %)))
              (map #(vector [(:hex %) (:opacity %)] (:map-to %)))
              (into {}))

         (->> color-data
              (filter #(= :gradient (:type %)))
              (map #(vector (:gradient %) (:map-to %)))
              (into {})))]

    [colors color-mapping color-mapping-inverse]))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [{:keys [id x y width height content] :as shape} (obj/get props "shape")
        grow-type (obj/get props "grow-type") ;; This is only needed in workspace
        ;; We add 8px to add a padding for the exporter
        ;; width (+ width 8)
        [colors color-mapping color-mapping-inverse] (retrieve-colors shape)

        plain-colors? (mf/use-ctx muc/text-plain-colors-ctx)

        content (cond-> content
                  plain-colors?
                  (remap-colors color-mapping))]

    [:foreignObject {:x x
                     :y y
                     :id id
                     :data-colors (->> colors (str/join ","))
                     :data-mapping (-> color-mapping-inverse (clj->js) (js/JSON.stringify))
                     :transform (geom/transform-matrix shape)
                     :width  (if (#{:auto-width} grow-type) 100000 width)
                     :height (if (#{:auto-height :auto-width} grow-type) 100000 height)
                     :style (-> (obj/new) (attrs/add-layer-props shape))
                     :ref ref}
     ;; We use a class here because react has a bug that won't use the appropriate selector for
     ;; `background-clip`
     [:style ".text-node { background-clip: text;
                           -webkit-background-clip: text;" ]
     [:& render-node {:index 0
                      :shape shape
                      :node content}]]))
