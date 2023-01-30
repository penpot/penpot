;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.text :as txt]
   [app.main.ui.formats :as fmt]
   [app.util.color :as uc]
   [cuerdas.core :as str]))

(defn shadow->css [shadow]
  (let [{:keys [style offset-x offset-y blur spread]} shadow
        css-color (uc/color->background (:color shadow))]
    (dm/str
     (if (= style :inner-shadow) "inset " "")
     (str/fmt "%spx %spx %spx %spx %s" offset-x offset-y blur spread css-color))))

(defn format-gap
  [{row-gap :row-gap column-gap :column-gap}]
  (if (= row-gap column-gap)
    (str/fmt "%spx" row-gap)
    (str/fmt "%spx %spx" row-gap column-gap)))

(defn fill-color->background
  [fill]
  (uc/color->background {:color (:fill-color fill)
                         :opacity (:fill-opacity fill)
                         :gradient (:fill-color-gradient fill)}))

(defn format-fill-color [_ shape]
  (let [fills      (:fills shape)
        first-fill (first fills)
        colors     (if (> (count fills) 1)
                     (map (fn [fill]
                            (let [color (fill-color->background fill)]
                              (if (some? (:fill-color-gradient fill))
                                color
                                (str/format "linear-gradient(%s,%s)" color color))))
                          (:fills shape))
                     [(fill-color->background first-fill)])]
    (str/join ", " colors)))

(defn format-stroke [_ shape]
  (let [first-stroke (first (:strokes shape))
        width (:stroke-width first-stroke)
        style (let [style (:stroke-style first-stroke)]
                (when (keyword? style) (d/name style)))
        color {:color (:stroke-color first-stroke)
               :opacity (:stroke-opacity first-stroke)
               :gradient (:stroke-color-gradient first-stroke)}]
    (when-not (= :none (:stroke-style first-stroke))
      (str/format "%spx %s %s" width style (uc/color->background color)))))

(defn format-position [_ shape]
  (cond
    (cph/frame-shape? shape) "relative"
    (empty? (:flex-items shape)) "absolute"
    :else "static"))

(defn get-size
  [type values]
  (let [value (cond
                (number? values) values
                (string? values) values
                (type values) (type values)
                :else (type (:selrect values)))]

    (if (= :width type)
      (fmt/format-size :width value values)
      (fmt/format-size :heigth value values))))

(defn styles-data
  [shape]
  {:position    {:props [:type]
                 :to-prop {:type "position"}
                 :format {:type format-position}}
   :layout      {:props   (if (empty? (:flex-items shape))
                            [:width :height :x :y :radius :rx :r1]
                            [:width :height :radius :rx :r1])
                 :to-prop {:x "left"
                           :y "top"
                           :rotation "transform"
                           :rx "border-radius"
                           :r1 "border-radius"}
                 :format  {:rotation #(str/fmt "rotate(%sdeg)" %)
                           :r1       #(apply str/fmt "%spx, %spx, %spx, %spx" %)
                           :width    #(get-size :width %)
                           :height   #(get-size :height %)}
                 :multi   {:r1 [:r1 :r2 :r3 :r4]}}
   :fill        {:props [:fills]
                 :to-prop {:fills (if (> (count (:fills shape)) 1) "background-image" "background")}
                 :format {:fills format-fill-color}}
   :stroke      {:props [:strokes]
                 :to-prop {:strokes "border"}
                 :format {:strokes format-stroke}}
   :shadow      {:props [:shadow]
                 :to-prop {:shadow :box-shadow}
                 :format {:shadow #(str/join ", " (map shadow->css %1))}}
   :blur        {:props [:blur]
                 :to-prop {:blur "filter"}
                 :format {:blur #(str/fmt "blur(%spx)" (:value %))}}
   :layout-flex {:props   [:layout
                           :layout-flex-dir
                           :layout-align-items
                           :layout-justify-content
                           :layout-gap
                           :layout-padding
                           :layout-wrap-type]
                 :to-prop {:layout "display"
                           :layout-flex-dir "flex-direction"
                           :layout-align-items "align-items"
                           :layout-justify-content "justify-content"
                           :layout-wrap-type "flex-wrap"
                           :layout-gap "gap"
                           :layout-padding "padding"}
                 :format  {:layout d/name
                           :layout-flex-dir d/name
                           :layout-align-items d/name
                           :layout-justify-content d/name
                           :layout-wrap-type d/name
                           :layout-gap format-gap
                           :layout-padding fmt/format-padding}}})

(def style-text
  {:props   [:fills
             :font-family
             :font-style
             :font-size
             :font-weight
             :line-height
             :letter-spacing
             :text-decoration
             :text-transform]
   :to-prop {:fills "color"}
   :format  {:font-family #(dm/str "'" % "'")
             :font-style #(dm/str %)
             :font-size #(dm/str % "px")
             :font-weight #(dm/str %)
             :line-height #(dm/str %)
             :letter-spacing #(dm/str % "px")
             :text-decoration d/name
             :text-transform d/name
             :fills format-fill-color}})

(def layout-flex-item-params
  {:props   [:layout-item-margin
             :layout-item-max-h
             :layout-item-min-h
             :layout-item-max-w
             :layout-item-min-w
             :layout-item-align-self]
   :to-prop {:layout-item-margin "margin"
             :layout-item-max-h "max-height"
             :layout-item-min-h "min-height"
             :layout-item-max-w "max-width"
             :layout-item-min-w "min-width"
             :layout-item-align-self "align-self"}
   :format  {:layout-item-margin fmt/format-margin
             :layout-item-max-h #(dm/str % "px")
             :layout-item-min-h #(dm/str % "px")
             :layout-item-max-w #(dm/str % "px")
             :layout-item-min-w #(dm/str % "px")
             :layout-item-align-self d/name}})

(def layout-align-content
  {:props   [:layout-align-content]
   :to-prop {:layout-align-content "align-content"}
   :format  {:layout-align-content d/name}})

(defn get-specific-value
  [values prop]
  (let [result  (if (get values prop)
                  (get values prop)
                  (get (:selrect values) prop))
        result (if (= :width prop)
                 (get-size :width values)
                 result)
        result (if (= :height prop)
                 (get-size :height values)
                 result)]

    result))

(defn generate-css-props
  ([values properties]
   (generate-css-props values properties nil))

  ([values properties params]
   (let [{:keys [to-prop format tab-size multi]
          :or {to-prop {} tab-size 0 multi {}}} params

         ;; We allow the :format and :to-prop to be a map for different properties
         ;; or just a value for a single property. This code transform a single
         ;; property to a uniform one
         properties (if-not (coll? properties) [properties] properties)

         format (if (not (map? format))
                  (into {} (map #(vector % format) properties))
                  format)

         to-prop (if (not (map? to-prop))
                   (into {} (map #(vector % to-prop) properties))
                   to-prop)

         get-value (fn [prop]
                     (if-let [props (prop multi)]
                       (map #(get values %) props)
                       (get-specific-value values prop)))

         null? (fn [value]
                 (if (coll? value)
                   (every? #(or (nil? %) (= % 0)) value)
                   (or (nil? value) (= value 0))))

         default-format (fn [value] (dm/str (fmt/format-pixels value)))
         format-property (fn [prop]
                           (let [css-prop (or (prop to-prop) (d/name prop))
                                 format-fn (or (prop format) default-format)
                                 css-val (format-fn (get-value prop) values)]
                             (when css-val
                               (dm/str
                                (str/repeat " " tab-size)
                                (str/fmt "%s: %s;" css-prop css-val)))))]

     (->> properties
          (remove #(null? (get-value %)))
          (map format-property)
          (filter (comp not nil?))
          (str/join "\n")))))

(defn shape->properties [shape]
  (let [;; This property is added in an earlier step (code.cljs), 
        ;; it will come with a vector of flex-items if any.
        ;; If there are none it will continue as usual. 
        flex-items (:flex-items shape)
        props      (->> (styles-data shape) vals (mapcat :props))
        to-prop    (->> (styles-data shape) vals (map :to-prop) (reduce merge))
        format     (->> (styles-data shape) vals (map :format) (reduce merge))
        multi      (->> (styles-data shape) vals (map :multi) (reduce merge))
        props      (cond-> props
                     (seq flex-items) (concat (:props layout-flex-item-params))
                     (= :wrap (:layout-wrap-type shape)) (concat (:props layout-align-content)))
        to-prop    (cond-> to-prop
                     (seq flex-items) (merge (:to-prop layout-flex-item-params))
                     (= :wrap (:layout-wrap-type shape)) (merge (:to-prop layout-align-content)))
        format     (cond-> format
                     (seq flex-items) (merge (:format layout-flex-item-params))
                     (= :wrap (:layout-wrap-type shape)) (merge (:format layout-align-content)))]
    (generate-css-props shape props {:to-prop to-prop
                                     :format format
                                     :multi multi
                                     :tab-size 2})))

(defn search-text-attrs
  [node attrs]
  (->> (txt/node-seq node)
       (map #(select-keys % attrs))
       (reduce d/merge)))


;; TODO: used on inspect
(defn parse-style-text-blocks
  [node attrs]
  (letfn
   [(rec-style-text-map [acc node style]
      (let [node-style (merge style (select-keys node attrs))
            head (or (-> acc first) [{} ""])
            [head-style head-text] head

            new-acc
            (cond
              (:children node)
              (reduce #(rec-style-text-map %1 %2 node-style) acc (:children node))

              (not= head-style node-style)
              (cons [node-style (:text node "")] acc)

              :else
              (cons [node-style (dm/str head-text "" (:text node))] (rest acc)))

               ;; We add an end-of-line when finish a paragraph
            new-acc
            (if (= (:type node) "paragraph")
              (let [[hs ht] (first new-acc)]
                (cons [hs (dm/str ht "\n")] (rest new-acc)))
              new-acc)]
        new-acc))]

    (-> (rec-style-text-map [] node {})
        reverse)))

(defn text->properties [shape]
  (let [flex-items (:flex-items shape)
        text-shape-style (select-keys (styles-data shape) [:layout :shadow :blur])

        shape-props      (->> text-shape-style vals (mapcat :props))
        shape-to-prop    (->> text-shape-style vals (map :to-prop) (reduce merge))
        shape-format     (->> text-shape-style vals (map :format) (reduce merge))

        shape-props      (cond-> shape-props
                           (seq flex-items) (concat (:props layout-flex-item-params)))
        shape-to-prop    (cond-> shape-to-prop
                           (seq flex-items) (merge (:to-prop layout-flex-item-params)))
        shape-format     (cond-> shape-format
                           (seq flex-items) (merge (:format layout-flex-item-params)))

        text-values      (->> (search-text-attrs
                               (:content shape)
                               (conj (:props style-text) :fill-color-gradient :fill-opacity))
                              (d/merge txt/default-text-attrs))]
    (str/join
     "\n"
     [(generate-css-props shape
                          shape-props
                          {:to-prop shape-to-prop
                           :format shape-format
                           :tab-size 2})
      (generate-css-props text-values
                          (:props style-text)
                          {:to-prop (:to-prop style-text)
                           :format (:format style-text)
                           :tab-size 2})])))

(defn generate-css [shape]
  (let [name (:name shape)
        properties (if (= :text (:type shape))
                     (text->properties shape)
                     (shape->properties shape))
        selector (str/css-selector name)
        selector (if (str/starts-with? selector "-") (subs selector 1) selector)]
    (str/join "\n" [(str/fmt "/* %s */" name)
                    (str/fmt ".%s {" selector)
                    properties
                    "}"])))

(defn generate-style-code [type shapes]
  (let [generate-style-fn (case type
                            "css" generate-css)]
    (->> shapes
         (map generate-style-fn)
         (str/join "\n\n"))))
