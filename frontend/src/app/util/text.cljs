(ns app.util.text)

(defonce default-text-attrs
  {:font-id "sourcesanspro"
   :font-family "sourcesanspro"
   :font-variant-id "regular"
   :font-size "14"
   :font-weight "400"
   :font-style "normal"
   :line-height "1.2"
   :letter-spacing "0"
   :text-transform "none"
   :text-align "left"
   :text-decoration "none"})

(def typography-fields
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style
   :line-height
   :letter-spacing
   :text-transform])

(def default-typography
  (merge
   {:name "Source Sans Pro Regular"}
   (select-keys default-text-attrs typography-fields)))

(defn some-node
  [predicate node]
  (or (predicate node)
      (some #(some-node predicate %) (:children node))))

(defn map-node
  [map-fn node]
  (cond-> (map-fn node)
    (:children node) (update :children (fn [children] (mapv #(map-node map-fn %) children)))))
