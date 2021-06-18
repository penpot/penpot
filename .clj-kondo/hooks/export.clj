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

(defn potok-reify
  [{:keys [:node]}]
  (let [[rnode rtype & other] (:children node)
        result  (api/list-node
                 (into [(api/token-node (symbol "deftype"))
                        (api/token-node (gensym (name (:k rtype))))
                        (api/vector-node [])]
                       other))]
    {:node result}))

(defn clojure-specify
  [{:keys [:node]}]
  (let [[rnode rtype & other] (:children node)
        result  (api/list-node
                 (into [(api/token-node (symbol "extend-type"))
                        (api/token-node (gensym (:string-value rtype)))]
                       other))]
    {:node result}))


(defn service-defmethod
  [{:keys [:node]}]
  (let [[rnode rtype & other] (:children node)
        rsym    (gensym (name (:k rtype)))
        result  (api/list-node
                 [(api/token-node (symbol "do"))
                  (api/list-node
                   [(api/token-node (symbol "declare"))
                    (api/token-node rsym)])
                  (api/list-node
                   (into [(api/token-node (symbol "defmethod"))
                          (api/token-node rsym)
                          rtype]
                         other))])]
    {:node result}))


