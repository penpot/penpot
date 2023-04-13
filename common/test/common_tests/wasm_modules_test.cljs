;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.wasm-modules-test
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.wasm :as wasm]
   [app.wasm.transform :as wasm.transform]
   [clojure.test :as t]
   [promesa.core :as p]))

(t/use-fixtures :once
  {:before (fn [& args]
             (t/async done
               (->> (wasm/init!) (p/fmap done))))})

(defn- modifier-generator
  []
  (sg/one-of
   (sg/let [center (sg/generator ::gpt/point)
            angle (sg/small-int :min 0 :max 360)]
     (ctm/rotation nil center angle))
   (sg/let [vector (sg/generator ::gpt/point)]
     (ctm/move nil vector))

   (sg/let [vector (sg/generator ::gpt/point)
            origin (sg/generator ::gpt/point)]
     (ctm/resize-parent nil vector origin))

   (sg/let [vector (sg/generator ::gpt/point)]
     (ctm/move-parent nil vector))

   (sg/let [size  (sg/small-int :min 0 :max 5)]
     (ctm/scale-content nil vector))))

(defn check-equal-matrix!
  [ma mb]
  (t/is (mth/close? (:a ma) (:a mb)))
  (t/is (mth/close? (:b ma) (:b mb)))
  (t/is (mth/close? (:c ma) (:c mb)))
  (t/is (mth/close? (:d ma) (:d mb)))
  (t/is (mth/close? (:e ma) (:e mb)))
  (t/is (mth/close? (:f ma) (:f mb))))

(t/deftest check-modifiers->transform
  (sg/check!
   (sg/for [modifiers (modifier-generator)]
     ;; (app.common.pprint/pprint modifiers)
     (let [modifiers  (->> (into (dm/get-prop modifiers :geometry-parent)
                                 (dm/get-prop modifiers :geometry-child))
                           (sort-by #(dm/get-prop % :order)))
           transform2 (into {} (wasm.transform/modifiers->transform modifiers))
           transform1 (into {} (ctm/modifiers->transform' modifiers))
           ]

       ;; (println "=====")
       ;; (prn "RR1" transform1)
       ;; (prn "RR2" transform2)

       (check-equal-matrix! transform1 transform2)))
   {:num 1000}))


(t/deftest regression-1
  (let [modifiers [(ctm/map->GeometricOperation
                    {:order 2,
                     :type :resize,
                     :vector (gpt/map->Point {:x 1.01195219123506 :y 1.043859649122807})
                     :origin (gpt/map->Point {:x 144, :y -136})
                     :transform nil,
                     :transform-inverse nil,
                     :rotation nil,
                     :center nil})
                   (ctm/map->GeometricOperation
                    {:order 5,
                     :type :resize,
                     :vector (gpt/map->Point {:x 0.9881889763779527 :y 0.9579831932773106})
                     :origin (gpt/map->Point {:x 235.4301269199957 :y -95.31825367796473})
                     :transform (gmt/map->Matrix {:a 1, :b 0, :c 0, :d 1, :e 0, :f 0})
                     :transform-inverse (gmt/map->Matrix {:a 1, :b 0, :c 0, :d 1, :e 0, :f 0})
                     :rotation nil,
                     :center nil})
                   (ctm/map->GeometricOperation
                    {:order 6,
                     :type :move,
                     :vector (gpt/map->Point {:x -1.0798833869542932, :y -1.7093171947766734})
                     :origin nil,
                     :transform nil,
                     :transform-inverse nil,
                     :rotation nil,
                     :center nil})]
        transform1 (into {} (wasm.transform/modifiers->transform modifiers))
        transform2 (into {} (ctm/modifiers->transform' modifiers))]

    (check-equal-matrix! transform1 transform2)))
