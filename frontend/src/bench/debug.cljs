(ns bench.debug
  (:require [datascript.core :as d]))
(defn -main [& _]
  (let [db (d/db-with (d/empty-db {:x/id {:db/index true}})
                      [{:db/id -1 :x/id 7 :x/name "a"}])]
    (println :scan-q    (pr-str (d/q '[:find ?e ?v :where [?e :x/name ?v]] db)))
    (println :bound-q   (pr-str (d/q '[:find ?e :in $ ?n :where [?e :x/name ?n]] db "a")))
    (println :avet-part (pr-str (vec (d/datoms db :avet :x/name "a"))))
    (println :eavt-part (pr-str (vec (d/datoms db :eavt 1 :x/name))))
    (println :seek      (pr-str (first (d/seek-datoms db :avet :x/name))))))
