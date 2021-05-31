(ns hooks.export
  (:require [clj-kondo.hooks-api :as api]))

(defn export
  [{:keys [:node]}]
  (let [[_ sname] (:children node)
        result  (api/list-node
                 [(api/token-node (symbol "def"))
                  (api/token-node (symbol (name (:value sname))))
                  sname])]
    {:node result}))
