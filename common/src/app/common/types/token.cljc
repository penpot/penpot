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
  {:boolean       "boolean"
   :border-radius "borderRadius"
   :color         "color"
   :dimensions    "dimension"
   :number        "number"
   :opacity       "opacity"
   :other         "other"
   :rotation      "rotation"
   :sizing        "sizing"
   :spacing       "spacing"
   :string        "string"
   :stroke-width  "strokeWidth"})

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

(def ^:private schema:spacing
  [:map
   [:row-gap {:optional true} token-name-ref]
   [:column-gap {:optional true} token-name-ref]
   [:p1 {:optional true} token-name-ref]
   [:p2 {:optional true} token-name-ref]
   [:p3 {:optional true} token-name-ref]
   [:p4 {:optional true} token-name-ref]
   [:m1 {:optional true} token-name-ref]
   [:m2 {:optional true} token-name-ref]
   [:m3 {:optional true} token-name-ref]
   [:m4 {:optional true} token-name-ref]
   [:x {:optional true} token-name-ref]
   [:y {:optional true} token-name-ref]])

(def spacing-keys (schema-keys schema:spacing))

(def ^:private schema:dimensions
  [:merge
   schema:sizing
   schema:spacing
   schema:stroke-width
   schema:border-radius])

(def dimensions-keys (schema-keys schema:dimensions))

(def ^:private schema:rotation
  [:map
   [:rotation {:optional true} token-name-ref]])

(def rotation-keys (schema-keys schema:rotation))

(def ^:private schema:number
  [:map
   [:rotation {:optional true} token-name-ref]
   [:line-height {:optional true} token-name-ref]])

(def number-keys (schema-keys schema:number))

(def all-keys (set/union color-keys
                         border-radius-keys
                         stroke-width-keys
                         sizing-keys
                         opacity-keys
                         spacing-keys
                         dimensions-keys
                         rotation-keys
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

     (border-radius-keys shape-attr) #{shape-attr}
     (sizing-keys shape-attr) #{shape-attr}
     (opacity-keys shape-attr) #{shape-attr}
     (spacing-keys shape-attr) #{shape-attr}
     (rotation-keys shape-attr) #{shape-attr}
     (number-keys shape-attr) #{shape-attr})))

(defn token-attr->shape-attr
  [token-attr]
  (case token-attr
    :fill :fills
    :stroke-color :strokes
    :stroke-width :strokes
    token-attr))

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

(defn maybe-apply-token-to-shape
  "When the passed `:token` is non-nil apply it to the `:applied-tokens` on a shape."
  [{:keys [shape token _attributes] :as props}]
  (if token
    (apply-token-to-shape props)
    shape))

(defn unapply-token-id [shape attributes]
  (update shape :applied-tokens d/without-keys attributes))

