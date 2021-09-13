;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.geom
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gshp]
   [app.util.path.commands :as upc]))

(defn calculate-opposite-handler
  "Given a point and its handler, gives the symetric handler"
  [point handler]
  (let [handler-vector (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate handler-vector))))

(defn split-line-to
  "Given a point and a line-to command will create a two new line-to commands
  that will split the original line into two given a value between 0-1"
  [from-p cmd t-val]
  (let [to-p (upc/command->point cmd)
        sp (gpt/lerp from-p to-p t-val)]
    [(upc/make-line-to sp) cmd]))

(defn split-curve-to
  "Given the point and a curve-to command will split the curve into two new
  curve-to commands given a value between 0-1"
  [from-p cmd t-val]
  (let [params (:params cmd)
        end (gpt/point (:x params) (:y params))
        h1 (gpt/point (:c1x params) (:c1y params))
        h2 (gpt/point (:c2x params) (:c2y params))
        [[_ to1 h11 h21]
         [_ to2 h12 h22]] (gshp/curve-split from-p end h1 h2 t-val)]
    [(upc/make-curve-to to1 h11 h21)
     (upc/make-curve-to to2 h12 h22)]))

(defn split-line-to-ranges
  "Splits a line into several lines given the points in `values`
  for example (split-line-to-ranges p c [0 0.25 0.5 0.75 1] will split
  the line into 4 lines"
  [from-p cmd values]
  (let [to-p (upc/command->point cmd)]
    (->> (conj values 1)
         (mapv (fn [val]
                 (-> (gpt/lerp from-p to-p val)
                     #_(gpt/round 2)
                     (upc/make-line-to)))))))

(defn split-curve-to-ranges
  "Splits a curve into several curves given the points in `values`
  for example (split-curve-to-ranges p c [0 0.25 0.5 0.75 1] will split
  the curve into 4 curves that draw the same curve"
  [from-p cmd values]
  (if (empty? values)
    [cmd]
    (let [to-p (upc/command->point cmd)
          params (:params cmd)
          h1 (gpt/point (:c1x params) (:c1y params))
          h2 (gpt/point (:c2x params) (:c2y params))

          values-set (->> (conj values 1) (into (sorted-set)))]
      (->> (d/with-prev values-set)
           (mapv
            (fn [[t1 t0]]
              (let [t0 (if (nil? t0) 0 t0)
                    [_ to-p h1' h2'] (gshp/subcurve-range from-p to-p h1 h2 t0 t1)]
                (upc/make-curve-to (-> to-p #_(gpt/round 2)) h1' h2'))))))))

(defn opposite-handler
  "Calculates the coordinates of the opposite handler"
  [point handler]
  (let [phv (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate phv))))

(defn opposite-handler-keep-distance
  "Calculates the coordinates of the opposite handler but keeping the old distance"
  [point handler old-opposite]
  (let [old-distance (gpt/distance point old-opposite)
        phv (gpt/to-vec point handler)
        phv2 (gpt/multiply
              (gpt/unit (gpt/negate phv))
              (gpt/point old-distance))]
    (gpt/add point phv2)))

(defn content->points
  "Returns the points in the given content"
  [content]
  (->> content
       (map #(when (-> % :params :x)
               (gpt/point (-> % :params :x) (-> % :params :y))))
       (remove nil?)
       (into [])))

