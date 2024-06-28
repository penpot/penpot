(ns app.main.ui.workspace.tokens.token
  (:require
   [cuerdas.core :as str]))

(defn token-name->path
  "Splits token-name into a path vector split by `.` characters.

  Will concatenate multiple `.` characters into one."
  [token-name]
  (str/split token-name #"\.+"))

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
  (let [name-path (token-name->path token-name)
        result (reduce
                (fn [acc cur]
                  (let [target (get acc cur)]
                    (cond
                      ;; Path segment doesn't exist yet
                      (nil? target) (reduced false)
                      ;; A token exists at this path
                      (:name target) (reduced true)
                      ;; Continue traversing the true
                      :else target)))
                token-names-tree name-path)]
    (if (map? result)
      (some? (:name result))
      result)))
