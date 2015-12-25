(ns uxbox.util.data
  "A collection of data transformation utils.")

(defn index-by
  "Return a indexed map of the collection
  keyed by the result of executing the getter
  over each element of the collection."
  [coll getter]
  (let [data (transient {})]
    (run! #(assoc! data (getter %) %) coll)
    (persistent! data)))

(def ^:static index-by-id #(index-by % :id))
