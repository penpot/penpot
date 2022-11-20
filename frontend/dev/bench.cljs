(ns bench
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gsr]
   [app.common.perf :as perf]
   [clojure.spec.alpha :as s]
   [clojure.test.check.generators :as gen]))

(def points
  (gen/sample (s/gen ::gpt/point) 20))

(defn bench-points
  []
  #_(perf/benchmark
     :f #(gpt/center-points-old points)
     :samples 20
     :max-iterations 500000
     :name "base")
  (perf/benchmark
   :f #(gpt/center-points points)
   :max-iterations 500000
   :samples 20
   :name "optimized"))

(defn main
  [& [name]]
  (case name
    "points" (bench-points)
    (println "available: points")))
