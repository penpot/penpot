(ns app.main.data.workspace.tokens.prepare-value
  (:require
   [cuerdas.core :as str]))

(defn resolve-value-preview [token base-size]
  (when-let [resolved-value (:resolved-value token)]
    (cond
      (= (:unit token) "rem") (str/format "%srem (%spx)" resolved-value (* resolved-value base-size))
      (= (:unit token) "px") (str/format "%spx" resolved-value)
      :else resolved-value)))

(defn resolved-value-for-shape-update
  "Extract value from a resolved token for shape update operation.
  Transform value so it works for penpot shapes."
  [token base-size]
  (when-let [resolved-value (:resolved-value token)]
    (cond
      (= (:unit token) "rem") (* resolved-value base-size)
      :else resolved-value)))
