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
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.cursors :as cur]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [malli.core :as m]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HIGH LEVEL SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Token value

(defn- token-value-empty-fn
  [{:keys [value]}]
  (when (or (str/empty? value)
            (str/blank? value))
    (tr "workspace.tokens.empty-input")))

(def schema:token-value-generic
  [::sm/text {:error/fn token-value-empty-fn}])

(def schema:token-value-numeric
  [:and
   [::sm/text {:error/fn token-value-empty-fn}]
   [:fn {:error/fn #(tr "workspace.tokens.invalid-value" (:value %))}
    (fn [value]
      (if (str/numeric? value)
        (let [n (d/parse-double value)]
          (some? n))
        true))]])  ;; Leave references or formulas to be checked by the resolver

(def schema:token-value-percent
  [:and
   [::sm/text {:error/fn token-value-empty-fn}]
   [:fn {:error/fn #(tr "workspace.tokens.value-with-percent" (:value %))}
    (fn [value]
      (if (d/percent? value)
        (let [v (d/parse-percent value)]
          (some? v))
        true))]])  ;; Leave references or formulas to be checked by the resolver

(def schema:token-value-composite-ref
  [::sm/text {:error/fn token-value-empty-fn}])

(def schema:token-value-opacity
  [:and
   [::sm/text {:error/fn token-value-empty-fn}]
   [:fn {:error/fn #(tr "workspace.tokens.opacity-range")}
    (fn [opacity]
      (if (str/numeric? opacity)
        (let [n (d/parse-percent opacity)]
          (and (some? n) (<= 0 n 1)))
        true))]])  ;; Leave references or formulas to be checked by the resolver

(def schema:token-value-font-family
  [:or
   [:vector ::sm/text]
   cto/schema:token-ref])

(def schema:token-value-font-weight
  [:or
   [:fn {:error/fn #(tr "workspace.tokens.invalid-font-weight-token-value")}
    cto/valid-font-weight-variant]
   ::sm/text])  ;; Leave references or formulas to be checked by the resolver

(def schema:token-value-typography-map
  [:map
   [:font-family {:optional true} schema:token-value-font-family]
   [:font-size {:optional true} schema:token-value-numeric]
   [:font-weight {:optional true} schema:token-value-font-weight]
   [:line-height {:optional true} schema:token-value-percent]
   [:letter-spacing {:optional true} schema:token-value-generic]
   [:paragraph-spacing {:optional true} schema:token-value-generic]
   [:text-decoration {:optional true} schema:token-value-generic]
   [:text-case {:optional true} schema:token-value-generic]])

(def schema:token-value-typography
  [:or
   schema:token-value-typography-map
   schema:token-value-composite-ref])

(def schema:token-value-shadow-vector
  [:vector
   [:map
    [:offset-x :string]
    [:offset-y :string]
    [:blur
     [:and
      :string
      [:fn {:error/fn #(tr "workspace.tokens.shadow-token-blur-value-error")}
       (fn [blur]
         (let [n (d/parse-double blur)]
           (or (nil? n) (not (< n 0)))))]]]
    [:spread
     [:and
      :string
      [:fn {:error/fn #(tr "workspace.tokens.shadow-token-spread-value-error")}
       (fn [spread]
         (let [n (d/parse-double spread)]
           (or (nil? n) (not (< n 0)))))]]]
    [:color :string]
    [:inset {:optional true} :boolean]]])

(def schema:token-value-shadow
  [:or
   schema:token-value-shadow-vector
   schema:token-value-composite-ref])

(defn make-token-value-schema
  [token-type]
  [:multi {:dispatch (constantly token-type)
           :title "Token Value"}
   [:opacity schema:token-value-opacity]
   [:font-family schema:token-value-font-family]
   [:font-size schema:token-value-numeric]
   [:font-weight schema:token-value-font-weight]
   [:typography schema:token-value-typography]
   [:shadow schema:token-value-shadow]
   [::m/default schema:token-value-generic]])

;; Token

(defn make-token-name-schema
  "Dynamically generates a schema to check a token name, adding translated error messages
   and two additional validations:
    - Min and max length.
    - Checks if other token with a path derived from the name already exists at `tokens-tree`.
      e.g. it's not allowed to create a token `foo.bar` if a token `foo` already exists."
  [tokens-tree]
  [:and
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   (-> cto/schema:token-name
       (sm/update-properties assoc :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error"))))
   [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
    #(and (some? tokens-tree)
          (not (ctob/token-name-path-exists? % tokens-tree)))]])

(defn update-tokens-path
  "Updates the path in active-tokens for tokens in the same directory.
   - Filters tokens whose path matches the current path prefix
   - Replaces the token name with the new name
   - Updates the :path value in the token object"
  [active-tokens current-path current-name new-name]
  (let [path-prefix (str/replace current-path current-name "")]
    (mapv (fn [[token-path token-obj]]
            (if (str/starts-with? token-path path-prefix)
              (let [new-token-path (str/replace token-path current-name new-name)]
                [new-token-path (assoc token-obj :name new-token-path)])
              [token-path token-obj]))
          active-tokens)))

(defn make-node-token-name-schema
  "Dynamically generates a schema to check a token nodename, adding translated error messages
   and two additional validations:
    - Min and max length.
    - Checks if other token with a path derived from the name already exists at `tokens-tree`.
      e.g. it's not allowed to create a token `foo.bar` if a token `foo` already exists."
  [active-tokens tokens-tree node]
  [:and
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   (-> cto/schema:token-node-name
       (sm/update-properties assoc :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error"))))
   [:fn {:error/fn #(tr "workspace.tokens.duplicated paths" (:value %))}
    (fn [name]
      (pp/pprint {:name name  :path (:path node) :tokens active-tokens})
      (let [current-path (:path node)
            current-name (:name node)
            new-tokens (update-tokens-path active-tokens current-path current-name name)
            _ (pp/pprint {:new-tokens new-tokens})]
        (and (some? new-tokens)
             (some #(not (ctob/token-name-path-exists? (first %) tokens-tree)) new-tokens))))]])

(def schema:token-description
  [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}])

(defn make-token-schema
  [tokens-tree token-type]
  [:and
   (sm/merge
    cto/schema:token-attrs
    [:map
     [:name (make-token-name-schema tokens-tree)]
     [:value (make-token-value-schema token-type)]
     [:description {:optional true} schema:token-description]])
   [:fn {:error/field :value
         :error/fn #(tr "workspace.tokens.self-reference")}
    (fn [{:keys [name value]}]
      (when (and name value)
        (not (cto/token-value-self-reference? name value))))]])

(defn make-node-token-schema
  [active-tokens tokens-tree node]
  [:map
   [:name (make-node-token-name-schema active-tokens tokens-tree node)]])

(defn convert-dtcg-token
  "Convert token attributes as they come from a decoded json, with DTCG types, to internal types.
   Eg. From this:

     {'name' 'body-text'
      'type' 'typography'
      'value' {
        'fontFamilies' ['Arial' 'Helvetica' 'sans-serif']
        'fontSize' '16px'
        'fontWeights' 'normal'}}

   to this
     {:name 'body-text'
      :type :typography
      :value {
        :font-family ['Arial' 'Helvetica' 'sans-serif']
        :font-size '16px'
        :font-weight 'normal'}}"
  [token-attrs]
  (let [name        (get token-attrs "name")
        type        (get token-attrs "type")
        value       (get token-attrs "value")
        description (get token-attrs "description")

        type  (cto/dtcg-token-type->token-type type)
        value (case type
                :font-family (ctob/convert-dtcg-font-family value)
                :typography  (ctob/convert-dtcg-typography-composite value)
                :shadow      (ctob/convert-dtcg-shadow-composite value)
                value)]

    (d/without-nils {:name name
                     :type type
                     :value value
                     :description description})))

;; Token set

(defn make-token-set-name-schema
  "Generates a dynamic schema to check a token set name:
    - Validate name length.
    - Checks if other token set with a path derived from the name already exists in the tokens lib."
  [tokens-lib set-id]
  [:and
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   [:fn {:error/fn #(tr "errors.token-set-already-exists")}
    (fn [name]
      (or (nil? tokens-lib)
          (let [set (ctob/get-set-by-name tokens-lib name)]
            (or (nil? set) (= (ctob/get-id set) set-id)))))]])

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
   [:fn {:error/fn #(tr "errors.token-theme-already-exists")}
    (fn [group]
      (or (nil? tokens-lib)
          (let [theme (ctob/get-theme-by-name tokens-lib group name)]
            (or (nil? theme) (= (:id theme) theme-id)))))]])

(defn make-token-theme-name-schema
  "Generates a dynamic schema to check a token theme name:
    - Validate name length.
    - Checks if other token theme with the same name already exists in the same group in the tokens lib."
  [tokens-lib group theme-id]
  [:and
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   [:fn {:error/fn #(tr "errors.token-theme-already-exists" (str group "/" (:value %)))}
    (fn [name]
      (or (nil? tokens-lib)
          (let [theme (ctob/get-theme-by-name tokens-lib group name)]
            (or (nil? theme) (= (:id theme) theme-id)))))]])

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
