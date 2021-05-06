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
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [clojure.set :as set]
   [app.common.math :as mth]
   [app.util.path.commands :as upc]))

(defn calculate-opposite-handler
  "Given a point and its handler, gives the symetric handler"
  [point handler]
  (let [handler-vector (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate handler-vector))))

(defn split-line-to [from-p cmd val]
  (let [to-p (upc/command->point cmd)
        sp (gpt/line-val from-p to-p val)]
    [(upc/make-line-to sp) cmd]))

(defn split-curve-to [from-p cmd val]
  (let [params (:params cmd)
        end (gpt/point (:x params) (:y params))
        h1 (gpt/point (:c1x params) (:c1y params))
        h2 (gpt/point (:c2x params) (:c2y params))
        [[_ to1 h11 h21]
         [_ to2 h12 h22]] (gshp/curve-split from-p end h1 h2 val)]
    [(upc/make-curve-to to1 h11 h21)
     (upc/make-curve-to to2 h12 h22)]))

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

(defn content->points [content]
  (->> content
       (map #(when (-> % :params :x) (gpt/point (-> % :params :x) (-> % :params :y))))
       (remove nil?)
       (into [])))

