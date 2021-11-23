;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.svg
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; Regex for XML ids per Spec
;; https://www.w3.org/TR/2008/REC-xml-20081126/#sec-common-syn
(defonce xml-id-regex #"#([:A-Z_a-z\xC0-\xD6\xD8-\xF6\xF8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD\u10000-\uEFFFF][\.\-\:0-9\xB7A-Z_a-z\xC0-\xD6\xD8-\xF6\xF8-\u02FF\u0300-\u036F\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u203F-\u2040\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD\u10000-\uEFFFF]*)")

(defonce matrices-regex #"(matrix|translate|scale|rotate|skewX|skewY)\(([^\)]*)\)")
(defonce number-regex #"[+-]?\d*(\.\d+)?(e[+-]?\d+)?")

(defonce tags-to-remove #{:defs :linearGradient :radialGradient :metadata :mask :clipPath :filter :title})

;; https://www.w3.org/TR/SVG11/eltindex.html
(defonce svg-tags-list
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
    :script
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
    :vkern
    })

;; https://www.w3.org/TR/SVG11/attindex.html
(defonce svg-attr-list
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
    ;; We don't support events
    ;;:onabort
    ;;:onactivate
    ;;:onbegin
    ;;:onclick
    ;;:onend
    ;;:onerror
    ;;:onfocusin
    ;;:onfocusout
    ;;:onload
    ;;:onmousedown
    ;;:onmousemove
    ;;:onmouseout
    ;;:onmouseover
    ;;:onmouseup
    ;;:onrepeat
    ;;:onresize
    ;;:onscroll
    ;;:onunload
    ;;:onzoom
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

(defonce svg-present-list
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

(defonce inheritable-props
  [:clip-rule
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
   :writing-mode])

(defonce gradient-tags
  #{:linearGradient
    :radialGradient})

(defonce filter-tags
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

;; Props not supported by react we need to keep them lowercase
(defonce non-react-props
  #{:mask-type})

;; Defaults for some tags per spec https://www.w3.org/TR/SVG11/single-page.html
;; they are basically the defaults that can be percents and we need to replace because
;; otherwise won't work as expected in the workspace
(defonce svg-tag-defaults
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
    (str "0" num-str)

    (str/starts-with? num-str "-.")
    (str "-0" (subs num-str 1))

    :else
    num-str))

(defn format-styles
  "Transforms attributes to their react equivalent"
  [attrs]
  (letfn [(format-styles [style-str]
            (if (string? style-str)
              (->> (str/split style-str ";")
                   (map str/trim)
                   (map #(str/split % ":"))
                   (group-by first)
                   (map (fn [[key val]]
                          (vector (keyword key) (second (first val)))))
                   (into {}))
              style-str))]

    (cond-> attrs
      (contains? attrs :style)
      (update :style format-styles))))

(defn clean-attrs
  "Transforms attributes to their react equivalent"
  ([attrs] (clean-attrs attrs true))
  ([attrs whitelist?]
   (letfn [(known-property? [[key _]]
             (or (not whitelist?)
                 (contains? svg-attr-list key )
                 (contains? svg-present-list key )))

           (transform-key [key]
             (if (contains? non-react-props key)
               key
               (-> (d/name key)
                   (str/replace ":" "-")
                   (str/camel)
                   (keyword))))

           (format-styles [style-str]
             (->> (str/split style-str ";")
                  (map str/trim)
                  (map #(str/split % ":"))
                  (group-by first)
                  (map (fn [[key val]]
                         (vector
                          (transform-key key)
                          (second (first val)))))
                  (into {})))

           (map-fn [[key val]]
             (let [key (keyword key)]
               (cond
                 (= key :class) [:className val]
                 (and (= key :style) (string? val)) [key (format-styles val)]
                 (and (= key :style) (map? val)) [key (clean-attrs val false)]
                 :else (vector (transform-key key) val))))

           ]

     (let [filtered-props (->> attrs (remove known-property?) (map first))]
       (when (seq filtered-props)
         (.warn js/console "Unknown properties: " (str/join ", " filtered-props  ))))

     (into {} (comp (filter known-property?) (map map-fn)) attrs))))

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
  (if (and ids-mapping (seq ids-mapping))
    (update-attr-ids attrs (fn [id] (get ids-mapping id id)))
    ;; Ids-mapping is null
    attrs))

(defn generate-id-mapping [content]
  (letfn [(visit-node [result node]
            (let [element-id (get-in node [:attrs :id])
                  result (cond-> result
                           element-id (assoc element-id (str (uuid/next))))]
              (reduce visit-node result (:content node))))]
    (visit-node {} content)))

(defn extract-defs [{:keys [attrs] :as node}]
  (if-not (map? node)
    [{} node]

    (let [remove-node? (fn [{:keys [tag]}] (and (some? tag)
                                                (or (contains? tags-to-remove tag)
                                                    (not (contains? svg-tags-list tag)))))
          rec-result (->> (:content node) (map extract-defs))
          node (assoc node :content (->> rec-result (map second) (filterv (comp not remove-node?))))

          current-node-defs (if (contains? attrs :id)
                              (hash-map (:id attrs) node)
                              (hash-map))

          node-defs (->> rec-result (map first) (reduce merge current-node-defs))]

      [ node-defs node ])))

(defn find-attr-references [attrs]
  (->> attrs
       (mapcat (fn [[_ attr-value]]
                 (if (string? attr-value)
                   (extract-ids attr-value)
                   (find-attr-references attr-value))))))

(defn find-node-references [node]
  (let [current (->> (find-attr-references (:attrs node)) (into #{}))
        children (->> (:content node) (map find-node-references) (flatten) (into #{}))]
    (-> (d/concat current children)
        (vec))))

(defn find-def-references [defs references]
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
        (recur (d/concat result new-refs)
               (conj checked? to-check)
               (first pending)
               (rest pending))))))

(defn svg-transform-matrix [shape]
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
       (if (= :path (:type shape))
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


(defn format-translate-params [params]
  (assert (or (= (count params) 1) (= (count params) 2)))
  (if (= (count params) 1)
    [(gpt/point (nth params 0) 0)]
    [(gpt/point (nth params 0) (nth params 1))]))

(defn format-scale-params [params]
  (assert (or (= (count params) 1) (= (count params) 2)))
  (if (= (count params) 1)
    [(gpt/point (nth params 0))]
    [(gpt/point (nth params 0) (nth params 1))]))

(defn format-rotate-params [params]
  (assert (or (= (count params) 1) (= (count params) 3)) (str "??" (count params)))
  (if (= (count params) 1)
    [(nth params 0) (gpt/point 0 0)]
    [(nth params 0) (gpt/point (nth params 1) (nth params 2))]))

(defn format-skew-x-params [params]
  (assert (= (count params) 1))
  [(nth params 0) 0])

(defn format-skew-y-params [params]
  (assert (= (count params) 1))
  [0 (nth params 0)])

(defn to-matrix [{:keys [type params]}]
  (assert (#{"matrix" "translate" "scale" "rotate" "skewX" "skewY"} type))
  (case type
    "matrix"    (apply gmt/matrix params)
    "translate" (apply gmt/translate-matrix (format-translate-params params))
    "scale"     (apply gmt/scale-matrix (format-scale-params params))
    "rotate"    (apply gmt/rotate-matrix (format-rotate-params params))
    "skewX"     (apply gmt/skew-matrix (format-skew-x-params params))
    "skewY"     (apply gmt/skew-matrix (format-skew-y-params params))))

(defn parse-transform [transform-attr]
  (if transform-attr
    (let [process-matrix
          (fn [[_ type params]]
            (let [params (->> (re-seq number-regex params)
                              (filter #(-> % first seq))
                              (map (comp d/parse-double first)))]
              {:type type :params params}))

          matrices (->> (re-seq matrices-regex transform-attr)
                        (map process-matrix)
                        (map to-matrix))]
      (reduce gmt/multiply (gmt/matrix) matrices))
    (gmt/matrix)))

(defn format-move [[x y]] (str "M" x " " y))
(defn format-line [[x y]] (str "L" x " " y))

(defn points->path [points-str]
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

(defn inherit-attributes [group-attrs {:keys [attrs] :as node}]
  (if (map? node)
    (let [attrs (-> (format-styles attrs)
                    (add-transform (:transform group-attrs)))
          attrs (d/deep-merge (select-keys group-attrs inheritable-props) attrs)]
      (assoc node :attrs attrs))
    node))

(defn map-nodes [mapfn node]
  (let [update-content
        (fn [content] (cond->> content
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
  "Gives values to some SVG elements which defaults won't work when imported into the platform"
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
  (/ (mth/sqrt (+ (mth/pow width 2)
                  (mth/pow height 2)))
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
                  ;; JS parseFloat removes the % symbol
                  (let [attr-num (d/parse-double attr-val)]
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

            (fix-percent-attr-numeric [_ attr-val]
              (let [is-percent? (str/ends-with? attr-val "%")]
                (if is-percent?
                  (str (let [attr-num (d/parse-double attr-val)]
                         (/ attr-num 100)))
                  attr-val)))

            (fix-percent-attrs-numeric [attrs]
              (d/mapm fix-percent-attr-numeric attrs))

            (fix-percent-values [node]
              (let [units (or (get-in node [:attrs :filterUnits])
                              (get-in node [:attrs :gradientUnits])
                              (get-in node [:attrs :patternUnits])
                              (get-in node [:attrs :clipUnits]))]
                (cond-> node
                  (= "objectBoundingBox" units)
                  (update :attrs fix-percent-attrs-numeric)

                  (not= "objectBoundingBox" units)
                  (update :attrs fix-percent-attrs-viewbox))))]

      (->> svg-data (map-nodes fix-percent-values)))))

(defn collect-images [svg-data]
  (let [redfn (fn [acc {:keys [tag attrs]}]
                (cond-> acc
                  (= :image tag)
                  (conj (:xlink:href attrs))))]
    (reduce-nodes redfn [] svg-data )))
