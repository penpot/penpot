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
   [app.common.time :as ct]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [malli.util :as mu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- schema-keys
  "Converts registed map schema into set of keys."
  [schema]
  (->> schema
       (sm/schema)
       (mu/keys)
       (into #{})))

(defn find-token-value-references
  "Returns set of token references found in `token-value`.

  Used for checking if a token has a reference in the value.
  Token references are strings delimited by curly braces.
  E.g.: {foo.bar.baz} -> foo.bar.baz"
  [token-value]
  (if (string? token-value)
    (some->> (re-seq #"\{([^}]*)\}" token-value)
             (map second)
             (into #{}))
    #{}))

(defn token-value-self-reference?
  "Check if the token is self referencing with its `token-name` in `token-value`.
  Simple 1 level check, doesn't account for circular self refernces across multiple tokens."
  [token-name token-value]
  (let [token-references (find-token-value-references token-value)
        self-reference? (get token-references token-name)]
    (boolean self-reference?)))

(defn references-token?
  "Recursively check if a value references the token name. Handles strings, maps, and sequences."
  [value token-name]
  (cond
    (string? value)
    (boolean (some #(= % token-name) (find-token-value-references value)))
    (map? value)
    (some true? (map #(references-token? % token-name) (vals value)))
    (sequential? value)
    (some true? (map #(references-token? % token-name) value))
    :else false))

(defn composite-token-reference?
  "Predicate if a composite token is a reference value - a string pointing to another token."
  [token-value]
  (string? token-value))

(defn update-token-value-references
  "Recursively update token references within a token value, supporting complex token values (maps, sequences, strings)."
  [value old-name new-name]
  (cond
    (string? value)
    (str/replace value
                 (re-pattern (str "\\{" (str/replace old-name "." "\\.") "\\}"))
                 (str "{" new-name "}"))
    (map? value)
    (d/update-vals value #(update-token-value-references % old-name new-name))
    (sequential? value)
    (mapv #(update-token-value-references % old-name new-name) value)
    :else
    value))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA: Token types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def token-type->dtcg-token-type
  {:boolean         "boolean"
   :border-radius   "borderRadius"
   :color           "color"
   :dimensions      "dimension"
   :font-family     "fontFamilies"
   :font-size       "fontSizes"
   :font-weight     "fontWeights"
   :letter-spacing  "letterSpacing"
   :number          "number"
   :opacity         "opacity"
   :other           "other"
   :rotation        "rotation"
   :shadow          "shadow"
   :sizing          "sizing"
   :spacing         "spacing"
   :string          "string"
   :stroke-width    "borderWidth"
   :text-case       "textCase"
   :text-decoration "textDecoration"
   :typography      "typography"})

(def dtcg-token-type->token-type
  (-> (set/map-invert token-type->dtcg-token-type)
      ;; Allow these properties to be imported with singular key names for backwards compability
      (assoc "fontWeight" :font-weight
             "fontSize" :font-size
             "fontFamily" :font-family
             "boxShadow" :shadow)))

(def composite-token-type->dtcg-token-type
  "When converting the type of one element inside a composite token, an additional type
   :line-height is available, that is not allowed for a standalone token."
  (assoc token-type->dtcg-token-type
         :line-height "lineHeights"))

(def composite-dtcg-token-type->token-type
  "Same as above, in the opposite direction."
  (assoc dtcg-token-type->token-type
         "lineHeights" :line-height
         "lineHeight"  :line-height))

(def token-types
  (into #{} (keys token-type->dtcg-token-type)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA: Token
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def token-name-validation-regex
  #"^[a-zA-Z0-9_-][a-zA-Z0-9$_-]*(\.[a-zA-Z0-9$_-]+)*$")

(def schema:token-name
  "A token name can contains letters, numbers, underscores the character $ and dots, but
   not start with $ or end with a dot. The $ character does not have any special meaning,
   but dots separate token groups (e.g. color.primary.background)."
  [:re {:title "TokenName"
        :gen/gen sg/text}
   token-name-validation-regex])

(def token-ref-validation-regex
  #"^\{[a-zA-Z0-9_-][a-zA-Z0-9$_-]*(\.[a-zA-Z0-9$_-]+)*\}$")

(def schema:token-ref
  "A token reference is a token name enclosed in {}."
  [:re {:title "TokenRef"
        :gen/gen sg/text}
   token-ref-validation-regex])

(def schema:token-type
  [::sm/one-of {:decode/json (fn [type]
                               (if (string? type)
                                 (dtcg-token-type->token-type type)
                                 type))}

   token-types])

(def schema:token-attrs
  [:map {:title "Token"}
   [:id ::sm/uuid]
   [:name schema:token-name]
   [:type schema:token-type]
   [:value ::sm/any]
   [:description {:optional true} :string]
   [:modified-at {:optional true} ::ct/inst]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA: Token application to shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; All the following schemas define the `:applied-tokens` attribute of a shape.
;; This attribute is a map <token-attribute> -> <token-name>.
;; Token attributes approximately match shape attributes, but not always.
;; For each schema there is a `*keys` set including all the possible token attributes
;; to which a token of the corresponding type can be applied.
;; Some token types can be applied to some attributes only if the shape has a
;; particular condition (i.e. has a layout itself or is a layout item).

(def ^:private schema:border-radius
  [:map {:title "BorderRadiusTokenAttrs"}
   [:r1 {:optional true} schema:token-name]
   [:r2 {:optional true} schema:token-name]
   [:r3 {:optional true} schema:token-name]
   [:r4 {:optional true} schema:token-name]])

(def border-radius-keys (schema-keys schema:border-radius))

(def ^:private schema:color
  [:map
   [:fill {:optional true} schema:token-name]
   [:stroke-color {:optional true} schema:token-name]])

(def color-keys (schema-keys schema:color))

(def ^:private schema:sizing-base
  [:map {:title "SizingBaseTokenAttrs"}
   [:width {:optional true} schema:token-name]
   [:height {:optional true} schema:token-name]])

(def ^:private schema:sizing-layout-item
  [:map {:title "SizingLayoutItemTokenAttrs"}
   [:layout-item-min-w {:optional true} schema:token-name]
   [:layout-item-max-w {:optional true} schema:token-name]
   [:layout-item-min-h {:optional true} schema:token-name]
   [:layout-item-max-h {:optional true} schema:token-name]])

(def sizing-layout-item-keys (schema-keys schema:sizing-layout-item))

(def ^:private schema:sizing
  (-> (reduce mu/union [schema:sizing-base
                        schema:sizing-layout-item])
      (mu/update-properties assoc :title "SizingTokenAttrs")))

(def sizing-keys (schema-keys schema:sizing))

(def ^:private schema:spacing-gap
  [:map {:title "SpacingGapTokenAttrs"}
   [:row-gap {:optional true} schema:token-name]
   [:column-gap {:optional true} schema:token-name]])

(def ^:private schema:spacing-padding
  [:map {:title "SpacingPaddingTokenAttrs"}
   [:p1 {:optional true} schema:token-name]
   [:p2 {:optional true} schema:token-name]
   [:p3 {:optional true} schema:token-name]
   [:p4 {:optional true} schema:token-name]])

(def ^:private schema:spacing-gap-padding
  (-> (reduce mu/union [schema:spacing-gap
                        schema:spacing-padding])
      (mu/update-properties assoc :title "SpacingGapPaddingTokenAttrs")))

(def spacing-gap-padding-keys (schema-keys schema:spacing-gap-padding))

(def ^:private schema:spacing-margin
  [:map {:title "SpacingMarginTokenAttrs"}
   [:m1 {:optional true} schema:token-name]
   [:m2 {:optional true} schema:token-name]
   [:m3 {:optional true} schema:token-name]
   [:m4 {:optional true} schema:token-name]])

(def spacing-margin-keys (schema-keys schema:spacing-margin))

(def ^:private schema:spacing
  (-> (reduce mu/union [schema:spacing-gap
                        schema:spacing-padding
                        schema:spacing-margin])
      (mu/update-properties assoc :title "SpacingTokenAttrs")))

(def spacing-keys (schema-keys schema:spacing))

(def ^:private schema:stroke-width
  [:map
   [:stroke-width {:optional true} schema:token-name]])

(def stroke-width-keys (schema-keys schema:stroke-width))

(def ^:private schema:dimensions
  (-> (reduce mu/union [schema:sizing
                        schema:spacing
                        schema:stroke-width
                        schema:border-radius])
      (mu/update-properties assoc :title "DimensionsTokenAttrs")))

(def dimensions-keys (schema-keys schema:dimensions))

(def ^:private schema:font-family
  [:map
   [:font-family {:optional true} schema:token-name]])

(def font-family-keys (schema-keys schema:font-family))

(def ^:private schema:font-size
  [:map {:title "FontSizeTokenAttrs"}
   [:font-size {:optional true} schema:token-name]])

(def font-size-keys (schema-keys schema:font-size))

(def ^:private schema:font-weight
  [:map
   [:font-weight {:optional true} schema:token-name]])

(def font-weight-keys (schema-keys schema:font-weight))

(def ^:private schema:letter-spacing
  [:map {:title "LetterSpacingTokenAttrs"}
   [:letter-spacing {:optional true} schema:token-name]])

(def letter-spacing-keys (schema-keys schema:letter-spacing))

(def ^:private schema:line-height        ;; This is not available for standalone tokens, only typography
  [:map {:title "LineHeightTokenAttrs"}
   [:line-height {:optional true} schema:token-name]])

(def line-height-keys (schema-keys schema:line-height))

(def ^:private schema:rotation
  [:map {:title "RotationTokenAttrs"}
   [:rotation {:optional true} schema:token-name]])

(def rotation-keys (schema-keys schema:rotation))

(def ^:private schema:number
  (-> (reduce mu/union [schema:line-height
                        schema:rotation])
      (mu/update-properties assoc :title "NumberTokenAttrs")))

(def number-keys (schema-keys schema:number))

(def ^:private schema:opacity
  [:map {:title "OpacityTokenAttrs"}
   [:opacity {:optional true} schema:token-name]])

(def opacity-keys (schema-keys schema:opacity))

(def ^:private schema:shadow
  [:map {:title "ShadowTokenAttrs"}
   [:shadow {:optional true} schema:token-name]])

(def shadow-keys (schema-keys schema:shadow))

(def ^:private schema:text-case
  [:map
   [:text-case {:optional true} schema:token-name]])

(def text-case-keys (schema-keys schema:text-case))

(def ^:private schema:text-decoration
  [:map
   [:text-decoration {:optional true} schema:token-name]])

(def text-decoration-keys (schema-keys schema:text-decoration))

(def ^:private schema:typography
  [:map
   [:typography {:optional true} schema:token-name]])

(def typography-token-keys (schema-keys schema:typography))

(def typography-keys (set/union font-family-keys
                                font-size-keys
                                font-weight-keys
                                font-weight-keys
                                letter-spacing-keys
                                line-height-keys
                                text-case-keys
                                text-decoration-keys
                                typography-token-keys))

(def ^:private schema:axis
  [:map
   [:x {:optional true} schema:token-name]
   [:y {:optional true} schema:token-name]])

(def axis-keys (schema-keys schema:axis))

(def all-keys (set/union axis-keys
                         border-radius-keys
                         color-keys
                         dimensions-keys
                         number-keys
                         opacity-keys
                         rotation-keys
                         shadow-keys
                         sizing-keys
                         spacing-keys
                         stroke-width-keys
                         typography-keys
                         typography-token-keys))

(def ^:private schema:tokens
  [:map {:title "GenericTokenAttrs"}])

(def schema:applied-tokens
  [:merge {:title "AppliedTokens"}
   schema:tokens
   schema:border-radius
   schema:shadow
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS for conversion between token attrs and shape attrs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn token-attr?
  [attr]
  (contains? all-keys attr))

(defn token-attr->shape-attr
  "Returns the actual shape attribute affected when a token have been applied
   to a given `token-attr`."
  [token-attr]
  (case token-attr
    :fill :fills
    :stroke-color :strokes
    :stroke-width :strokes
    token-attr))

(defn shape-attr->token-attrs
  "Returns the token-attr affected when a given attribute in a shape is changed.
   The sub-attr is for attributes that may have multiple values, like strokes
   (may be width or color) and layout padding & margin (may have 4 edges)."
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
     (= :line-height shape-attr)       #{:line-height :typography}
     (= :text-transform shape-attr)    #{:text-case :typography}
     (text-decoration-keys shape-attr) #{shape-attr :typography}
     (font-weight-keys shape-attr)     #{shape-attr :typography}

     (border-radius-keys shape-attr) #{shape-attr}
     (shadow-keys shape-attr) #{shape-attr}
     (sizing-keys shape-attr) #{shape-attr}
     (opacity-keys shape-attr) #{shape-attr}
     (spacing-keys shape-attr) #{shape-attr}
     (rotation-keys shape-attr) #{shape-attr}
     (number-keys shape-attr) #{shape-attr}
     (axis-keys shape-attr) #{shape-attr})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS for token attributes by shape type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private position-attributes #{:x :y})

(def ^:private generic-attributes
  (set/union color-keys
             stroke-width-keys
             rotation-keys
             sizing-keys
             opacity-keys
             shadow-keys
             position-attributes))

(def ^:private rect-attributes
  (set/union generic-attributes
             border-radius-keys))

(def ^:private frame-with-layout-attributes
  (set/union rect-attributes
             spacing-gap-padding-keys))

(def ^:private text-attributes
  (set/union generic-attributes
             typography-keys
             number-keys))

(defn shape-type->attributes
  "Returns what token attributes may be applied to a shape depending on its type
   and if it is a frame with a layout."
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

(defn appliable-attrs-for-shape
  "Returns which ones of the given `attributes` can be applied to a shape
   of type `shape-type` and `is-layout`."
  [attributes shape-type is-layout]
  (set/intersection attributes (shape-type->attributes shape-type is-layout)))

(defn any-appliable-attr-for-shape?
  "Returns if any of the given `attributes` can be applied to a shape
   of type `shape-type` and `is-layout`."
  [attributes token-type is-layout]
  (d/not-empty? (appliable-attrs-for-shape attributes token-type is-layout)))

;; Token attrs that are set inside content blocks of text shapes, instead
;; at the shape level.
(def attrs-in-text-content
  (set/union
   typography-keys
   #{:fill}))

(def tokens-by-input
  "A map from input name to applicable token for that input."
  {:width #{:sizing :dimensions}
   :height #{:sizing :dimensions}
   :max-width #{:sizing :dimensions}
   :max-height #{:sizing :dimensions}
   :min-width #{:sizing :dimensions}
   :min-height #{:sizing :dimensions}
   :x #{:dimensions}
   :y #{:dimensions}
   :rotation #{:number :rotation}
   :border-radius #{:border-radius :dimensions}
   :row-gap #{:spacing :dimensions}
   :column-gap #{:spacing :dimensions}
   :horizontal-padding #{:spacing :dimensions}
   :vertical-padding #{:spacing :dimensions}
   :sided-paddings #{:spacing :dimensions}
   :horizontal-margin #{:spacing :dimensions}
   :vertical-margin #{:spacing :dimensions}
   :sided-margins #{:spacing :dimensions}
   :line-height #{:line-height :number}
   :opacity #{:opacity}
   :stroke-width #{:stroke-width :dimensions}
   :font-size #{:font-size}
   :letter-spacing #{:letter-spacing}
   :fill #{:color}
   :stroke-color #{:color}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS for tokens application
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- generate-attr-map [token attributes]
  (->> (map (fn [attr] [attr (:name token)]) attributes)
       (into {})))

(defn apply-token-to-shape
  "Applies the token to the given attributes in the shape."
  [{:keys [shape token attributes] :as _props}]
  (let [map-to-apply (generate-attr-map token attributes)]
    (update shape :applied-tokens #(merge % map-to-apply))))

(defn unapply-tokens-from-shape
  "Removes any token applied to the given attributes in the shape."
  [shape attributes]
  (update shape :applied-tokens d/without-keys attributes))

(defn unapply-layout-item-tokens
  "Unapplies all layout item related tokens from shape."
  [shape]
  (let [layout-item-attrs (set/union sizing-layout-item-keys
                                     spacing-margin-keys)]
    (unapply-tokens-from-shape shape layout-item-attrs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS for typography tokens
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
   "700" #{"dreivi
            ertelfett" "bold"},
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


;;;;;; Combobox token parsing

(defn inside-ref?
  "Returns true if `position` in `value` is inside an open reference block (i.e. after a `{`
   that has no matching `}` to its left).
   A reference block is considered open when the last `{` appears after the last `}`,
   or when there is a `{` but no `}` at all to the left of `position`."
  [value position]
  (let [left        (str/slice value 0 position)
        last-open   (str/last-index-of left "{")
        last-close  (str/last-index-of left "}")]
    (and (some? last-open)
         (or (nil? last-close)
             (< last-close last-open)))))

(defn- block-open-start
  "Returns the index of the leftmost `{` in the run of consecutive `{` characters
   that contains the last `{` before `position` in `value`.
   Used to find where a reference block truly starts when multiple braces are stacked."
  [value position]
  (let [left      (str/slice value 0 position)
        last-open (str/last-index-of left "{")]
    (loop [i last-open]
      (if (and i
               (> i 0)
               (= (nth left (dec i)) \{))
        (recur (dec i))
        i))))

(defn- start-ref-position
  "Returns the position where the current token (reference candidate) starts,
   relative to `position` in `value`.
   The start is determined by whichever comes last: the opening `{` of the current
   reference block or the character after the last space before `position`."
  [value position]
  (let [left      (str/slice value 0 position)
        open-pos  (block-open-start value position)
        space-pos (some-> (str/last-index-of left " ") inc)]
    (->> [open-pos space-pos]
         (remove nil?)
         sort
         last)))

(defn inside-closed-ref?
  "Returns true if `position` falls inside a complete (closed) reference block,
     i.e. there is a `{` to the left and a `}` to the right with no spaces between
     either delimiter and the position.
     Returns nil (falsy) when not inside a closed reference."
  [value position]
  (let [left              (str/slice value 0 position)
        right             (str/slice value position)
        open-pos          (d/nth-last-index-of left "{" 1)
        close-pos         (d/nth-index-of right "}" 1)
        last-space-left   (d/nth-last-index-of left " " 1)
        first-space-right (d/nth-index-of right " " 1)]
    (boolean
     (and open-pos
          close-pos
          (or (nil? last-space-left)   (> open-pos last-space-left))
          (or (nil? first-space-right) (< close-pos first-space-right))))))

(defn- build-result
  "Builds the result map for `insert-ref` by replacing the substring of `value`
   between `prefix-end` and `suffix-start` with a formatted reference `{name}`.
   Returns a map with:
     :value  — the updated string
     :cursor — the index immediately after the inserted reference"
  [value prefix-end suffix-start name]
  (let [ref         (str "{" name "}")
        first-part  (str/slice value 0 prefix-end)
        second-part (str/slice value suffix-start)]
    {:value  (str first-part ref second-part)
     :cursor (+ (count first-part) (count ref))}))

(defn insert-ref
  "Inserts a reference `{name}` into `value` at `position`, respecting the context:

   - Outside any reference block: inserts `{name}` at the cursor position.
   - Inside an open reference block (no closing `}`): replaces from the block's
     start up to the cursor with `{name}`.
   - Inside a closed reference block (has both `{` and `}`): replaces the entire
     existing reference with `{name}`.

   Returns a map with:
     :value  — the resulting string after insertion
     :cursor — the index immediately after the inserted reference"
  [value position name]
  (cond
    (inside-ref? value position)
    (if (inside-closed-ref? value position)
      (let [open-pos  (d/nth-last-index-of (str/slice value 0 position) "{" 1)
            close-pos (+ position (d/nth-index-of (str/slice value position) "}" 1) 1)]
        (build-result value open-pos close-pos name))
      (build-result value (start-ref-position value position) position name))

    :else
    (build-result value position position name)))