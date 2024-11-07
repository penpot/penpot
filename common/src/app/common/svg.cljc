;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.svg
  (:require
   #?(:clj  [clojure.xml :as xml]
      :cljs [tubax.core :as tubax])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str])
  #?(:clj
     (:import
      clojure.lang.XMLHandler
      java.io.InputStream
      javax.xml.XMLConstants
      javax.xml.parsers.SAXParserFactory
      org.apache.commons.io.IOUtils)))


;; Regex for XML ids per Spec
;; https://www.w3.org/TR/2008/REC-xml-20081126/#sec-common-syn
(def xml-id-regex #"#([:A-Z_a-z\xC0-\xD6\xD8-\xF6\xF8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD\u10000-\uEFFFF][\.\-\:0-9\xB7A-Z_a-z\xC0-\xD6\xD8-\xF6\xF8-\u02FF\u0300-\u036F\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u203F-\u2040\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD\u10000-\uEFFFF]*)")

(def matrices-regex #"(matrix|translate|scale|rotate|skewX|skewY)\(([^\)]*)\)")
(def number-regex #"[+-]?\d*(\.\d+)?([eE][+-]?\d+)?")

(def tags-to-remove #{:linearGradient :radialGradient :metadata :mask :clipPath :filter :title})

(defn- camelize
  [s]
  (when (string? s)
    (let [vendor? (str/starts-with? s "-")
          result  #?(:cljs (js* "~{}.replace(\":\", \"-\").replace(/-./g, x=>x[1].toUpperCase())", s)
                     :clj  (str/camel s))]
      (if ^boolean vendor?
        (str/capital result)
        result))))

;; https://www.w3.org/TR/SVG11/eltindex.html
(def svg-tags
  #{:a
    :altGlyph
    :altGlyphDef
    :altGlyphItem
    :animate
    :animateColor
    :animateMotion
    :animateTransform
    :circle
    :clipPath
    :color-profile
    :cursor
    :defs
    :desc
    :ellipse
    :feBlend
    :feColorMatrix
    :feComponentTransfer
    :feComposite
    :feConvolveMatrix
    :feDiffuseLighting
    :feDisplacementMap
    :feDistantLight
    :feFlood
    :feFuncA
    :feFuncB
    :feFuncG
    :feFuncR
    :feGaussianBlur
    :feImage
    :feMerge
    :feMergeNode
    :feMorphology
    :feOffset
    :fePointLight
    :feSpecularLighting
    :feSpotLight
    :feTile
    :feTurbulence
    :filter
    :font
    :font-face
    :font-face-format
    :font-face-name
    :font-face-src
    :font-face-uri
    :foreignObject
    :g
    :glyph
    :glyphRef
    :hkern
    :image
    :line
    :linearGradient
    :marker
    :mask
    :metadata
    :missing-glyph
    :mpath
    :path
    :pattern
    :polygon
    :polyline
    :radialGradient
    :rect
    :set
    :stop
    :style
    :svg
    :switch
    :symbol
    :text
    :textPath
    :title
    :tref
    :tspan
    :use
    :view
    :vkern})

;; https://www.w3.org/TR/SVG11/attindex.html
(def svg-attrs
  #{:accent-height
    :accumulate
    :additive
    :alphabetic
    :amplitude
    :arabic-form
    :ascent
    :attributeName
    :attributeType
    :azimuth
    :baseFrequency
    :baseProfile
    :bbox
    :begin
    :bias
    :by
    :calcMode
    :cap-height
    :class
    :clipPathUnits
    :contentScriptType
    :contentStyleType
    :cx
    :cy
    :d
    :descent
    :diffuseConstant
    :divisor
    :dur
    :dx
    :dy
    :edgeMode
    :elevation
    :end
    :exponent
    :externalResourcesRequired
    :fill
    :filterRes
    :filterUnits
    :font-family
    :font-size
    :font-stretch
    :font-style
    :font-variant
    :font-weight
    :format
    :from
    :fx
    :fy
    :g1
    :g2
    :glyph-name
    :glyphRef
    :gradientTransform
    :gradientUnits
    :hanging
    :height
    :horiz-adv-x
    :horiz-origin-x
    :horiz-origin-y
    :id
    :ideographic
    :in
    :in2
    :intercept
    :k
    :k1
    :k2
    :k3
    :k4
    :kernelMatrix
    :kernelUnitLength
    :keyPoints
    :keySplines
    :keyTimes
    :lang
    :lengthAdjust
    :limitingConeAngle
    :local
    :markerHeight
    :markerUnits
    :markerWidth
    :maskContentUnits
    :maskUnits
    :mathematical
    :max
    :media
    :method
    :min
    :mode
    :name
    :numOctaves
    :offset
    :operator
    :order
    :orient
    :orientation
    :origin
    :overline-position
    :overline-thickness
    :panose-1
    :path
    :pathLength
    :patternContentUnits
    :patternTransform
    :patternUnits
    :points
    :pointsAtX
    :pointsAtY
    :pointsAtZ
    :preserveAlpha
    :preserveAspectRatio
    :primitiveUnits
    :r
    :radius
    :refX
    :refY
    :rendering-intent
    :repeatCount
    :repeatDur
    :requiredExtensions
    :requiredFeatures
    :restart
    :result
    :rotate
    :rx
    :ry
    :scale
    :seed
    :slope
    :spacing
    :specularConstant
    :specularExponent
    :spreadMethod
    :startOffset
    :stdDeviation
    :stemh
    :stemv
    :stitchTiles
    :strikethrough-position
    :strikethrough-thickness
    :string
    :style
    :surfaceScale
    :systemLanguage
    :tableValues
    :target
    :targetX
    :targetY
    :textLength
    :title
    :to
    :transform
    :type
    :u1
    :u2
    :underline-position
    :underline-thickness
    :unicode
    :unicode-range
    :units-per-em
    :v-alphabetic
    :v-hanging
    :v-ideographic
    :v-mathematical
    :values
    :version
    :vert-adv-y
    :vert-origin-x
    :vert-origin-y
    :viewBox
    :viewTarget
    :width
    :widths
    :x
    :x-height
    :x1
    :x2
    :xChannelSelector
    :xmlns:xlink
    :xlink:actuate
    :xlink:arcrole
    :xlink:href
    :xlink:role
    :xlink:show
    :xlink:title
    :xlink:type
    :xml:base
    :xml:lang
    :xml:space
    :y
    :y1
    :y2
    :yChannelSelector
    :z
    :zoomAndPan})

(def svg-presentation-attrs
  "A set of presentation SVG attributes as per SVG spec."
  #{:alignment-baseline
    :baseline-shift
    :clip-path
    :clip-rule
    :clip
    :color-interpolation-filters
    :color-interpolation
    :color-profile
    :color-rendering
    :color
    :cursor
    :direction
    :display
    :dominant-baseline
    :enable-background
    :fill-opacity
    :fill-rule
    :fill
    :filter
    :flood-color
    :flood-opacity
    :font-family
    :font-size-adjust
    :font-size
    :font-stretch
    :font-style
    :font-variant
    :font-weight
    :glyph-orientation-horizontal
    :glyph-orientation-vertical
    :image-rendering
    :kerning
    :letter-spacing
    :lighting-color
    :marker-end
    :marker-mid
    :marker-start
    :mask
    :opacity
    :overflow
    :pointer-events
    :shape-rendering
    :stop-color
    :stop-opacity
    :stroke-dasharray
    :stroke-dashoffset
    :stroke-linecap
    :stroke-linejoin
    :stroke-miterlimit
    :stroke-opacity
    :stroke-width
    :stroke
    :text-anchor
    :text-decoration
    :text-rendering
    :unicode-bidi
    :visibility
    :word-spacing
    :writing-mode
    :mask-type})

(def inheritable-props
  #{:style
    :clip-rule
    :color
    :color-interpolation
    :color-interpolation-filters
    :color-profile
    :color-rendering
    :cursor
    :direction
    :dominant-baseline
    :fill
    :fill-opacity
    :fill-rule
    :font
    :font-family
    :font-size
    :font-size-adjust
    :font-stretch
    :font-style
    :font-variant
    :font-weight
    :glyph-orientation-horizontal
    :glyph-orientation-vertical
    :image-rendering
    :letter-spacing
    :marker
    :marker-end
    :marker-mid
    :marker-start
    :paint-order
    :pointer-events
    :shape-rendering
    :stroke
    :stroke-dasharray
    :stroke-dashoffset
    :stroke-linecap
    :stroke-linejoin
    :stroke-miterlimit
    :stroke-opacity
    :stroke-width
    :text-anchor
    :text-rendering
    :transform
    :visibility
    :word-spacing
    :writing-mode})

(def gradient-tags
  #{:linearGradient
    :radialGradient})

(def filter-tags
  #{:filter
    :feBlend
    :feColorMatrix
    :feComponentTransfer
    :feComposite
    :feConvolveMatrix
    :feDiffuseLighting
    :feDisplacementMap
    :feFlood
    :feGaussianBlur
    :feImage
    :feMerge
    :feMorphology
    :feOffset
    :feSpecularLighting
    :feTile
    :feTurbulence})

(def parent-tags
  #{:g
    :svg
    :text
    :tspan})

;; By spec: https://www.w3.org/TR/SVG11/single-page.html#struct-GElement
(def svg-group-safe-tags
  #{:animate
    :animateColor
    :animateMotion
    :animateTransform
    :set
    :desc
    :metadata
    :title
    :circle
    :ellipse
    :line
    :path
    :polygon
    :polyline
    :rect
    :defs
    :g
    :svg
    :symbol
    :use
    :linearGradient
    :radialGradient
    :a
    :altGlyphDef
    :clipPath
    :color-profile
    :cursor
    :filter
    :font
    :font-face
    :foreignObject
    :image
    :marker
    :mask
    :pattern
    :style
    :switch
    :text
    :view})

(defn prop-key
  "Convert an attr key to a react compatible prop key. Returns nil if key is empty or invalid"
  [k]
  (let [kn (cond
             (string? k)  k
             (keyword? k) (name k))]
    (case kn
      ("" nil) nil
      "class"  :className
      "for"    :htmlFor
      (let [kn1 (subs kn 0 1)]
        (if (= kn1 (str/upper kn1))
          (-> kn camelize str/capital keyword)
          (-> kn camelize keyword))))))

(def svg-props
  "A set of all attrs (including the presentation) converted to
  camelCase for make it React compatible."
  (let [xf (map prop-key)]
    (-> #{}
        (into xf svg-attrs)
        (into xf svg-presentation-attrs))))

;; Defaults for some tags per spec https://www.w3.org/TR/SVG11/single-page.html
;; they are basically the defaults that can be percents and we need to replace because
;; otherwise won't work as expected in the workspace
(def svg-tag-defaults
  (let [filter-default {:units :filterUnits
                        :default "objectBoundingBox"
                        "objectBoundingBox" {}
                        "userSpaceOnUse" {:x "-10%" :y "-10%" :width "120%" :height "120%"}}
        filter-values (->> filter-tags
                           (reduce #(merge %1 (hash-map %2 filter-default)) {}))]

    (merge {:linearGradient {:units :gradientUnits
                             :default "objectBoundingBox"
                             "objectBoundingBox" {}
                             "userSpaceOnUse"    {:x1 "0%" :y1 "0%" :x2 "100%" :y2 "0%"}}
            :radialGradient {:units :gradientUnits
                             :default "objectBoundingBox"
                             "objectBoundingBox" {}
                             "userSpaceOnUse"    {:cx "50%" :cy "50%" :r "50%"}}
            :mask {:units :maskUnits
                   :default "userSpaceOnUse"
                   "objectBoundingBox" {}
                   "userSpaceOnUse"    {:x "-10%" :y "-10%" :width "120%" :height "120%"}}}
           filter-values)))

(defn extract-ids [val]
  (when (some? val)
    (->> (re-seq xml-id-regex val)
         (mapv second))))

(defn fix-dot-number
  "Fixes decimal numbers starting in dot but without leading 0"
  [num-str]
  (cond
    (str/starts-with? num-str ".")
    (dm/str "0" num-str)

    (str/starts-with? num-str "-.")
    (dm/str "-0" (subs num-str 1))

    :else
    num-str))

(defn parse-style
  [style]
  (reduce (fn [res item]
            (let [[k v] (-> (str/trim item) (str/split ":" 2))
                  k     (keyword k)]
              (if (contains? res k)
                res
                (assoc res (keyword k) v))))
          {}
          (str/split style ";")))

;; FIXME: rename to `format-style` or directly use parse-style on code...
(defn format-styles
  "Transform string based styles found on attrs map to key-value map."
  [attrs]
  (if (contains? attrs :style)
    (update attrs :style
            (fn [style]
              (if (string? style)
                (parse-style style)
                style)))
    attrs))

(defn attrs->props
  "Transforms and cleans svg attributes to react compatible props"
  ([attrs]
   (attrs->props attrs true))

  ([attrs whitelist?]
   (reduce-kv (fn [res k v]
                (let [k (prop-key k)]
                  (cond
                    (nil? k)
                    res

                    (nil? v)
                    res

                    (= k :style)
                    (let [v (if (string? v) (parse-style v) v)
                          v (not-empty (attrs->props v false))]
                      (if v
                        (assoc res k v)
                        res))

                    :else
                    (if (or (not whitelist?) (contains? svg-props k))
                      (let [v (if (string? v) (str/trim v) v)]
                        (assoc res k v))
                      res))))
              {}
              attrs)))

(defn update-attr-ids
  "Replaces the ids inside a property"
  [attrs replace-fn]
  (letfn [(update-ids [key val]
            (cond
              (map? val)
              (d/mapm update-ids val)

              (= key :id)
              (replace-fn val)

              :else
              (let [replace-id
                    (fn [result it]
                      (let [to-replace (replace-fn it)]
                        (str/replace result (str "#" it) (str "#" to-replace))))]
                (reduce replace-id val (extract-ids val)))))]

    (d/mapm update-ids attrs)))

(defn replace-attrs-ids
  "Replaces the ids inside a property"
  [attrs ids-mapping]
  (if (empty? ids-mapping)
    attrs
    (update-attr-ids attrs (fn [id] (get ids-mapping id id)))))

(defn generate-id-mapping
  [content]
  (letfn [(visit-node [result node]
            (let [element-id (dm/get-in node [:attrs :id])
                  result     (if (some? element-id)
                               (assoc result element-id (dm/str (uuid/next)))
                               result)]
              (reduce visit-node result (:content node))))]
    (visit-node {} content)))

(defn extract-defs
  [{:keys [attrs] :as node}]
  (if-not (map? node)
    [{} node]

    (let [remove-node? (fn [{:keys [tag]}] (and (some? tag)
                                                (or (contains? tags-to-remove tag)
                                                    (not (contains? svg-tags tag)))))
          rec-result (->> (:content node) (map extract-defs))
          node (assoc node :content (->> rec-result (map second) (filterv (comp not remove-node?))))

          current-node-defs (if (contains? attrs :id)
                              (hash-map (:id attrs) node)
                              (hash-map))

          node-defs (->> rec-result (map first) (reduce merge current-node-defs))]

      [node-defs node])))

(defn find-attr-references
  [attrs]
  (->> attrs
       (mapcat (fn [[_ attr-value]]
                 (if (string? attr-value)
                   (extract-ids attr-value)
                   (find-attr-references attr-value))))))

(defn find-node-references
  [node]
  (let [current (->> (find-attr-references (:attrs node)) (into #{}))
        children (->> (:content node) (map find-node-references) (flatten) (into #{}))]
    (vec (into current children))))

(defn find-def-references
  [defs references]
  (loop [result (into #{} references)
         checked? #{}
         to-check (first references)
         pending (rest references)]

    (cond
      (nil? to-check)
      result

      (checked? to-check)
      (recur result
             checked?
             (first pending)
             (rest pending))

      :else
      (let [node (get defs to-check)
            new-refs (find-node-references node)
            pending (concat pending new-refs)]
        (recur (into result new-refs)
               (conj checked? to-check)
               (first pending)
               (rest pending))))))

(defn svg-transform-matrix
  [shape]
  (if (:svg-viewbox shape)
    (let [{svg-x :x
           svg-y :y
           svg-width :width
           svg-height :height} (:svg-viewbox shape)
          {:keys [x y width height]} (:selrect shape)

          scale-x (/ width svg-width)
          scale-y (/ height svg-height)]

      (gmt/multiply
       (gmt/matrix)

       ;; Paths doesn't have transform so we have to transform its gradients
       (if (or (= :path (:type shape))
               (= :group (:type shape)))
         (gsh/transform-matrix shape)
         (gmt/matrix))

       (gmt/translate-matrix (gpt/point (- x (* scale-x svg-x)) (- y (* scale-y svg-y))))
       (gmt/scale-matrix (gpt/point scale-x scale-y))))

    ;; :else
    (gmt/matrix)))

;; Parse transform attributes to native matrix format so we can transform paths instead of
;; relying in SVG transformation. This is necessary to import SVG's and not to break path tooling
;;
;; Transforms spec:
;; https://www.w3.org/TR/SVG11/single-page.html#coords-TransformAttribute

(defn- format-translate-params
  [params]
  (assert (or (= (count params) 1) (= (count params) 2)))
  (if (= (count params) 1)
    [(gpt/point (nth params 0) 0)]
    [(gpt/point (nth params 0) (nth params 1))]))

(defn- format-scale-params
  [params]
  (assert (or (= (count params) 1) (= (count params) 2)))
  (if (= (count params) 1)
    [(gpt/point (nth params 0))]
    [(gpt/point (nth params 0) (nth params 1))]))

(defn- format-rotate-params
  [params]
  (assert (or (= (count params) 1) (= (count params) 3)) (str "??" (count params)))
  (if (= (count params) 1)
    [(nth params 0) (gpt/point 0 0)]
    [(nth params 0) (gpt/point (nth params 1) (nth params 2))]))

(defn- format-skew-x-params
  [params]
  (assert (= (count params) 1))
  [(nth params 0) 0])

(defn- format-skew-y-params
  [params]
  (assert (= (count params) 1))
  [0 (nth params 0)])

(defn- to-matrix
  [type params]
  (case type
    "matrix"    (apply gmt/matrix params)
    "translate" (apply gmt/translate-matrix (format-translate-params params))
    "scale"     (apply gmt/scale-matrix (format-scale-params params))
    "rotate"    (apply gmt/rotate-matrix (format-rotate-params params))
    "skewX"     (apply gmt/skew-matrix (format-skew-x-params params))
    "skewY"     (apply gmt/skew-matrix (format-skew-y-params params))))

(def ^:private
  xf-parse-numbers
  (comp
   (map first)
   (keep not-empty)
   (map d/parse-double)))

(defn parse-numbers
  [data]
  (->> (re-seq number-regex data)
       (into [] xf-parse-numbers)))

(defn parse-transform
  [transform]
  (if (string? transform)
    (->> (re-seq matrices-regex transform)
         (map (fn [[_ type params]]
                (let [params (parse-numbers params)]
                  (to-matrix type params))))
         (reduce gmt/multiply (gmt/matrix)))

    (gmt/matrix)))

(defn format-move [[x y]] (str "M" x " " y))
(defn format-line [[x y]] (str "L" x " " y))

(defn points->path
  [points-str]
  (let [points (->> points-str
                    (re-seq number-regex)
                    (filter (comp not empty? first))
                    (mapv (comp d/parse-double first))
                    (partition 2))

        head (first points)
        other (rest points)]

    (str (format-move head)
         (->> other (map format-line) (str/join " ")))))

(defn polyline->path [{:keys [attrs] :as node}]
  (let [tag :path
        attrs (-> attrs
                  (dissoc :points)
                  (assoc :d (points->path (:points attrs))))]

    (assoc node :attrs attrs :tag tag)))

(defn polygon->path [{:keys [attrs] :as node}]
  (let [tag :path
        attrs (-> attrs
                  (dissoc :points)
                  (assoc :d (str (points->path (:points attrs)) "Z")))]
    (assoc node :attrs attrs :tag tag)))

(defn line->path [{:keys [attrs] :as node}]
  (let [tag :path
        {:keys [x1 y1 x2 y2]} attrs
        x1 (or x1 0)
        y1 (or y1 0)
        x2 (or x2 0)
        y2 (or y2 0)
        attrs (-> attrs
                  (dissoc :x1 :x2 :y1 :y2)
                  (assoc :d (str "M" x1 "," y1 " L" x2 "," y2)))]

    (assoc node :attrs attrs :tag tag)))

(defn add-transform [attrs transform]
  (letfn [(append-transform [old-transform]
            (if (or (nil? old-transform) (empty? old-transform))
              transform
              (str transform " " old-transform)))]

    (cond-> attrs
      transform
      (update :transform append-transform))))

(defn inherit-attributes
  [group-attrs {:keys [attrs] :as node}]
  (if (map? node)
    (let [attrs             (-> (format-styles attrs)
                                (add-transform (:transform group-attrs)))
          group-attrs       (format-styles group-attrs)

          ;; Don't inherit a property that is already in the style attribute
          inherit-style     (-> (:style group-attrs) (d/without-keys (keys attrs)))
          inheritable-props (->> inheritable-props (remove #(contains? (:styles attrs) %)))
          group-attrs       (-> group-attrs (assoc :style inherit-style))

          attrs             (-> (select-keys group-attrs inheritable-props)
                                (d/deep-merge attrs)
                                (d/without-nils))]
      (assoc node :attrs attrs))
    node))

(defn map-nodes [mapfn node]
  (let [update-content
        (fn [content]
          (cond->> content
            (vector? content)
            (mapv (partial map-nodes mapfn))))]

    (cond-> node
      (map? node)
      (-> (mapfn)
          (d/update-when :content update-content)))))

(defn reduce-nodes [redfn value node]
  (let [reduce-content
        (fn [value content]
          (loop [current (first content)
                 content (rest content)
                 value value]
            (if (nil? current)
              value
              (recur (first content)
                     (rest content)
                     (reduce-nodes redfn value current)))))]

    (if (map? node)
      (-> (redfn value node)
          (reduce-content (:content node)))
      value)))

(defn fix-default-values
  "Gives values to some SVG elements which defaults won't work when
  imported into the platform"
  [svg-data]
  (let [add-defaults
        (fn [{:keys [tag attrs] :as node}]
          (let [prop (get-in svg-tag-defaults [tag :units])
                default-units (get-in svg-tag-defaults [tag :default])
                units (get attrs prop default-units)
                tag-default (get-in svg-tag-defaults [tag units])]
            (d/update-when node :attrs #(merge tag-default %))))

        fix-node-defaults
        (fn [node]
          (cond-> node
            (contains? svg-tag-defaults (:tag node))
            (add-defaults)))]

    (->> svg-data (map-nodes fix-node-defaults))))

(defn calculate-ratio
  ;;  sqrt((actual-width)**2 + (actual-height)**2)/sqrt(2).
  [width height]
  (/ (mth/hypot width height)
     (mth/sqrt 2)))

(defn fix-percents
  "Changes percents to a value according to the size of the svg imported"
  [svg-data]
  ;; https://www.w3.org/TR/SVG11/single-page.html#coords-Units
  (let [viewbox {:x (:offset-x svg-data)
                 :y (:offset-y svg-data)
                 :width (:width svg-data)
                 :height (:height svg-data)
                 :ratio (calculate-ratio (:width svg-data) (:height svg-data))}]
    (letfn [(fix-length [prop-length val]
              (* (get viewbox prop-length) (/ val 100.)))

            (fix-coord [prop-coord prop-length val]
              (+ (get viewbox prop-coord)
                 (fix-length prop-length val)))

            (fix-percent-attr-viewbox [attr-key attr-val]
              (let [is-percent? (str/ends-with? attr-val "%")
                    is-x? #{:x :x1 :x2 :cx}
                    is-y? #{:y :y1 :y2 :cy}
                    is-width? #{:width}
                    is-height? #{:height}
                    is-other? #{:r :stroke-width}]

                (if is-percent?
                  (let [attr-num (d/parse-double (str/rtrim attr-val "%"))]
                    (str (cond
                           (is-x? attr-key)      (fix-coord  :x :width attr-num)
                           (is-y? attr-key)      (fix-coord  :y :height attr-num)
                           (is-width? attr-key)  (fix-length :width attr-num)
                           (is-height? attr-key) (fix-length :height attr-num)
                           (is-other? attr-key)  (fix-length :ratio attr-num)
                           :else attr-val)))
                  attr-val)))

            (fix-percent-attrs-viewbox [attrs]
              (d/mapm fix-percent-attr-viewbox attrs))

            (fix-percent-attr-numeric-val [val]
              (let [val (d/parse-double (str/rtrim val "%"))]
                (str (/ val 100))))

            (fix-percent-attr-numeric [attrs key val]
              (cond
                (= key :style)
                attrs

                (= key :unicode)
                attrs

                (str/starts-with? (d/name key) "data-")
                attrs

                (str/ends-with? val "%")
                (assoc attrs key (fix-percent-attr-numeric-val val))

                :else
                attrs))

            (fix-percent-values [node]
              (let [units (or (get-in node [:attrs :filterUnits])
                              (get-in node [:attrs :gradientUnits])
                              (get-in node [:attrs :patternUnits])
                              (get-in node [:attrs :clipUnits]))]

                (cond-> node
                  (or (= "objectBoundingBox" units) (nil? units))
                  (update :attrs #(reduce-kv fix-percent-attr-numeric % %))

                  (not= "objectBoundingBox" units)
                  (update :attrs fix-percent-attrs-viewbox))))]

      (map-nodes fix-percent-values svg-data))))

(defn collect-images [svg-data]
  (let [redfn (fn [acc {:keys [tag attrs]}]
                (cond-> acc
                  (= :image tag)
                  (conj {:href (or (:href attrs) (:xlink:href attrs))
                         :width (d/parse-integer (:width attrs) 0)
                         :height (d/parse-integer (:height attrs) 0)})))]
    (reduce-nodes redfn [] svg-data)))

#?(:clj
   (defn- secure-parser-factory
     [^InputStream input ^XMLHandler handler]
     (.. (doto (SAXParserFactory/newInstance)
           (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
           (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
         (newSAXParser)
         (parse input handler))))

(defn strip-doctype
  [data]
  (cond-> data
    (str/includes? data "<!DOCTYPE")
    (str/replace #"<\!DOCTYPE[^>]*>" "")))

(defn parse
  [text]
  #?(:cljs (tubax/xml->clj text)
     :clj  (let [text (strip-doctype text)]
             (dm/with-open [istream (IOUtils/toInputStream text "UTF-8")]
               (xml/parse istream secure-parser-factory)))))
