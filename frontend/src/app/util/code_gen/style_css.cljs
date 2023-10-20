;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen.style-css
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.geom.shapes.points :as gpo]
   [app.common.pages.helpers :as cph]
   [app.common.text :as txt]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.shapes.text.styles :as sts]
   [app.util.code-gen.common :as cgc]
   [app.util.code-gen.style-css-formats :refer [format-value]]
   [app.util.code-gen.style-css-values :refer [get-value]]
   [cuerdas.core :as str]))

;;
;; Common styles to display always. Will be attached as a prelude to the generated CSS
;;
(def prelude "
html, body {
  margin: 0;
  min-height: 100%;
  min-width: 100%;
  padding: 0;
}

body {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 2rem;
  gap: 2rem;
}

* {
  box-sizing: border-box;
}

.text-node { background-clip: text !important; -webkit-background-clip: text !important; }

")

(def shape-wrapper-css-properties
  #{:flex-shrink
    :margin
    :max-height
    :min-height
    :max-width
    :min-width
    :align-self
    :justify-self
    :grid-column
    :grid-row
    :z-index
    :top
    :left
    :position})

(def shape-css-properties
  [:position
   :left
   :top
   :width
   :height
   :transform
   :background
   :background-color
   :background-image
   :border
   :border-radius
   :box-shadow
   :filter
   :opacity
   :overflow

   ;; Flex/grid related properties
   :display
   :align-items
   :align-content
   :justify-items
   :justify-content
   :gap
   :column-gap
   :row-gap
   :padding
   :z-index

   ;; Flex related properties
   :flex-direction
   :flex-wrap
   :flex

   ;; Grid related properties
   :grid-template-rows
   :grid-template-columns
   :grid-template-areas

   ;; Flex/grid self properties
   :flex-shrink
   :margin
   :max-height
   :min-height
   :max-width
   :min-width
   :align-self
   :justify-self

   ;; Grid cell properties
   :grid-column
   :grid-row
   :grid-area])

(def text-node-css-properties
  [:font-family
   :font-style
   :font-size
   :font-weight
   :line-height
   :letter-spacing
   :text-decoration
   :text-transform
   :color])

(defn shape->css-property
  [shape objects property]
  (when-let [value (get-value property shape objects)]
    [property value]))

(defn shape->wrapper-css-properties
  [shape objects]
  (when (and (ctl/any-layout-immediate-child? objects shape)
             (not (gmt/unit? (:transform shape))))
    (let [parent (get objects (:parent-id shape))
          bounds (gpo/parent-coords-bounds (:points shape) (:points parent))
          width  (gpo/width-points bounds)
          height (gpo/height-points bounds)]
      (cond-> [[:width width]
               [:height height]]

        (or (not (ctl/any-layout-immediate-child? objects shape))
            (not (ctl/layout-absolute? shape)))
        (conj [:position "relative"])))))

(defn shape->wrapper-child-css-properties
  [shape objects]
  (when (and (ctl/any-layout-immediate-child? objects shape) (not (gmt/unit? (:transform shape))))
    [[:position "absolute"]
     [:left "50%"]
     [:top "50%"]]))

(defn shape->svg-props
  [shape objects]
  (let [bounds (gsb/get-object-bounds objects shape)]
    [[:position "absolute"]
     [:top 0]
     [:left 0]
     [:transform (dm/fmt "translate(%,%)"
                         (dm/str (- (:x bounds) (-> shape :selrect :x)) "px")
                         (dm/str (- (:y bounds) (-> shape :selrect :y)) "px"))]]))

(defn shape->css-properties
  "Given a shape extract the CSS properties in the format of list [property value]"
  [shape objects properties]
  (->> properties
       (keep (fn [property]
               (when-let [value (get-value property shape objects)]
                 [property value])))))

(defn format-css-value
  ([[property value] options]
   (format-css-value property value options))

  ([property value options]
   (when (some? value)
     (format-value property value options))))

(defn format-css-property
  [[property value] options]
  (when (some? value)
    (let [formatted-value (format-css-value property value options)]
      (dm/fmt "%: %;" (d/name property) formatted-value))))

(defn format-css-properties
  "Format a list of [property value] into a list of css properties in the format 'property: value;'"
  [properties options]
  (when properties
    (->> properties
         (map #(dm/str "  " (format-css-property % options)))
         (str/join "\n"))))

(defn get-shape-properties-css
  ([objects shape properties]
   (get-shape-properties-css objects shape properties nil))

  ([objects shape properties options]
   (-> shape
       (shape->css-properties objects properties)
       (format-css-properties options))))

(defn format-js-styles
  [properties _options]
  (format-css-properties
   (->> (.keys js/Object properties)
        (remove #(str/starts-with? % "--"))
        (mapv (fn [key]
                [(str/kebab key) (unchecked-get properties key)])))
   nil))

(defn node->css
  [shape shape-selector node]
  (let [properties
        (case (:type node)
          (:root "root")
          (sts/generate-root-styles shape node)

          (:paragraph-set "paragraph-set")
          (sts/generate-paragraph-set-styles shape)

          (:paragraph "paragraph")
          (sts/generate-paragraph-styles shape node)

          (sts/generate-text-styles shape node))]
    (dm/fmt
     ".% {\n%\n}"
     (dm/str shape-selector " ." (:$id node))
     (format-js-styles properties nil))))

(defn generate-text-css
  [shape]
  (let [selector (cgc/shape->selector shape)]
    (->> shape
         :content
         (txt/index-content)
         (txt/node-seq)
         (map #(node->css shape selector %))
         (str/join "\n"))))

(defn get-shape-css-selector
  ([objects shape]
   (get-shape-css-selector shape objects nil))

  ([shape objects options]
   (when (and (some? shape) (some? (:selrect shape)))
     (let [selector (cgc/shape->selector shape)

           wrapper? (cgc/has-wrapper? objects shape)
           svg?     (cgc/svg-markup? shape)

           css-properties
           (if wrapper?
             (filter (complement shape-wrapper-css-properties) shape-css-properties)
             shape-css-properties)

           properties
           (-> shape
               (shape->css-properties objects css-properties)
               (format-css-properties options))

           wrapper-properties
           (when wrapper?
             (-> (d/concat-vec
                  (shape->css-properties shape objects shape-wrapper-css-properties)
                  (shape->wrapper-css-properties shape objects))
                 (format-css-properties options)))

           wrapper-child-properties
           (when wrapper?
             (-> shape
                 (shape->wrapper-child-css-properties objects)
                 (format-css-properties options)))

           svg-child-props
           (when svg?
             (-> shape
                 (shape->svg-props objects)
                 (format-css-properties options)))]

       (str/join
        "\n"
        (filter some? [(str/fmt "/* %s */" (:name shape))
                       (when wrapper? (str/fmt ".%s-wrapper {\n%s\n}" selector wrapper-properties))
                       (when wrapper? (str/fmt ".%s-wrapper > * {\n%s\n}" selector wrapper-child-properties))
                       (when svg?     (str/fmt ".%s > svg {\n%s\n}" selector svg-child-props))
                       (str/fmt ".%s {\n%s\n}" selector properties)
                       (when (cph/text-shape? shape) (generate-text-css shape))]))))))

(defn get-css-property
  ([objects shape property]
   (get-css-property objects shape property nil))

  ([objects shape property options]
   (-> shape
       (shape->css-property objects property)
       (format-css-property options))))

(defn get-css-value
  ([objects shape property]
   (get-css-value objects shape property nil))

  ([objects shape property options]
   (when-let [prop (shape->css-property shape objects property)]
     (format-css-value prop options))))

(defn generate-style
  ([objects shapes]
   (generate-style objects shapes nil))
  ([objects shapes options]
   (dm/str
    prelude
    (->> shapes
         (keep #(get-shape-css-selector % objects options))
         (str/join "\n\n")))))
