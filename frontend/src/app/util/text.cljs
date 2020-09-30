(ns app.util.text)

(defn some-node
  [predicate node]
  (or (predicate node)
      (some #(some-node predicate %) (:children node))))

(defn map-node
  [map-fn node]
  (cond-> (map-fn node)
    (:children node) (update :children (fn [children] (mapv #(map-node map-fn %) children)))))
