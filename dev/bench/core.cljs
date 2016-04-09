(ns bench.core
  (:require [kdtree :as k]
            [cljs.nodejs :as node]))

(enable-console-print!)

(defn generate-points
  [n]
  (for [x (range 0 n)
        y (range 0 n)]
    #js [x y]))

(defn bench-init-100
  []
  (js/console.time "init:100")
  (let [points (into-array (generate-points 10))]
    (k/create2d points)
    (js/console.timeEnd "init:100")))

(defn bench-init-10000
  []
  (js/console.time "init:10000")
  (let [points (into-array (generate-points 100))]
    (k/create2d points)
    (js/console.timeEnd "init:10000")))

(defn bench-init-160000
  []
  (js/console.time "init:160000")
  (let [points (into-array (generate-points 400))]
    (k/create2d points)
    (js/console.timeEnd "init:160000")))

(defn bench-init
  []
  (bench-init-100)
  (bench-init-10000)
  (bench-init-160000))

(defn bench-knn-160000
  []
  (let [tree (-> (into-array (generate-points 400))
                 (k/create2d))]
    (dotimes [i 100]
      (js/console.time "knn:160000")
      (let [pt #js [(rand-int 400)
                    (rand-int 400)]]
        (.nearest tree pt 2))
      (js/console.timeEnd "knn:160000"))))

(defn bench-knn-360000
  []
  (let [tree (-> (into-array (generate-points 600))
                 (k/create2d))]
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

(defn main
  [& [type]]
  (bench-init)
  (bench-knn)
  #_(cond
    (= type "init")
    (bench-init)

    (= type "knn")
    (bench-knn)

    :else
    (println "not implemented")))

(set! *main-cli-fn* main)
