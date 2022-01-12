;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.code-gen
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.text :as txt]
   [app.util.color :as uc]
   [cuerdas.core :as str]))

(defn shadow->css [shadow]
  (let [{:keys [style offset-x offset-y blur spread]} shadow
        css-color (uc/color->background (:color shadow))]
    (str
     (if (= style :inner-shadow) "inset " "")
     (str/fmt "%spx %spx %spx %spx %s" offset-x offset-y blur spread css-color))))


(defn format-fill-color [_ shape]
  (let [color {:color (:fill-color shape)
               :opacity (:fill-opacity shape)
               :gradient (:fill-color-gradient shape)}]
    (uc/color->background color)))

(defn format-stroke [_ shape]
  (let [width (:stroke-width shape)
        style (let [style (:stroke-style shape)]
                (when (keyword? style) (name style)))
        color {:color (:stroke-color shape)
               :opacity (:stroke-opacity shape)
               :gradient (:stroke-color-gradient shape)}]
    (when-not (= :none (:stroke-style shape))
      (str/format "%spx %s %s" width style (uc/color->background color)))))

(def styles-data
  {:layout {:props   [:width :height :x :y :radius :rx :r1]
            :to-prop {:x "left"
                      :y "top"
                      :rotation "transform"
                      :rx "border-radius"
                      :r1 "border-radius"}
            :format  {:rotation #(str/fmt "rotate(%sdeg)" %)
                      :r1 #(apply str/fmt "%spx, %spx, %spx, %spx" %)}
            :multi   {:r1 [:r1 :r2 :r3 :r4]}}
   :fill   {:props [:fill-color :fill-color-gradient]
            :to-prop {:fill-color "background" :fill-color-gradient "background"}
            :format {:fill-color format-fill-color :fill-color-gradient format-fill-color}}
   :stroke {:props [:stroke-style]
            :to-prop {:stroke-style "border"}
            :format {:stroke-style format-stroke}}
   :shadow {:props [:shadow]
            :to-prop {:shadow :box-shadow}
            :format {:shadow #(str/join ", " (map shadow->css %1))}}
   :blur {:props [:blur]
          :to-prop {:blur "filter"}
          :format {:blur #(str/fmt "blur(%spx)" (:value %))}}})

(def style-text
  {:props   [:fill-color
             :font-family
             :font-style
             :font-size
             :line-height
             :letter-spacing
             :text-decoration
             :text-transform]
   :to-prop {:fill-color "color"}
   :format  {:font-family #(str "'" % "'")
             :font-style #(str "'" % "'")
             :font-size #(str % "px")
             :line-height #(str % "px")
             :letter-spacing #(str % "px")
             :text-decoration name
             :text-transform name
             :fill-color format-fill-color}})

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
                       (get values prop)))

         null? (fn [value]
                 (if (coll? value)
                   (every? #(or (nil? %) (= % 0)) value)
                   (or (nil? value) (= value 0))))

         default-format (fn [value] (str (mth/precision value 2) "px"))
         format-property (fn [prop]
                           (let [css-prop (or (prop to-prop) (name prop))
                                 format-fn (or (prop format) default-format)
                                 css-val (format-fn (get-value prop) values)]
                             (when css-val
                               (str
                                (str/repeat " " tab-size)
                                (str/fmt "%s: %s;" css-prop css-val)))))]

     (->> properties
          (remove #(null? (get-value %)))
          (map format-property)
          (filter (comp not nil?))
          (str/join "\n")))))

(defn shape->properties [shape]
  (let [props   (->> styles-data vals (mapcat :props))
        to-prop (->> styles-data vals (map :to-prop) (reduce merge))
        format  (->> styles-data vals (map :format) (reduce merge))
        multi   (->> styles-data vals (map :multi) (reduce merge))]
    (generate-css-props shape props {:to-prop to-prop
                                     :format format
                                     :multi multi
                                     :tab-size 2})))

(defn search-text-attrs
  [node attrs]
  (->> (txt/node-seq node)
       (map #(select-keys % attrs))
       (reduce d/merge)))


;; TODO: used on handoff
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
                 (cons [node-style (str head-text "" (:text node))] (rest acc)))

               ;; We add an end-of-line when finish a paragraph
               new-acc
               (if (= (:type node) "paragraph")
                 (let [[hs ht] (first new-acc)]
                   (cons [hs (str ht "\n")] (rest new-acc)))
                 new-acc)]
           new-acc))]

    (-> (rec-style-text-map [] node {})
        reverse)))

(defn text->properties [shape]
  (let [text-shape-style (select-keys styles-data [:layout :shadow :blur])

        shape-props      (->> text-shape-style vals (mapcat :props))
        shape-to-prop    (->> text-shape-style vals (map :to-prop) (reduce merge))
        shape-format     (->> text-shape-style vals (map :format) (reduce merge))

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
                           :tab-size 2})]))

  )

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
