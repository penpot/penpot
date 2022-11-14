(ns bench
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gsr]
   [app.common.perf :as perf]
   [clojure.spec.alpha :as s]
   [clojure.test.check.generators :as gen]))

(def points
  (gen/sample (s/gen ::gpt/point) 20))

(defn points->rect
  [points]
  (when-let [points (seq points)]
    (loop [minx ##Inf
           miny ##Inf
           maxx ##-Inf
           maxy ##-Inf
           pts  points]
      (if-let [pt ^boolean (first pts)]
        (let [x (d/get-prop pt :x)
              y (d/get-prop pt :y)]
          (recur (min minx x)
                 (min miny y)
                 (max maxx x)
                 (max maxy y)
                 (rest pts)))
        (when (d/num? minx miny maxx maxy)
          (gsr/make-rect minx miny (- maxx minx) (- maxy miny)))))))

(defn bench-points
  []
  (perf/benchmark
   :f #(gsr/points->rect points)
   :name "base")
  (perf/benchmark
   :f #(points->rect points)
   :name "optimized"))


(defn main
  [& [name]]
  (case name
    "points" (bench-points)
    (println "available: points")))


