;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.tokens
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.i18n :refer [tr]]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HIGH LEVEL SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Token

(defn make-token-name-schema
  "Dynamically generates a schema to check a token name, adding translated error messages
   and two additional validations:
    - Min and max length.
    - Checks if other token with a path derived from the name already exists at `tokens-tree`.
      e.g. it's not allowed to create a token `foo.bar` if a token `foo` already exists."
  [tokens-tree]
  [:and
   (-> cto/schema:token-name
       (sm/update-properties assoc :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error"))))
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
    #(not (ctob/token-name-path-exists? % tokens-tree))]])

(def schema:token-description
  [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}])

(defn make-token-schema
  [tokens-tree]
  (sm/merge
   cto/schema:token-attrs
   [:map
    [:name (make-token-name-schema tokens-tree)]
    [:description {:optional true} schema:token-description]]))

;; Token set

(defn make-token-set-name-schema
  "Generates a dynamic schema to check a token set name:
    - Validate name length.
    - Checks if other token set with a path derived from the name already exists in the tokens lib."
  [tokens-lib set-id]
  [:and
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   [:fn {:error/fn #(tr "errors.token-set-already-exists" (:value %))}
    (fn [name]
      (let [set (ctob/get-set-by-name tokens-lib name)]
        (or (nil? set) (= (ctob/get-id set) set-id))))]])

(def schema:token-set-description
  [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}])

(defn make-token-set-schema
  [tokens-lib set-id]
  (sm/merge
   ctob/schema:token-set-attrs
   [:map
    [:name [:and (make-token-set-name-schema tokens-lib set-id)
            [:fn #(ctob/normalized-set-name? %)]]]
    [:description {:optional true} schema:token-set-description]]))

;; Token theme

(defn make-token-theme-group-schema
  "Generates a dynamic schema to check a token theme group:
    - Validate group length.
    - Checks if other token theme with the same name already exists in the new group in the tokens lib."
  [tokens-lib name theme-id]
  [:and
   [:string {:min 0 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   [:fn {:error/fn #(tr "errors.token-theme-already-exists" (:value %))}
    (fn [group]
      (let [theme (ctob/get-theme-by-name tokens-lib group name)]
        (or (nil? theme) (= (:id theme) theme-id))))]])

(defn make-token-theme-name-schema
  "Generates a dynamic schema to check a token theme name:
    - Validate name length.
    - Checks if other token theme with the same name already exists in the same group in the tokens lib."
  [tokens-lib group theme-id]
  [:and
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   [:fn {:error/fn #(tr "errors.token-theme-already-exists" (str group "/" (:value %)))}
    (fn [name]
      (let [theme (ctob/get-theme-by-name tokens-lib group name)]
        (or (nil? theme) (= (:id theme) theme-id))))]])

(def schema:token-theme-description
  [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}])

(defn make-token-theme-schema
  [tokens-lib group name theme-id]
  (sm/merge
   ctob/schema:token-theme-attrs
   [:map
    [:group (make-token-theme-group-schema tokens-lib name theme-id)] ;; TODO how to keep error-fn from here?
    [:name (make-token-theme-name-schema tokens-lib group theme-id)]
    [:description {:optional true} schema:token-theme-description]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def parseable-token-value-regexp
  "Regexp that can be used to parse a number value out of resolved token value.
  This regexp also trims whitespace around the value."
  #"^\s*(-?[0-9]+\.?[0-9]*)(px|%)?\s*$")

(defn parse-token-value
  "Parses a resolved value and separates the unit from the value.
  Returns a map of {:value `number` :unit `string`}."
  [value]
  (cond
    (number? value) {:value value}
    (string? value) (when-let [[_ value unit] (re-find parseable-token-value-regexp value)]
                      (when-let [parsed-value (d/parse-double value)]
                        {:value parsed-value
                         :unit unit}))))

;; FIXME: looks very redundant function
(defn token-identifier
  [{:keys [name] :as _token}]
  name)

(defn attributes-map
  "Creats an attributes map using collection of `attributes` for `id`."
  [attributes token]
  (->> (map (fn [attr] [attr (token-identifier token)]) attributes)
       (into {})))

(defn remove-attributes-for-token
  "Removes applied tokens with `token-name` for the given `attributes` set from `applied-tokens`."
  [attributes token-name applied-tokens]
  (let [attr? (set attributes)]
    (->> (remove (fn [[k v]]
                   (and (attr? k)
                        (= v token-name)))
                 applied-tokens)
         (into {}))))

(defn token-attribute-applied?
  "Test if `token` is applied to a `shape` on single `token-attribute`."
  [token shape token-attribute]
  (when-let [id (dm/get-in shape [:applied-tokens token-attribute])]
    (= (token-identifier token) id)))

(defn token-applied?
  "Test if `token` is applied to a `shape` with at least one of the given `token-attributes`."
  [token shape token-attributes]
  (some #(token-attribute-applied? token shape %) token-attributes))

(defn shapes-token-applied?
  "Test if `token` is applied to to any of `shapes` with at least one of the given `token-attributes`."
  [token shapes token-attributes]
  (some #(token-applied? token % token-attributes) shapes))

(defn shapes-ids-by-applied-attributes
  [token shapes token-attributes]
  (let [conj* (fnil conj #{})]
    (reduce (fn [result shape]
              (let [shape-id (dm/get-prop shape :id)]
                (->> token-attributes
                     (filter #(token-attribute-applied? token shape %))
                     (reduce (fn [result attr]
                               (update result attr conj* shape-id))
                             result))))
            {}
            shapes)))

(defn shapes-applied-all? [ids-by-attributes shape-ids attributes]
  (every? #(set/superset? (get ids-by-attributes %) shape-ids) attributes))

(defn color-token? [token]
  (= (:type token) :color))

;; FIXME: this should be precalculated ?
(defn is-reference? [token]
  (str/includes? (:value token) "{"))
