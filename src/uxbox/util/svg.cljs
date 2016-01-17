(ns uxbox.util.svg
  "A svg related utils."
  (:require [cuerdas.core :as str]
            [uxbox.util.matrix :as mtx]
            [uxbox.util.math :as mth]
            [uxbox.util.data :refer (without-keys)]))

(defn translate-matrix
  ([x]
   (translate-matrix x x))
  ([x y]
   (mtx/matrix [[1 0 x]
                [0 1 y]
                [0 0 1]])))

(defn scale-matrix
  ([w]
   (scale-matrix w w))
  ([w h]
   (mtx/matrix [[w 0 0]
                [0 h 0]
                [0 0 1]])))

(defn rotation-matrix
  [^number degrees]
  (let [v1 (mth/cos (mth/radiants degrees))
        v2 (mth/sin (mth/radiants degrees))
        v3 (mth/neg v2)]
    (mtx/matrix [[v1 v3 0]
                 [v2 v1 0]
                 [0 0 1]])))

(defn apply-translate
  [{:keys [x y]}]
  (translate-matrix x y))

(defn apply-scale
  [{:keys [width height view-box]}]
  (if (and width height)
    (let [orig-width (nth view-box 2)
          orig-height (nth view-box 3)
          scale-x (/ width orig-width)
          scale-y (/ height orig-height)]
      (scale-matrix scale-x scale-y))
    (scale-matrix 1 1)))

(defn apply-rotation
  [{:keys [rotation x y width height view-box] :or {rotation 0}}]
  (let [width (nth view-box 2)
        height (nth view-box 3)
        x (- width (/ width 2))
        y (- height (/ height 2))]
    (mtx/multiply (translate-matrix x y)
                  (rotation-matrix rotation)
                  (translate-matrix (- x)
                                    (- y)))))

(def interpret-attrs (juxt apply-translate
                           apply-scale
                           apply-rotation))

(defn calculate-transform
  [attrs]
  (let [result (->> (interpret-attrs attrs)
                    (apply mtx/multiply)
                    (deref)
                    (take 6)
                    (flatten))]
    (->> (map #(nth result %) [0 3 1 4 2 5])
         (str/join ",")
         (str/format "matrix(%s)"))))

(defn apply-transform
  [attrs]
  (let [transform (calculate-transform attrs)]
    (-> attrs
        (without-keys [:rotation :width :height :x :y :view-box])
        (assoc :transform transform))))
