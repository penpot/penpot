(ns app.main.ui.workspace.tokens.token
  (:require
   [cuerdas.core :as str]))

(defn token-applied?
  "Test if `token` is applied to a `shape` with the given `token-attributes`."
  [token shape token-attributes]
  (let [{:keys [id]} token
        applied-tokens (get shape :applied-tokens {})]
    (some (fn [attr]
            (= (get applied-tokens attr) id))
          token-attributes)))

(defn shapes-token-applied?
  "Test if `token` is applied to to any of `shapes` with the given `token-attributes`."
  [token shapes token-attributes]
  (some #(token-applied? token % token-attributes) shapes))

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

(defn token-names-map
  "Convert tokens into a map with their `:name` as the key.

  E.g.: {\"sm\" {:token-type :border-radius :id #uuid \"000\" ...}}"
  [tokens]
  (->> (map (fn [{:keys [name] :as token}] [name token]) tokens)
       (into {})))

(defn token-names-tree
  "Convert tokens into a nested tree with their `:name` as the path."
  [tokens]
  (reduce
   (fn [acc [_ {:keys [name] :as token}]]
     (when (string? name)
       (let [path (token-name->path name)]
         (assoc-in acc path token))))
   {} tokens))

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
