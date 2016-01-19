(ns uxbox.util.matrix
  "A lightweight abstraction over Matrix library
  of the Google Closure Library."
  (:import goog.math.Matrix
           goog.math.Size))

(extend-type Matrix
  cljs.core/ICounted
  (-count [v]
    (let [^Size size (.getSize v)]
      (* (.-width size)
         (.-height size))))

  cljs.core/IDeref
  (-deref [v]
    (js->clj (.toArray v)))

  IPrintWithWriter
  (-pr-writer [v writer _]
    (->> (str "#goog.math.Matrix " (js->clj (.toArray v)))
         (cljs.core/-write writer))))

(defn matrix
  "Create a matrix instance from coll.
  The size is determined by the number
  of elements of the collection."
  [coll]
  {:pre [(coll? coll)
         (coll? (first coll))]}
  (Matrix. (clj->js coll)))

(defn multiply
  ([n] n)
  ([n m]
   (.multiply n m))
  ([n m & more]
   (reduce multiply (.multiply n m) more)))

