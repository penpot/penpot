(ns bench.core
  (:require [kdtree :as k]))

(enable-console-print!)

(defn generate-points
  [n]
  (for [x (range 0 n)
        y (range 0 n)]
    (k/point2d x y)))

(defn bench-init-100
  []
  (let [points (into-array (generate-points 10))]
    (time
     (k/create2d points))))

(defn bench-init-1000
  []
  (let [points (into-array (generate-points 100))]
    (time
     (k/create2d points))))

(defn bench-init-10000
  []
  (let [points (into-array (generate-points 1000))]
    (time
     (k/create2d points))))

(defn bench-init
  []
  (println "init:100")
  (bench-init-100)
  (println "init:1000")
  (bench-init-1000)
  (println "init:10000")
  (bench-init-10000))

(defn bench-knn-100000-2
  []
  (let [tree (-> (into-array (generate-points 10000))
                 (k/create2d points))
        pt (k/point2d (rand-int 10000)
                      (rand-int 10000))]

    (dotimes [i 100]
      (time
       (.nearest tree pt 2)))))

(defn bench-knn-10000-2
  []
  (let [tree (-> (into-array (generate-points 1000))
                 (k/create2d points))
        pt (k/point2d (rand-int 1000)
                      (rand-int 1000))]

    (dotimes [i 100]
      (time
       (.nearest tree pt 2)))))

(defn bench-knn
  []
  (println "knn:10000:2")
  (bench-knn-10000-2))

(defn main
  [& [type]]
  (cond
    (= type "init")
    (bench-init)

    (= type "knn")
    (bench-knn)

    :else
    (println "not implemented")))

(set! *main-cli-fn* main)
