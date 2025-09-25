;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.token
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [clojure.data :as data]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [malli.util :as mu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- schema-keys
  "Converts registed map schema into set of keys."
  [schema]
  (->> schema
       (sm/schema)
       (mu/keys)
       (into #{})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def token-type->dtcg-token-type
  {:boolean         "boolean"
   :border-radius   "borderRadius"
   :color           "color"
   :dimensions      "dimension"
   :font-family     "fontFamilies"
   :font-size       "fontSizes"
   :letter-spacing  "letterSpacing"
   :number          "number"
   :opacity         "opacity"
   :other           "other"
   :rotation        "rotation"
   :sizing          "sizing"
   :spacing         "spacing"
   :string          "string"
   :stroke-width    "borderWidth"
   :text-case       "textCase"
   :text-decoration "textDecoration"
   :font-weight     "fontWeights"
   :typography      "typography"})

(def dtcg-token-type->token-type
  (set/map-invert token-type->dtcg-token-type))

(def token-types
  (into #{} (keys token-type->dtcg-token-type)))

(def token-name-ref
  [:re {:title "TokenNameRef" :gen/gen sg/text}
   #"^(?!\$)([a-zA-Z0-9-$_]+\.?)*(?<!\.)$"])

(def ^:private schema:color
  [:map
   [:fill {:optional true} token-name-ref]
   [:stroke-color {:optional true} token-name-ref]])

(def color-keys (schema-keys schema:color))

(def ^:private schema:border-radius
  [:map {:title "BorderRadiusTokenAttrs"}
   [:r1 {:optional true} token-name-ref]
   [:r2 {:optional true} token-name-ref]
   [:r3 {:optional true} token-name-ref]
   [:r4 {:optional true} token-name-ref]])

(def border-radius-keys (schema-keys schema:border-radius))

(def ^:private schema:stroke-width
  [:map
   [:stroke-width {:optional true} token-name-ref]])

(def stroke-width-keys (schema-keys schema:stroke-width))

(def ^:private schema:sizing-base
  [:map {:title "SizingBaseTokenAttrs"}
   [:width {:optional true} token-name-ref]
   [:height {:optional true} token-name-ref]])

(def ^:private schema:sizing-layout-item
  [:map {:title "SizingLayoutItemTokenAttrs"}
   [:layout-item-min-w {:optional true} token-name-ref]
   [:layout-item-max-w {:optional true} token-name-ref]
   [:layout-item-min-h {:optional true} token-name-ref]
   [:layout-item-max-h {:optional true} token-name-ref]])

(def ^:private schema:sizing
  (-> (reduce mu/union [schema:sizing-base
                        schema:sizing-layout-item])
      (mu/update-properties assoc :title "SizingTokenAttrs")))

(def sizing-layout-item-keys (schema-keys schema:sizing-layout-item))

(def sizing-keys (schema-keys schema:sizing))

(def ^:private schema:opacity
  [:map {:title "OpacityTokenAttrs"}
   [:opacity {:optional true} token-name-ref]])

(def opacity-keys (schema-keys schema:opacity))

(def ^:private schema:spacing-gap
  [:map {:title "SpacingGapTokenAttrs"}
   [:row-gap {:optional true} token-name-ref]
   [:column-gap {:optional true} token-name-ref]])

(def ^:private schema:spacing-padding
  [:map {:title "SpacingPaddingTokenAttrs"}
   [:p1 {:optional true} token-name-ref]
   [:p2 {:optional true} token-name-ref]
   [:p3 {:optional true} token-name-ref]
   [:p4 {:optional true} token-name-ref]])

(def ^:private schema:spacing-margin
  [:map {:title "SpacingMarginTokenAttrs"}
   [:m1 {:optional true} token-name-ref]
   [:m2 {:optional true} token-name-ref]
   [:m3 {:optional true} token-name-ref]
   [:m4 {:optional true} token-name-ref]])

(def ^:private schema:spacing
  (-> (reduce mu/union [schema:spacing-gap
                        schema:spacing-padding
                        schema:spacing-margin])
      (mu/update-properties assoc :title "SpacingTokenAttrs")))

(def spacing-margin-keys (schema-keys schema:spacing-margin))

(def spacing-keys (schema-keys schema:spacing))

(def ^:private schema:spacing-gap-padding
  (-> (reduce mu/union [schema:spacing-gap
                        schema:spacing-padding])
      (mu/update-properties assoc :title "SpacingGapPaddingTokenAttrs")))

(def spacing-gap-padding-keys (schema-keys schema:spacing-gap-padding))

(def ^:private schema:dimensions
  (-> (reduce mu/union [schema:sizing
                        schema:spacing
                        schema:stroke-width
                        schema:border-radius])
      (mu/update-properties assoc :title "DimensionsTokenAttrs")))

(def dimensions-keys (schema-keys schema:dimensions))

(def ^:private schema:axis
  [:map
   [:x {:optional true} token-name-ref]
   [:y {:optional true} token-name-ref]])

(def axis-keys (schema-keys schema:axis))

(def ^:private schema:rotation
  [:map {:title "RotationTokenAttrs"}
   [:rotation {:optional true} token-name-ref]])

(def rotation-keys (schema-keys schema:rotation))

(def ^:private schema:font-size
  [:map {:title "FontSizeTokenAttrs"}
   [:font-size {:optional true} token-name-ref]])

(def font-size-keys (schema-keys schema:font-size))

(def ^:private schema:letter-spacing
  [:map {:title "LetterSpacingTokenAttrs"}
   [:letter-spacing {:optional true} token-name-ref]])

(def letter-spacing-keys (schema-keys schema:letter-spacing))

(def ^:private schema:font-family
  [:map
   [:font-family {:optional true} token-name-ref]])

(def font-family-keys (schema-keys schema:font-family))

(def ^:private schema:text-case
  [:map
   [:text-case {:optional true} token-name-ref]])

(def text-case-keys (schema-keys schema:text-case))

(def ^:private schema:font-weight
  [:map
   [:font-weight {:optional true} token-name-ref]])

(def font-weight-keys (schema-keys schema:font-weight))

(def ^:private schema:typography
  [:map
   [:typography {:optional true} token-name-ref]])

(def typography-token-keys (schema-keys schema:typography))

(def ^:private schema:text-decoration
  [:map
   [:text-decoration {:optional true} token-name-ref]])

(def text-decoration-keys (schema-keys schema:text-decoration))

(def typography-keys (set/union font-size-keys
                                letter-spacing-keys
                                font-family-keys
                                font-weight-keys
                                text-case-keys
                                text-decoration-keys
                                font-weight-keys
                                typography-token-keys))

;; TODO: Created to extract the font-size feature from the typography feature flag.
;; Delete this once the typography feature flag is removed.
(def ff-typography-keys (set/difference typography-keys font-size-keys))

(def ^:private schema:number
  (-> (reduce mu/union [[:map [:line-height {:optional true} token-name-ref]]
                        schema:rotation])
      (mu/update-properties assoc :title "NumberTokenAttrs")))

(def number-keys (schema-keys schema:number))

(def all-keys (set/union color-keys
                         border-radius-keys
                         stroke-width-keys
                         sizing-keys
                         opacity-keys
                         spacing-keys
                         dimensions-keys
                         axis-keys
                         rotation-keys
                         typography-keys
                         typography-token-keys
                         number-keys))

(def ^:private schema:tokens
  [:map {:title "GenericTokenAttrs"}])

(def schema:applied-tokens
  [:merge {:title "AppliedTokens"}
   schema:tokens
   schema:border-radius
   schema:sizing
   schema:spacing
   schema:rotation
   schema:number
   schema:font-size
   schema:letter-spacing
   schema:font-family
   schema:text-case
   schema:text-decoration
   schema:dimensions])

(defn shape-attr->token-attrs
  ([shape-attr] (shape-attr->token-attrs shape-attr nil))
  ([shape-attr changed-sub-attr]
   (cond
     (= :fills shape-attr)
     #{:fill}

     (and (= :strokes shape-attr) (nil? changed-sub-attr))
     #{:stroke-width :stroke-color}

     (= :strokes shape-attr)
     (cond
       (some #{:stroke-color} changed-sub-attr) #{:stroke-color}
       (some #{:stroke-width} changed-sub-attr) #{:stroke-width})

     (= :layout-padding shape-attr)
     (if (seq changed-sub-attr)
       changed-sub-attr
       #{:p1 :p2 :p3 :p4})

     (= :layout-item-margin shape-attr)
     (if (seq changed-sub-attr)
       changed-sub-attr
       #{:m1 :m2 :m3 :m4})

     (font-size-keys shape-attr)       #{shape-attr :typography}
     (letter-spacing-keys shape-attr)  #{shape-attr :typography}
     (font-family-keys shape-attr)     #{shape-attr :typography}
     (= :text-transform shape-attr)    #{:text-case :typography}
     (text-decoration-keys shape-attr) #{shape-attr :typography}
     (font-weight-keys shape-attr)     #{shape-attr :typography}

     (border-radius-keys shape-attr) #{shape-attr}
     (sizing-keys shape-attr) #{shape-attr}
     (opacity-keys shape-attr) #{shape-attr}
     (spacing-keys shape-attr) #{shape-attr}
     (rotation-keys shape-attr) #{shape-attr}
     (number-keys shape-attr) #{shape-attr}
     (axis-keys shape-attr) #{shape-attr})))

(defn token-attr->shape-attr
  [token-attr]
  (case token-attr
    :fill :fills
    :stroke-color :strokes
    :stroke-width :strokes
    token-attr))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKEN SHAPE ATTRIBUTES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def position-attributes #{:x :y})

(def generic-attributes
  (set/union color-keys
             stroke-width-keys
             rotation-keys
             sizing-keys
             opacity-keys
             position-attributes))

(def rect-attributes
  (set/union generic-attributes
             border-radius-keys))

(def frame-with-layout-attributes
  (set/union rect-attributes
             spacing-gap-padding-keys))

(def text-attributes
  (set/union generic-attributes
             typography-keys
             number-keys))

(defn shape-type->attributes
  [type is-layout]
  (case type
    :bool    generic-attributes
    :circle  generic-attributes
    :rect    rect-attributes
    :frame   (if is-layout
               frame-with-layout-attributes
               rect-attributes)
    :image   rect-attributes
    :path    generic-attributes
    :svg-raw generic-attributes
    :text    text-attributes
    nil))

(defn appliable-attrs
  "Returns intersection of shape `attributes` for `shape-type`."
  [attributes shape-type is-layout]
  (set/intersection attributes (shape-type->attributes shape-type is-layout)))

(defn any-appliable-attr?
  "Checks if `token-type` supports given shape `attributes`."
  [attributes token-type is-layout]
  (seq (appliable-attrs attributes token-type is-layout)))

;; Token attrs that are set inside content blocks of text shapes, instead
;; at the shape level.
(def attrs-in-text-content
  (set/union
   typography-keys
   #{:fill}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS IN SHAPES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- toggle-or-apply-token
  "Remove any shape attributes from token if they exists.
  Othewise apply token attributes."
  [shape token]
  (let [[shape-leftover token-leftover _matching] (data/diff (:applied-tokens shape) token)]
    (merge {} shape-leftover token-leftover)))

(defn- token-from-attributes [token attributes]
  (->> (map (fn [attr] [attr (:name token)]) attributes)
       (into {})))

(defn- apply-token-to-attributes [{:keys [shape token attributes]}]
  (let [token (token-from-attributes token attributes)]
    (toggle-or-apply-token shape token)))

(defn apply-token-to-shape
  [{:keys [shape token attributes] :as _props}]
  (let [applied-tokens (apply-token-to-attributes {:shape shape
                                                   :token token
                                                   :attributes attributes})]
    (update shape :applied-tokens #(merge % applied-tokens))))

(defn unapply-token-id [shape attributes]
  (update shape :applied-tokens d/without-keys attributes))

(defn unapply-layout-item-tokens
  "Unapplies all layout item related tokens from shape."
  [shape]
  (let [layout-item-attrs (set/union sizing-layout-item-keys
                                     spacing-margin-keys)]
    (unapply-token-id shape layout-item-attrs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TYPOGRAPHY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn split-font-family
  "Splits font family `value` string from into vector of font families.

  Doesn't handle possible edge-case of font-families with `,` in their font family name."
  [font-value]
  (let [families (str/split font-value ",")
        xform (comp
               (map str/trim)
               (remove str/empty?))]
    (into [] xform families)))

(defn join-font-family
  "Joins font family `value` into a string to be edited with a single input."
  [font-families]
  (str/join ", " font-families))

(def text-decoration-values #{"none" "underline" "strike-through"})

(defn valid-text-decoration [value]
  (let [normalized-value (str/lower (str/trim value))]
    (when (contains? text-decoration-values normalized-value)
      normalized-value)))

(def font-weight-aliases
  {"100" #{"thin" "hairline"},
   "200" #{"ultra light" "extralight" "extraleicht" "extra-light" "ultra-light" "ultralight" "extra light"},
   "300" #{"light" "leicht"},
   "400" #{"book" "normal" "buch" "regular"},
   "500" #{"kräftig" "medium" "kraeftig"},
   "600" #{"demi-bold" "halbfett" "demibold" "demi bold" "semibold" "semi bold" "semi-bold"},
   "700" #{"dreiviertelfett" "bold"},
   "800" #{"extrabold" "fett" "extra-bold" "ultrabold" "ultra-bold" "extra bold" "ultra bold"},
   "900" #{"heavy" "black" "extrafett"},
   "950" #{"extra-black" "extra black" "ultra-black" "ultra black"}})

(def font-weight-values (into #{} (keys font-weight-aliases)))

(def font-weight-map
  "A map of font-weight aliases that map to their number equivalent used by penpot fonts per `:weight`."
  (->> font-weight-aliases
       (reduce (fn [acc [k vs]]
                 (into acc (zipmap vs (repeat k)))) {})))

(defn parse-font-weight [font-weight]
  (let [[_ variant italic] (->> (str font-weight)
                                (str/lower)
                                (re-find #"^(.+?)\s*(italic)?$"))]
    {:variant variant
     :italic? (some? italic)}))

(defn valid-font-weight-variant
  "Converts font-weight token value to a map like `{:weight \"100\" :style \"italic\"}`.
  Converts a weight alias like `regular` to a number, needs to be a regular number.
  Adds `italic` style when found in the `value` string."
  [value]
  (let [{:keys [variant italic?]} (parse-font-weight value)
        weight (get font-weight-map variant variant)]
    (when (font-weight-values weight)
      (cond-> {:weight weight}
        italic? (assoc :style "italic")))))
