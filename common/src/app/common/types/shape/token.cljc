(ns app.common.types.shape.token)

(defn font-weight-applied?
  [shape]
  (or
   (get-in shape [:applied-tokens :font-weight])
   (get-in shape [:applied-tokens :typography :font-weight])))
