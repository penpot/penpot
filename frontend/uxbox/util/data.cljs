(ns uxbox.util.data
  "A collection of data transformation utils."
  (:require [cljs.reader :as r]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data structure manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; NOTE: commented because tansients are buggy in cljs?
;; (defn index-by
;;   "Return a indexed map of the collection
;;   keyed by the result of executing the getter
;;   over each element of the collection."
;;   [coll getter]
;;   (let [data (transient {})]
;;     (run! #(do
;;              (println (getter %))
;;              (assoc! data (getter %) %)) coll)
;;     (println "test1:" (keys data))
;;     (let [r (persistent! data)]
;;       (println "test2:" (keys r))
;;       r)))

;; (defn index-by
;;   "Return a indexed map of the collection
;;   keyed by the result of executing the getter
;;   over each element of the collection."
;;   [coll getter]
;;   (let [data (transient {})]
;;     (loop [coll coll]
;;       (let [item (first coll)]
;;         (if item
;;           (do
;;             (assoc! data (getter item) item)
;;             (recur (rest coll)))
;;           (let [_ 1 #_(println "test1:" (keys data))
;;                 r (persistent! data)]
;;             (println "test2:" (keys r))
;;             r))))))

(defn index-by
  "Return a indexed map of the collection
  keyed by the result of executing the getter
  over each element of the collection."
  [coll getter]
  (reduce (fn [acc item]
            (assoc acc (getter item) item))
          {}
          coll))

(def ^:static index-by-id #(index-by % :id))

(defn remove-nil-vals
  "Given a map, return a map removing key-value
  pairs when value is `nil`."
  [data]
  (into {} (remove (comp nil? second) data)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Numbers Parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-string
  [v]
  (r/read-string v))

(defn parse-int
  [v]
  (js/parseInt v 10))
