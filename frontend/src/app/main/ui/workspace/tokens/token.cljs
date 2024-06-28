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
