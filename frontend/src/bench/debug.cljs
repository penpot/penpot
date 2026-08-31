(ns bench.debug
  "The `:advanced` gate for the engine that carries the worker overlay.

  `datascript.query/lookup-pattern-db` labels a datom's slots with the
  string property names \"e\", \"a\", \"v\" and \"tx\", and the query
  engine reads them back with a raw property access
  (`datascript.arrays/aget`). Closure's `:advanced` property renaming
  moves those four fields and lets an unrelated one answer to the old
  name, so the read succeeds and returns the wrong slot: a full-scan
  query hands back a protocol-mask integer where a string belongs, and a
  bound-value query matches nothing and returns the empty set. The index
  reads the same datoms through real field access
  (`datascript.db/val-at-datom`) and stays correct, which is what makes
  the corruption silent.

  Datascript ships the externs that pin those property names,
  `datascript/externs.js`, and declares them in its `deps.cljs`.
  Shadow-cljs collects that declaration into `:deps-externs` and never
  populates or reads it, so every build that ships the engine names the
  externs file in its own `:compiler-options`.

  Run this against the release toolchain, never against dev, because dev
  renames nothing and answers correctly either way:

      clojure -M:dev:shadow-cljs release ds-probe && node target/ds-probe.js

  Exits non-zero and names every reading that came back wrong."
  (:require [datascript.core :as d]))

(defn- db
  "One entity, two datoms, `:x/name` indexed so the `:avet` read is legal."
  []
  (d/db-with (d/empty-db {:x/name {:db/index true}})
             [{:db/id -1 :x/id 7 :x/name "a"}]))

(defn- readings
  "Six readings paired with the answer each must give. Four of them bind
  one datom slot apiece, because which slot a build corrupts is a fact
  about that build's whole property set and not about datascript. The
  fifth reads a slot across a join, which is the shape a real query has.
  The last one reads the same datoms through the index, so it stays
  correct in a corrupted build and tells a reader storage was never the
  problem; it is also the oracle for the transaction id, which no
  literal can name."
  [db]
  (let [datom (first (d/datoms db :eavt 1 :x/name))]
    [[:query-value     (d/q '[:find ?v :where [?e :x/name ?v]] db) #{["a"]}]
     [:query-entity    (d/q '[:find ?e :where [?e :x/name "a"]] db) #{[1]}]
     [:query-attribute (d/q '[:find ?a :where [1 ?a "a"]] db) #{[:x/name]}]
     [:query-tx        (d/q '[:find ?tx :where [?e :x/name _ ?tx]] db) #{[(:tx datom)]}]
     [:query-join      (d/q '[:find ?e :in $ ?n :where [?e :x/name ?n]] db "a") #{[1]}]
     [:index-control   (mapv (juxt :e :a :v) (d/datoms db :avet :x/name "a"))
      [[1 :x/name "a"]]]]))

(defn -main [& _]
  (let [rs (readings (db))
        wrong (into [] (comp (remove (fn [[_ answer expected]] (= expected answer)))
                             (map first))
                    rs)]
    (doseq [[label answer expected] rs]
      (println label (pr-str answer)
               (if (= expected answer)
                 "ok"
                 (str "WRONG, expected " (pr-str expected)))))
    (if (seq wrong)
      (do (println "FAIL" (pr-str wrong)
                   "— this build renamed the properties the query layer reads by name;"
                   "add :externs [\"datascript/externs.js\"] to its :compiler-options")
          (js/process.exit 1))
      (println "PASS — the query layer survives this build's optimizations"))))
