;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.token
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [clojure.data :as data]
   [clojure.set :as set]
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
  {:boolean        "boolean"
   :border-radius  "borderRadius"
   :color          "color"
   :dimensions     "dimension"
   :font-size      "fontSizes"
   :letter-spacing "letterSpacing"
   :number         "number"
   :opacity        "opacity"
   :other          "other"
   :rotation       "rotation"
   :sizing         "sizing"
   :spacing        "spacing"
   :string         "string"
   :stroke-width   "strokeWidth"})

(def dtcg-token-type->token-type
  (set/map-invert token-type->dtcg-token-type))

(def token-types
  (into #{} (keys token-type->dtcg-token-type)))

(def token-name-ref
  [:and :string [:re #"^(?!\$)([a-zA-Z0-9-$_]+\.?)*(?<!\.)$"]])

(def ^:private schema:color
  [:map
   [:fill {:optional true} token-name-ref]
   [:stroke-color {:optional true} token-name-ref]])

(def color-keys (schema-keys schema:color))

(def ^:private schema:border-radius
  [:map
   [:r1 {:optional true} token-name-ref]
   [:r2 {:optional true} token-name-ref]
   [:r3 {:optional true} token-name-ref]
   [:r4 {:optional true} token-name-ref]])

(def border-radius-keys (schema-keys schema:border-radius))

(def ^:private schema:stroke-width
  [:map
   [:stroke-width {:optional true} token-name-ref]])

(def stroke-width-keys (schema-keys schema:stroke-width))

(def ^:private schema:sizing
  [:map
   [:width {:optional true} token-name-ref]
   [:height {:optional true} token-name-ref]
   [:layout-item-min-w {:optional true} token-name-ref]
   [:layout-item-max-w {:optional true} token-name-ref]
   [:layout-item-min-h {:optional true} token-name-ref]
   [:layout-item-max-h {:optional true} token-name-ref]])

(def sizing-keys (schema-keys schema:sizing))

(def ^:private schema:opacity
  [:map
   [:opacity {:optional true} token-name-ref]])

(def opacity-keys (schema-keys schema:opacity))

(def ^:private schema:spacing-gap
  [:map
   [:row-gap {:optional true} token-name-ref]
   [:column-gap {:optional true} token-name-ref]])

(def ^:private schema:spacing-padding
  [:map
   [:p1 {:optional true} token-name-ref]
   [:p2 {:optional true} token-name-ref]
   [:p3 {:optional true} token-name-ref]
   [:p4 {:optional true} token-name-ref]])

(def ^:private schema:spacing-margin
  [:map
   [:m1 {:optional true} token-name-ref]
   [:m2 {:optional true} token-name-ref]
   [:m3 {:optional true} token-name-ref]
   [:m4 {:optional true} token-name-ref]])

(def ^:private schema:spacing
  (reduce mu/union [schema:spacing-gap
                    schema:spacing-padding
                    schema:spacing-margin]))

(def spacing-margin-keys (schema-keys schema:spacing-margin))

(def spacing-keys (schema-keys schema:spacing))

(def ^:private schema:dimensions
  (reduce mu/union [schema:sizing
                    schema:spacing
                    schema:stroke-width
                    schema:border-radius]))

(def dimensions-keys (schema-keys schema:dimensions))

(def ^:private schema:axis
  [:map
   [:x {:optional true} token-name-ref]
   [:y {:optional true} token-name-ref]])

(def axis-keys (schema-keys schema:axis))



(def ^:private schema:rotation
  [:map
   [:rotation {:optional true} token-name-ref]])

(def rotation-keys (schema-keys schema:rotation))

(def ^:private schema:font-size
  [:map
   [:font-size {:optional true} token-name-ref]])

(def font-size-keys (schema-keys schema:font-size))

(def ^:private schema:letter-spacing
  [:map
   [:letter-spacing {:optional true} token-name-ref]])

(def letter-spacing-keys (schema-keys schema:letter-spacing))

(def typography-keys (set/union font-size-keys letter-spacing-keys))

;; TODO: Created to extract the font-size feature from the typography feature flag.
;; Delete this once the typography feature flag is removed.
(def ff-typography-keys (set/difference typography-keys font-size-keys))

(def ^:private schema:number
  (reduce mu/union [[:map [:line-height {:optional true} token-name-ref]]
                    schema:rotation]))

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
                         number-keys))

(def ^:private schema:tokens
  [:map {:title "Applied Tokens"}])

(def schema:applied-tokens
  [:merge
   schema:tokens
   schema:border-radius
   schema:sizing
   schema:spacing
   schema:rotation
   schema:number
   schema:font-size
   schema:letter-spacing
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

     (font-size-keys shape-attr) #{shape-attr}
     (letter-spacing-keys shape-attr) #{shape-attr}
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

(def frame-attributes
  (set/union rect-attributes
             spacing-keys))

(def text-attributes
  (set/union generic-attributes
             typography-keys
             number-keys))

(defn shape-type->attributes
  [type]
  (case type
    :bool    generic-attributes
    :circle  generic-attributes
    :rect    rect-attributes
    :frame   frame-attributes
    :image   rect-attributes
    :path    generic-attributes
    :svg-raw generic-attributes
    :text    text-attributes
    nil))

(defn appliable-attrs
  "Returns intersection of shape `attributes` for `token-type`."
  [attributes token-type]
  (set/intersection attributes (shape-type->attributes token-type)))

(defn any-appliable-attr?
  "Checks if `token-type` supports given shape `attributes`."
  [attributes token-type]
  (seq (appliable-attrs attributes token-type)))

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
