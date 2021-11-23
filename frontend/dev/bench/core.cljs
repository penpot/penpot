(ns bench.core
  (:require [kdtree.core :as k]
            [intervaltree.core :as it]
            [cljs.pprint :refer (pprint)]
            [cljs.nodejs :as node]))

(enable-console-print!)

;; --- Index Initialization Benchmark

(defn- bench-init-10000
  []
  (println "1000x1000,10 -> 10000 points")
  (time
   (k/generate 1000 1000 10 10)))

(defn- bench-init-250000
  []
  (time
   (k/generate 5000 5000 10 10)))

(defn bench-init
  []
  (bench-init-10000)
  (bench-init-10000)
  (bench-init-250000)
  (bench-init-250000)
  (bench-init-10000)
  (bench-init-10000)
  (bench-init-250000)
  (bench-init-250000))

;; --- Nearest Search Benchmark

(defn- bench-knn-160000
  []
  (let [tree (k/create)]
    (k/setup tree 4000 4000 10 10)
    (println "KNN Search (160000 points) 1000 times")
    (time
     (dotimes [i 1000]
       (let [pt #js [(rand-int 400)
                     (rand-int 400)]]
         (k/nearest tree pt 2))))))


(defn- bench-knn-360000
  []
  (let [tree (k/create)]
    (k/initialize tree 6000 6000 10 10)
    (println "KNN Search (360000 points) 1000 times")
    (time
     (dotimes [i 1000]
       (let [pt #js [(rand-int 600)
                     (rand-int 600)]]
         (k/nearest tree pt 2))))))

(defn bench-knn
  []
  (bench-knn-160000)
  (bench-knn-360000))

;; --- Accuracy tests

(defn test-accuracy
  []
  (let [tree (k/create)]
    (k/setup tree 4000 4000 20 20)
    (print "[1742 1419]")
    (pprint (js->clj (k/nearest tree #js [1742 1419] 6)))
    (print "[1742 1420]")
    (pprint (js->clj (k/nearest tree #js [1742 1420] 6)))
    ))

(defn test-interval
  []
  (let [tree (it/create)]
    (it/add tree #js [1 5])
    (it/add tree #js [5 7])
    (it/add tree #js [-4 -1])
    (it/add tree #js [-10 -3])
    (it/add tree #js [-20 -10])
    (it/add tree #js [20 30])
    (it/add tree #js [3 9])
    (it/add tree #js [100 200])
    (it/add tree #js [1000 2000])
    (it/add tree #js [6 9])

    (js/console.dir tree #js {"depth" nil})
    (js/console.log "contains", 4, (it/contains tree 4))
    (js/console.log "contains", 0, (it/contains tree 0))
    ))

(defn main
  [& [type]]
  (cond
    (= type "kd-init")
    (bench-init)

    (= type "kd-search")
    (bench-knn)

    (= type "kd-test")
    (test-accuracy)

    (= type "interval")
    (test-interval)

    :else
    (println "not implemented")))

(set! *main-cli-fn* main)
