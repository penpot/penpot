(ns app.main.ui.workspace.tokens.token
  (:require
   [app.common.data :as d]
   [app.main.ui.workspace.tokens.tinycolor :as tinycolor]
   [clojure.set :as set]
   [cuerdas.core :as str]))

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

(defn token-identifier [{:keys [name] :as _token}]
  name)

(defn attributes-map
  "Creats an attributes map using collection of `attributes` for `id`."
  [attributes token]
  (->> (map (fn [attr] [attr (token-identifier token)]) attributes)
       (into {})))

(defn remove-attributes-for-token
  "Removes applied tokens with `token-id` for the given `attributes` set from `applied-tokens`."
  [attributes token applied-tokens]
  (let [attr? (set attributes)]
    (->> (remove (fn [[k v]]
                   (and (attr? k)
                        (= v (token-identifier token))))
                 applied-tokens)
         (into {}))))

(defn token-attribute-applied?
  "Test if `token` is applied to a `shape` on single `token-attribute`."
  [token shape token-attribute]
  (when-let [id (get-in shape [:applied-tokens token-attribute])]
    (= (token-identifier token) id)))

(defn token-applied?
  "Test if `token` is applied to a `shape` with at least one of the one of the given `token-attributes`."
  [token shape token-attributes]
  (some #(token-attribute-applied? token shape %) token-attributes))

(defn shapes-token-applied?
  "Test if `token` is applied to to any of `shapes` with at least one of the one of the given `token-attributes`."
  [token shapes token-attributes]
  (some #(token-applied? token % token-attributes) shapes))

(defn shapes-ids-by-applied-attributes [token shapes token-attributes]
  (reduce (fn [acc shape]
            (let [applied-ids-by-attribute (->> (map #(when (token-attribute-applied? token shape %)
                                                        [% #{(:id shape)}])
                                                     token-attributes)
                                                (filter some?)
                                                (into {}))]
              (merge-with into acc applied-ids-by-attribute)))
          {} shapes))

(defn shapes-applied-all? [ids-by-attributes shape-ids attributes]
  (every? #(set/superset? (get ids-by-attributes %) shape-ids) attributes))

(defn token-name->path
  "Splits token-name into a path vector split by `.` characters.

  Will concatenate multiple `.` characters into one."
  [token-name]
  (str/split token-name #"\.+"))

(defn token-name->path-selector
  "Splits token-name into map with `:path` and `:selector` using `token-name->path`.

  `:selector` is the last item of the names path
  `:path` is everything leading up the the `:selector`."
  [token-name]
  (let [path-segments (token-name->path token-name)
        last-idx (dec (count path-segments))
        [path [selector]] (split-at last-idx path-segments)]
    {:path (seq path)
     :selector selector}))

(defn token-names-tree-id-map [tokens]
  (reduce
   (fn [acc [_ {:keys [name] :as token}]]
     (when (string? name)
       (let [temp-id (random-uuid)
             token (assoc token :temp/id temp-id)]
         (-> acc
             (assoc-in (concat [:tree] (token-name->path name)) token)
             (assoc-in [:ids-map temp-id] token)))))
   {:tree {}
    :ids-map {}}
   tokens))

(defn token-name-path-exists?
  "Traverses the path from `token-name` down a `token-tree` and checks if a token at that path exists.

  It's not allowed to create a token inside a token. E.g.:
  Creating a token with

    {:name \"foo.bar\"}

  in the tokens tree:

    {\"foo\" {:name \"other\"}}"
  [token-name token-names-tree]
  (let [{:keys [path selector]} (token-name->path-selector token-name)
        path-target (reduce
                     (fn [acc cur]
                       (let [target (get acc cur)]
                         (cond
                           ;; Path segment doesn't exist yet
                           (nil? target) (reduced false)
                           ;; A token exists at this path
                           (:name target) (reduced true)
                           ;; Continue traversing the true
                           :else target)))
                     token-names-tree path)]
    (cond
      (boolean? path-target) path-target
      (get path-target :name) true
      :else (-> (get path-target selector)
                (seq)
                (boolean)))))

(defn color-token? [token]
  (= (:type token) :color))

(defn resolved-value-hex [{:keys [resolved-value] :as token}]
  (when (and resolved-value (color-token? token))
    (some->> (tinycolor/valid-color resolved-value)
             (tinycolor/->hex)
             (str "#"))))
