;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.fill-test
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.pprint :as pp]
   [app.common.pprint :as pp]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.transit :as trans]
   [app.common.types.fill :as types.fill]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn equivalent-fill?
  [fill-a fill-b]
  ;; (prn "-------------------")
  ;; (app.common.pprint/pprint fill-a)
  ;; (app.common.pprint/pprint fill-b)

  (and (= (get fill-a :fill-color-ref-file)
          (get fill-b :fill-color-ref-file))

       (= (get fill-a :fill-color-ref-id)
          (get fill-b :fill-color-ref-id))

       (or (and (contains? fill-a :fill-color)
                (= (:fill-color fill-a)
                   (:fill-color fill-b))
                (mth/close? (:fill-opacity fill-a 1.0)
                            (:fill-opacity fill-b 1.0)))
           (and (contains? fill-a :fill-image)
                (mth/close? (:fill-opacity fill-a 1.0)
                            (:fill-opacity fill-b 1.0))
                (let [image-a (:fill-image fill-a)
                      image-b (:fill-image fill-b)]
                  (and (= (:id image-a)
                          (:id image-b))
                       (= (:mtype image-a)
                          (:mtype image-b))
                       (mth/close? (:width image-a)
                                   (:width image-b))
                       (mth/close? (:height image-a)
                                   (:height image-b)))))
           (and (contains? fill-a :fill-color-gradient)
                (mth/close? (:fill-opacity fill-a 1)
                            (:fill-opacity fill-b 1))
                (let [gradient-a (:fill-color-gradient fill-a)
                      gradient-b (:fill-color-gradient fill-b)]
                  (and (= (count (:stops gradient-a))
                          (count (:stops gradient-b)))
                       (= (get gradient-a :type)
                          (get gradient-b :type))
                       (mth/close? (get gradient-a :start-x)
                                   (get gradient-b :start-x))
                       (mth/close? (get gradient-a :start-y)
                                   (get gradient-b :start-y))
                       (mth/close? (get gradient-a :end-x)
                                   (get gradient-b :end-x))
                       (mth/close? (get gradient-a :end-y)
                                   (get gradient-b :end-y))
                       (mth/close? (get gradient-a :width)
                                   (get gradient-b :width))
                       (every? true?
                               (map (fn [stop-a stop-b]
                                      (and (= (get stop-a :color)
                                              (get stop-b :color))
                                           (mth/close? (get stop-a :opacity 1)
                                                       (get stop-b :opacity 1))
                                           (mth/close? (get stop-a :offset)
                                                       (get stop-b :offset))))
                                    (get gradient-a :stops)
                                    (get gradient-b :stops)))))))))


(def sample-fill-1
  {:fill-color "#fabada"
   :fill-opacity 0.7})

(t/deftest build-from-plain-1
  (let [fills (types.fill/from-plain [sample-fill-1])]
    (t/is (types.fill/fills? fills))
    (t/is (= 1 (count fills)))
    (t/is (equivalent-fill? (first fills) sample-fill-1))))

(def sample-fill-2
  {:fill-color-ref-file #uuid "4fcb3db7-d281-8004-8006-3a97e2e142ad"
   :fill-color-ref-id #uuid "fb19956a-c9e0-8056-8006-3a9c78f531c6"
   :fill-image {:width 200, :height 100, :mtype "image/gif",
                :id #uuid "b30f028d-cc2f-8035-8006-3a93bd0e137b",
                :name "ovba",
                :keep-aspect-ratio false}})

(t/deftest build-from-plain-2
  (let [fills (types.fill/from-plain [sample-fill-2])]
    (t/is (types.fill/fills? fills))
    (t/is (= 1 (count fills)))
    (t/is (equivalent-fill? (first fills) sample-fill-2))))

(def sample-fill-3
  {:fill-color-ref-id #uuid "fb19956a-c9e0-8056-8006-3a9c78f531c6"
   :fill-color-ref-file #uuid "fb19956a-c9e0-8056-8006-3a9c78f531c5"
   :fill-color-gradient
   {:type :linear,
    :start-x 0.75,
    :start-y 3.0,
    :end-x 1.0,
    :end-y 1.5,
    :width 200,
    :stops [{:color "#631aa8", :offset 0.5}]}})

(t/deftest build-from-plain-3
  (let [fills (types.fill/from-plain [sample-fill-3])]
    (t/is (types.fill/fills? fills))
    (t/is (= 1 (count fills)))
    (t/is (equivalent-fill? (first fills) sample-fill-3))))

(def sample-fill-4
  {:fill-color-ref-file #uuid "2eef07f1-e38a-8062-8006-3aa264d5b784",
   :fill-color-gradient
   {:type :radial,
    :start-x 0.5,
    :start-y -1.0,
    :end-x -0.5,
    :end-y 2,
    :width 0.5,
    :stops [{:color "#781025", :offset 0.0} {:color "#035c3f", :offset 0.2}]},
   :fill-opacity 1.0,
   :fill-color-ref-id #uuid "2eef07f1-e38a-8062-8006-3aa264d5b785"})

(t/deftest build-from-plain-4
  (let [fills (types.fill/from-plain [sample-fill-4])]
    (t/is (types.fill/fills? fills))
    (t/is (= 1 (count fills)))
    (t/is (equivalent-fill? (first fills) sample-fill-4))))

(def sample-fill-5
  {:fill-color-ref-file #uuid "b0f76f9a-f548-806e-8006-3aa4456131d1",
   :fill-color-ref-id #uuid "b0f76f9a-f548-806e-8006-3aa445618851",
   :fill-color-gradient
   {:type :radial,
    :start-x -0.86,
    :start-y 6.0,
    :end-x 0.25,
    :end-y -0.5,
    :width 3.8,
    :stops [{:color "#bba1aa", :opacity 0.37, :offset 0.84}]}})

(t/deftest build-from-plain-5
  (let [fills (types.fill/from-plain [sample-fill-5])]
    (t/is (types.fill/fills? fills))
    (t/is (= 1 (count fills)))
    (t/is (equivalent-fill? (first fills) sample-fill-5))))

(def sample-fill-6
  {:fill-color-gradient
   {:type :linear,
    :start-x 3.5,
    :start-y 0.39,
    :end-x -1.87,
    :end-y 1.95,
    :width 2.62,
    :stops [{:color "#e15610", :offset 0.4} {:color "#005a9e", :opacity 0.62, :offset 0.81}]}})

(t/deftest build-from-plain-6
  (let [fills (types.fill/from-plain [sample-fill-6])]
    (t/is (types.fill/fills? fills))
    (t/is (= 1 (count fills)))
    (t/is (equivalent-fill? (first fills) sample-fill-6))))

(t/deftest fills-datatype-roundtrip
  (smt/check!
   (smt/for [fill (->> (sg/generator types.fill/schema:fill)
                       (sg/fmap d/without-nils)
                       (sg/fmap (fn [fill]
                                  (cond-> fill
                                    (and (not (and (contains? fill :fill-color-ref-id)
                                                   (contains? fill :fill-color-ref-file)))
                                         (or (contains? fill :fill-color-ref-id)
                                             (contains? fill :fill-color-ref-file)))
                                    (-> (assoc :fill-color-ref-file (uuid/next))
                                        (assoc :fill-color-ref-id (uuid/next)))))))]
     (let [bfills (types.fill/from-plain [fill])]
       (and (= (count bfills) 1)
            (equivalent-fill? (first bfills) fill))))
   {:num 2000}))

(t/deftest equality-operation
  (let [fills1 (types.fill/from-plain [sample-fill-6])
        fills2 (types.fill/from-plain [sample-fill-6])]
    (t/is (= fills1 fills2))))

(t/deftest reduce-impl
  (let [fills1 (types.fill/from-plain [sample-fill-6])
        fills2 (reduce (fn [result fill]
                         (conj result fill))
                       []
                       fills1)
        fills3 (types.fill/from-plain fills2)]
    (t/is (= fills1 fills3))))

(t/deftest indexed-access
  (let [fills1 (types.fill/from-plain [sample-fill-6])
        fill0  (nth fills1 0)
        fill1  (nth fills1 1)]
    (t/is (nil? fill1))
    (t/is (equivalent-fill? fill0 sample-fill-6))))
