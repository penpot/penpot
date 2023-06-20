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

(def registry (atom {}))

(defn potok-reify
  [{:keys [:node :filename] :as params}]
  (let [[rnode rtype & other] (:children node)
        rsym (symbol (str "event-type-" (name (:k rtype))))
        reg  (get @registry filename #{})]
    (when-not (:namespaced? rtype)
      (let [{:keys [:row :col]} (meta rtype)]
        (api/reg-finding! {:message "ptk/reify type should be namespaced"
                           :type :potok/reify-type
                           :row row
                           :col col})))

    (if (contains? reg rsym)
      (let [{:keys [:row :col]} (meta rtype)]
        (api/reg-finding! {:message (str "duplicate type: " (name (:k rtype)))
                           :type :potok/reify-type
                           :row row
                           :col col}))
      (swap! registry update filename (fnil conj #{}) rsym))

    (let [result  (api/list-node
                   (into [(api/token-node (symbol "deftype"))
                          (api/token-node rsym)
                          (api/vector-node [])]
                         other))]
      {:node result})))


(defn penpot-defrecord
  [{:keys [:node]}]
  (let [[rnode rtype rparams & other] (:children node)

        nodes [(api/token-node (symbol "do"))
               (api/list-node
                (into [(api/token-node (symbol (name (:value rnode)))) rtype rparams] other))
               (api/list-node
                [(api/token-node (symbol "defn"))
                 (api/token-node (symbol (str "pos->" (:string-value rtype))))
                 (api/vector-node
                  (->> (:children rparams)
                       (mapv (fn [t]
                               (api/token-node (symbol (str "_" (:string-value t))))))))
                 (api/token-node nil)])]

        result (api/list-node nodes)]

    ;; (prn "=====>" (into {} rparams))
    ;; (prn (api/sexpr result))
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
  (let [[rnode rtype ?meta & other] (:children node)
        rsym    (gensym (name (:k rtype)))

        [?docs other] (if (api/string-node? ?meta)
                        [?meta other]
                        [nil (cons ?meta other)])

        [?meta other] (let [?meta (first other)]
                        (if (api/map-node? ?meta)
                          [?meta (rest other)]
                          [nil   other]))

        nodes         [(api/token-node (symbol "do"))
                       (api/list-node
                        [(api/token-node (symbol "declare"))
                         (api/token-node rsym)])

                       (when ?docs
                         (api/list-node
                          [(api/token-node (symbol "comment")) ?docs]))

                       (when ?meta
                         (api/list-node
                          [(api/token-node (symbol "reset-meta!"))
                           (api/token-node rsym)
                           ?meta]))
                       (api/list-node
                        (into [(api/token-node (symbol "defmethod"))
                               (api/token-node rsym)
                               rtype]
                              other))]
        result  (api/list-node (filterv some? nodes))]

    ;; (prn "=====>" rtype)
    ;; (prn (api/sexpr result))
    {:node result}))
