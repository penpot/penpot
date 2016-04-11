(ns bench.core
  (:require [kdtree.core :as k]
            [cljs.pprint :refer (pprint)]
            [cljs.nodejs :as node]))

(enable-console-print!)

;; --- Helpers

(defn generate-points
  ([n] (generate-points n 1))
  ([n step]
   (into-array
    (for [x (range 0 n step)
          y (range 0 n step)]
      #js [x y]))))

;; --- Index Initialization Bechmark

(defn- bench-init-10000
  []
  (js/console.time "init:10000")
  (let [points (generate-points 1000 10)]
    (println (count points))
    (k/create2d points)
    (js/console.timeEnd "init:10000")))

(defn- bench-init-250000
  []
  (js/console.time "init:250000")
  (let [points (generate-points 5000 10)]
    (println (count points))
    (k/create2d points)
    (js/console.timeEnd "init:250000")))

(defn bench-init
  []
  (bench-init-10000)
  (bench-init-250000))

;; --- Nearest Search Benchmark

(defn- bench-knn-160000
  []
  (let [tree (k/create2d (generate-points 4000 10))]
    (dotimes [i 100]
      (js/console.time "knn:160000")
      (let [pt #js [(rand-int 400)
                    (rand-int 400)]]
        (.nearest tree pt 2))
      (js/console.timeEnd "knn:160000"))))

(defn- bench-knn-360000
  []
  (let [tree (k/create2d (generate-points 6000 10))]
    (dotimes [i 100]
      (js/console.time "knn:360000")
      (let [pt #js [(rand-int 600)
                    (rand-int 600)]]
        (.nearest tree pt 2))
      (js/console.timeEnd "knn:360000"))))

(defn bench-knn
  []
  (bench-knn-160000)
  (bench-knn-360000))

;; --- Accuracity tests

(defn test-accuracity
  []
  (let [tree (k/create2d (generate-points 4000 20))]
    (print "[1742 1419]")
    (pprint (js->clj (.nearest tree #js [1742 1419] 6)))
    (print "[1742 1420]")
    (pprint (js->clj (.nearest tree #js [1742 1420] 6)))
    ))

(defn main
  [& [type]]
  (cond
    (= type "init")
    (bench-init)

    (= type "knn")
    (bench-knn)

    (= type "test")
    (test-accuracity)

    :else
    (println "not implemented")))

(set! *main-cli-fn* main)
