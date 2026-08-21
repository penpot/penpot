(ns bench.core
  "Engine-parameterized V8 bench: same body as the browser probes, node runtime.
   The engine ns is aliased at compile time via a reader switch on the classpath:
   exactly one of datascript/datahike is present per compilation."
  (:require [bench.engine :as e]))

(def n-shapes 20000)

(def rules
  '[[(child-of ?c ?p) [?c :shape/parent ?p]]
    [(descendant-of-walk ?d ?a) (child-of ?d ?a)]
    [(descendant-of-walk ?d ?a) (child-of ?d ?x) (descendant-of-walk ?x ?a)]])

(defn- median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))
(defn- timed [f] (let [t0 (js/performance.now) r (f)] [(- (js/performance.now) t0) r]))
(defn- medians [n f] (median (mapv (fn [_] (first (timed f))) (range n))))

(defn run []
  (let [component-id (random-uuid)
        file-id      (random-uuid)
        shape-ids    (into [] (map (fn [_] (random-uuid))) (range n-shapes))
        tx           (into [{:db/id -1 :shape/id (random-uuid) :shape/type :frame
                             :shape/name "root"}]
                           (map-indexed
                            (fn [i sid]
                              (cond-> {:db/id (- -2 i)
                                       :shape/id sid
                                       :shape/type :rect
                                       :shape/name (str "rect-" i)
                                       :shape/parent -1}
                                (zero? (mod i 25))
                                (assoc :shape/component-id component-id
                                       :shape/component-file file-id))))
                           shape-ids)
        schema       {:shape/id {:db/index true}
                      :shape/name {:db/index true}
                      :shape/component-id {:db/index true}
                      :shape/parent {:db/valueType :db.type/ref}}
        _            (e/build schema tx) ;; warm-up build
        build-ms     (medians 3 #(do (e/build schema tx) nil))
        db           (e/build schema tx)
        reverse-ms   (medians 40 #(e/q '[:find [?id ...]
                                         :in $ ?cid ?fid
                                         :where
                                         [?e :shape/component-id ?cid]
                                         [?e :shape/component-file ?fid]
                                         [?e :shape/id ?id]]
                                       db component-id file-id))
        name-ms      (medians 40 #(e/q '[:find ?id .
                                         :in $ ?n
                                         :where [?e :shape/name ?n] [?e :shape/id ?id]]
                                       db "rect-19999"))
        desc-ms      (medians 5 (fn []
                                  (e/q '[:find [?id ...]
                                         :in $ % ?rn
                                         :where
                                         [?root :shape/name ?rn]
                                         (descendant-of-walk ?d ?root)
                                         [?d :shape/id ?id]]
                                       db rules "root")))
        rev-rows     (count (e/q '[:find [?id ...] :in $ ?cid ?fid
                                    :where [?e :shape/component-id ?cid]
                                           [?e :shape/component-file ?fid]
                                           [?e :shape/id ?id]]
                                  db component-id file-id))
        desc-rows    (count (e/q '[:find [?id ...] :in $ % ?rn
                                    :where [?root :shape/name ?rn]
                                           (descendant-of-walk ?d ?root)
                                           [?d :shape/id ?id]]
                                  db rules "root"))
        fmt          #(.toFixed % 2)]
    (println (pr-str {:engine e/engine
                      :build-ms (fmt build-ms)
                      :reverse-ms (fmt reverse-ms)
                      :name-ms (fmt name-ms)
                      :desc-ms (fmt desc-ms)
                      :rev-rows rev-rows :desc-rows desc-rows
                      :heap-mb (fmt (/ (.-heapUsed (js/process.memoryUsage)) 1e6))}))))

(defn -main [& _] (run))
