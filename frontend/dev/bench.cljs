(ns bench
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gsr]
   [app.common.perf :as perf]
   [app.common.types.modifiers :as ctm]
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

(def modifiers
  (-> (ctm/empty)
      (ctm/move (gpt/point 100 200))
      (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
      (ctm/move (gpt/point -100 -200))
      (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
      (ctm/rotation (gpt/point 0 0) -100)
      (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))))

(defn bench-modifiers
  []
  (perf/benchmark
   :f #(ctm/modifiers->transform modifiers)
   :max-iterations 50000
   :samples 20
   :name "current")

  #_(perf/benchmark
   :f #(ctm/modifiers->transform-2 modifiers)
   :max-iterations 50000
   :samples 20
   :name "optimized"))

;; (ctm/modifiers->transform-2 modifiers)

(defn ^:dev/after-load after-load
  []
  #_(bench-modifiers))

(defn main
  [& [name]]
  (case name
    "points" (bench-points)
    "modifiers" (bench-modifiers)
    (println "available: points"))
  #_(.exit js/process 0))

