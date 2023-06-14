;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen
  (:require
   ["react-dom/server" :as rds]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.text :as txt]
   [app.common.types.shape.layout :as ctl]
   [app.config :as cfg]
   [app.main.render :as render]
   [app.main.ui.formats :as fmt]
   [app.main.ui.shapes.text.html-text :as text]
   [app.util.color :as uc]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn shadow->css [shadow]
  (let [{:keys [style offset-x offset-y blur spread]} shadow
        css-color (uc/color->background (:color shadow))]
    (dm/str
     (if (= style :inner-shadow) "inset " "")
     (str/fmt "%spx %spx %spx %spx %s" offset-x offset-y blur spread css-color))))

(defn fill-color->background
  [fill]
  (cond
    (not= (:fill-opacity fill) 1)
    (uc/color->background {:color (:fill-color fill)
                           :opacity (:fill-opacity fill)
                           :gradient (:fill-color-gradient fill)})

    :else
    (str/upper (:fill-color fill))))

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

(defn format-stroke [shape]
  (let [first-stroke (first (:strokes shape))
        width (:stroke-width first-stroke)
        style (let [style (:stroke-style first-stroke)]
                (when (keyword? style) (d/name style)))
        color {:color (:stroke-color first-stroke)
               :opacity (:stroke-opacity first-stroke)
               :gradient (:stroke-color-gradient first-stroke)}]
    (when-not (= :none (:stroke-style first-stroke))
      (str/format "%spx %s %s" width style (uc/color->background color)))))

(defn format-position [objects]
  (fn [_ shape]
    (cond
      (and (ctl/any-layout-immediate-child? objects shape)
           (not (ctl/layout-absolute? shape))
           (or (cph/group-shape? shape)
               (cph/frame-shape? shape)))
      "relative"

      (and (ctl/any-layout-immediate-child? objects shape)
           (not (ctl/layout-absolute? shape)))
      nil

      :else
      "absolute")))

(defn mk-grid-coord
  [objects prop span-prop]

  (fn [_ shape]
    (when (ctl/grid-layout-immediate-child? objects shape)
      (let [parent (get objects (:parent-id shape))
            cell (ctl/get-cell-by-shape-id parent (:id shape))]
        (if (> (get cell span-prop) 1)
          (dm/str (get cell prop) " / " (+ (get cell prop) (get cell span-prop)))
          (get cell prop))))))

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

(defn make-format-absolute-pos
  [objects shape coord]
  (fn [value]
    (let [parent-id (dm/get-in objects [(:id shape) :parent-id])
          parent-value (dm/get-in objects [parent-id :selrect coord])]
      (when-not (or (cph/root-frame? shape)
                    (ctl/any-layout-immediate-child? objects shape)
                    (ctl/layout-absolute? shape))
        (fmt/format-pixels (- value parent-value))))))

(defn format-tracks
  [tracks]
  (str/join
   " "
   (->> tracks (map (fn [{:keys [type value]}]
                      (case type
                        :flex (dm/str (fmt/format-number value) "fr")
                        :percent (fmt/format-percent (/ value 100))
                        :auto "auto"
                        (fmt/format-pixels value)))))))

(defn styles-data
  [objects shape]
  {:position    {:props [:type]
                 :to-prop {:type "position"}
                 :format {:type (format-position objects)}}
   :layout      {:props   (if (or (empty? (:flex-items shape))
                                  (ctl/layout-absolute? shape))
                            [:x :y :width :height :radius :rx :r1]
                            [:width :height :radius :rx :r1])
                 :to-prop {:x "left"
                           :y "top"
                           :rotation "transform"
                           :rx "border-radius"
                           :r1 "border-radius"}
                 :format  {:rotation #(str/fmt "rotate(%sdeg)" %)
                           :r1       #(apply str/fmt "%spx %spx %spx %spx" %)
                           :width    #(get-size :width %)
                           :height   #(get-size :height %)
                           :x        (make-format-absolute-pos objects shape :x)
                           :y        (make-format-absolute-pos objects shape :y)}
                 :multi   {:r1 [:r1 :r2 :r3 :r4]}}
   :fill        {:props [:fills]
                 :to-prop {:fills (cond
                                    (or (cph/path-shape? shape)
                                        (cph/mask-shape? shape)
                                        (cph/bool-shape? shape)
                                        (cph/svg-raw-shape? shape)
                                        (some? (:svg-attrs shape)))
                                    nil

                                    (> (count (:fills shape)) 1)
                                    "background-image"

                                    (and (= (count (:fills shape)) 1)
                                         (some? (:fill-color-gradient (first (:fills shape)))))
                                    "background"

                                    :else
                                    "background-color")}
                 :format {:fills format-fill-color}}
   :stroke      {:props [:strokes]
                 :to-prop {:strokes "border"}
                 :format {:strokes (fn [_ shape]
                                     (when-not (or (cph/path-shape? shape)
                                                   (cph/mask-shape? shape)
                                                   (cph/bool-shape? shape)
                                                   (cph/svg-raw-shape? shape)
                                                   (some? (:svg-attrs shape)))
                                       (format-stroke shape)))}}
   :shadow      {:props [:shadow]
                 :to-prop {:shadow :box-shadow}
                 :format {:shadow #(str/join ", " (map shadow->css %1))}}
   :blur        {:props [:blur]
                 :to-prop {:blur "filter"}
                 :format {:blur #(str/fmt "blur(%spx)" (:value %))}}

   :layout-flex {:props   [:layout
                           :layout-flex-dir
                           :layout-align-items
                           :layout-justify-items
                           :layout-align-content
                           :layout-justify-content
                           :layout-gap
                           :layout-padding
                           :layout-wrap-type]
                 :gen-props [:flex-shrink]
                 :to-prop {:layout "display"
                           :layout-flex-dir "flex-direction"
                           :layout-align-items "align-items"
                           :layout-align-content "align-content"
                           :layout-justify-items "justify-items"
                           :layout-justify-content "justify-content"
                           :layout-wrap-type "flex-wrap"
                           :layout-gap "gap"
                           :layout-padding "padding"}
                 :format  {:layout d/name
                           :layout-flex-dir d/name
                           :layout-align-items d/name
                           :layout-align-content d/name
                           :layout-justify-items d/name
                           :layout-justify-content d/name
                           :layout-wrap-type d/name
                           :layout-gap fmt/format-gap
                           :layout-padding fmt/format-padding
                           :flex-shrink (fn [_ shape] (when (ctl/flex-layout-immediate-child? objects shape) 0))}}

   :layout-grid {:props [:layout-grid-rows
                         :layout-grid-columns]
                 :gen-props [:grid-column
                             :grid-row]
                 :to-prop {:layout-grid-rows "grid-template-rows"
                           :layout-grid-columns "grid-template-columns"}
                 :format {:layout-grid-rows format-tracks
                          :layout-grid-columns format-tracks
                          :grid-column (mk-grid-coord objects :column :column-span)
                          :grid-row (mk-grid-coord objects :row :row-span)}}})

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
   (generate-css-props values properties [] nil))

  ([values properties gen-properties]
   (generate-css-props values properties gen-properties nil))

  ([values properties gen-properties params]
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
                     (if-let [props (get multi prop)]
                       (map #(get values %) props)
                       (get-specific-value values prop)))

         null? (fn [value]
                 (if (coll? value)
                   (every? #(or (nil? %) (= % 0)) value)
                   (or (nil? value) (= value 0))))

         default-format (fn [value] (dm/str (fmt/format-pixels value)))

         format-property
         (fn [prop]
           (let [css-prop (or (get to-prop prop) (d/name prop))
                 format-fn (or (get format prop) default-format)
                 css-val (format-fn (get-value prop) values)]
             (when (and css-val (not= css-val ""))
               (dm/str
                (str/repeat " " tab-size)
                (dm/fmt "%: %;" css-prop css-val)))))]

     (->> (concat
           (->> properties
                (remove #(null? (get-value %))))
           gen-properties)
          (keep format-property)
          (str/join "\n")))))

(defn shape->properties [objects shape]
  (let [;; This property is added in an earlier step (code.cljs),
        ;; it will come with a vector of flex-items if any.
        ;; If there are none it will continue as usual.
        flex-items (:flex-items shape)
        props      (->> (styles-data objects shape) vals (mapcat :props))
        to-prop    (->> (styles-data objects shape) vals (map :to-prop) (reduce merge))
        format     (->> (styles-data objects shape) vals (map :format) (reduce merge))
        multi      (->> (styles-data objects shape) vals (map :multi) (reduce merge))
        gen-props  (->> (styles-data objects shape) vals (mapcat :gen-props))

        props      (cond-> props
                     (seq flex-items) (concat (:props layout-flex-item-params))
                     (= :wrap (:layout-wrap-type shape)) (concat (:props layout-align-content)))
        to-prop    (cond-> to-prop
                     (seq flex-items) (merge (:to-prop layout-flex-item-params))
                     (= :wrap (:layout-wrap-type shape)) (merge (:to-prop layout-align-content)))
        format     (cond-> format
                     (seq flex-items) (merge (:format layout-flex-item-params))
                     (= :wrap (:layout-wrap-type shape)) (merge (:format layout-align-content)))]
    (generate-css-props
     shape
     props
     gen-props
     {:to-prop to-prop
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

(defn text->properties [objects shape]
  (let [flex-items (:flex-items shape)
        text-shape-style (d/without-keys (styles-data objects shape) [:fill :stroke])

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

(defn selector-name [shape]
  (let [name (-> (:name shape)
                 (subs 0 (min 10 (count (:name shape)))))
        ;; selectors cannot start with numbers
        name (if (re-matches #"^\d.*" name) (dm/str "c-" name) name)
        id (-> (dm/str (:id shape))
               #_(subs 24 36))
        selector (str/css-selector (dm/str name " " id))
        selector (if (str/starts-with? selector "-") (subs selector 1) selector)]
    selector))

(defn generate-css [objects shape]
  (let [name (:name shape)
        properties (shape->properties objects shape)
        selector (selector-name shape)]
    (str/join "\n" [(str/fmt "/* %s */" name)
                    (str/fmt ".%s {" selector)
                    properties
                    "}"])))

(defn generate-svg
  [objects shape-id]
  (let [shape (get objects shape-id)]
    (rds/renderToStaticMarkup
     (mf/element
      render/object-svg
      #js {:objects objects
           :object-id (-> shape :id)}))))

(defn generate-html
  ([objects shape-id]
   (generate-html objects shape-id 0))

  ([objects shape-id level]
   (let [shape (get objects shape-id)
         indent (str/repeat "  " level)
         maybe-reverse (if (ctl/any-layout? shape) reverse identity)]
     (cond
       (cph/text-shape? shape)
       (let [text-shape-html (rds/renderToStaticMarkup (mf/element text/text-shape #js {:shape shape :code? true}))]
         (dm/fmt "%<div class=\"%\">\n%\n%</div>"
                 indent
                 (selector-name shape)
                 text-shape-html
                 indent))

       (cph/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape))
             image-url (cfg/resolve-file-media data)]
         (dm/fmt "%<img src=\"%\" class=\"%\">\n%</img>"
                 indent
                 image-url
                 (selector-name shape)
                 indent))

       (or (cph/path-shape? shape)
           (cph/mask-shape? shape)
           (cph/bool-shape? shape)
           (cph/svg-raw-shape? shape)
           (some? (:svg-attrs shape)))
       (let [svg-markup (rds/renderToStaticMarkup (mf/element render/object-svg #js {:objects objects :object-id (:id shape) :render-embed? false}))]
         (dm/fmt "%<div class=\"%\">\n%\n%</div>"
                 indent
                 (selector-name shape)
                 svg-markup
                 indent))

       (empty? (:shapes shape))
       (dm/fmt "%<div class=\"%\">\n%</div>"
               indent
               (selector-name shape)
               indent)

       :else
       (dm/fmt "%<div class=\"%\">\n%\n%</div>"
               indent
               (selector-name shape)
               (->> (:shapes shape)
                    (maybe-reverse)
                    (map #(generate-html objects % (inc level)))
                    (str/join "\n"))
               indent)))))

(defn generate-markup-code [objects type shapes]
  (let [generate-markup-fn (case type
                             "html" generate-html
                             "svg" generate-svg)]
    (->> shapes
         (map #(generate-markup-fn objects % 0))
         (str/join "\n"))))

(defn generate-style-code [objects type shapes]
  (let [generate-style-fn (case type
                            "css" generate-css)]
    (dm/str
     "html, body {
  background-color: #E8E9EA;
  height: 100%;
  margin: 0;
  padding: 0;
  width: 100%;
}

body {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 2rem;
}

svg {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
}

* {
  box-sizing: border-box;
}
\n"
     (->> shapes
          (map (partial generate-style-fn objects))
          (str/join "\n\n")))))
