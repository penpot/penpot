;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.path-data-test
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.pprint :as pp]
   [app.common.transit :as trans]
   [app.common.types.path :as path]
   [app.common.types.path.segment :as path.segment]
   [clojure.test :as t]))

(def sample-content
  [{:command :move-to :params {:x 480.0 :y 839.0}}
   {:command :line-to :params {:x 439.0 :y 802.0}}
   {:command :curve-to :params {:c1x 368.0 :c1y 737.0 :c2x 310.0 :c2y 681.0 :x 264.0 :y 634.0}}
   {:command :close-path :params {}}])

(def sample-content-large
  [{:command :move-to :params {:x 480.0 :y 839.0}}
   {:command :line-to :params {:x 439.0 :y 802.0}}
   {:command :curve-to :params {:c1x 368.0 :c1y 737.0 :c2x 310.0 :c2y 681.0 :x 264.0 :y 634.0}}
   {:command :curve-to :params {:c1x 218.0 :c1y 587.0 :c2x 181.0 :c2y 545.0 :x 154.0 :y 508.0}}
   {:command :curve-to :params {:c1x 126.0 :c1y 471.0 :c2x 107.0 :c2y 438.0 :x 96.0  :y 408.0}}
   {:command :curve-to :params {:c1x 85.0  :c1y 378.0 :c2x 80.0  :c2y 347.0 :x 80.0  :y 317.0}}
   {:command :curve-to :params {:c1x 80.0  :c1y 256.0 :c2x 100.0 :c2y 206.0 :x 140.0 :y 166.0}}
   {:command :curve-to :params {:c1x 180.0 :c1y 126.0 :c2x 230.0 :c2y 106.0 :x 290.0 :y 106.0}}
   {:command :curve-to :params {:c1x 328.0 :c1y 106.0 :c2x 363.0 :c2y 115.0 :x 395.0 :y 133.0}}
   {:command :curve-to :params {:c1x 427.0 :c1y 151.0 :c2x 456.0 :c2y 177.0 :x 480.0 :y 211.0}}
   {:command :curve-to :params {:c1x 508.0 :c1y 175.0 :c2x 537.0 :c2y 148.0 :x 569.0 :y 131.0}}
   {:command :curve-to :params {:c1x 600.0 :c1y 114.0 :c2x 634.0 :c2y 106.0 :x 670.0 :y 106.0}}
   {:command :curve-to :params {:c1x 729.0 :c1y 106.0 :c2x 779.0 :c2y 126.0 :x 819.0 :y 166.0}}
   {:command :curve-to :params {:c1x 859.0 :c1y 206.0 :c2x 880.0 :c2y 256.0 :x 880.0 :y 317.0}}
   {:command :curve-to :params {:c1x 880.0 :c1y 347.0 :c2x 874.0 :c2y 378.0 :x 863.0 :y 408.0}}
   {:command :curve-to :params {:c1x 852.0 :c1y 438.0 :c2x 833.0 :c2y 471.0 :x 806.0 :y 508.0}}
   {:command :curve-to :params {:c1x 778.0 :c1y 545.0 :c2x 741.0 :c2y 587.0 :x 695.0 :y 634.0}}
   {:command :curve-to :params {:c1x 649.0 :c1y 681.0 :c2x 591.0 :c2y 737.0 :x 521.0 :y 802.0}}
   {:command :line-to :params {:x 480.0 :y 839.0}}
   {:command :close-path :params {}}
   {:command :move-to :params {:x 480.0 :y 760.0}}
   {:command :curve-to :params {:c1x 547.0 :c1y 698.0 :c2x 603.0 :c2y 644.0 :x 646.0 :y 600.0}}
   {:command :curve-to :params {:c1x 690.0 :c1y 556.0 :c2x 724.0 :c2y 517.0 :x 750.0 :y 484.0}}
   {:command :curve-to :params {:c1x 776.0 :c1y 450.0 :c2x 794.0 :c2y 420.0 :x 804.0 :y 394.0}}
   {:command :curve-to :params {:c1x 814.0 :c1y 368.0 :c2x 820.0 :c2y 342.0 :x 820.0 :y 317.0}}
   {:command :curve-to :params {:c1x 820.0 :c1y 273.0 :c2x 806.0 :c2y 236.0 :x 778.0 :y 2085.0}}
   {:command :curve-to :params {:c1x 750.0 :c1y 180.0 :c2x 714.0 :c2y 166.0 :x 670.0 :y 1660.0}}
   {:command :curve-to :params {:c1x 635.0 :c1y 166.0 :c2x 604.0 :c2y 176.0 :x 574.0 :y 1975.0}}
   {:command :curve-to :params {:c1x 545.0 :c1y 218.0 :c2x 522.0 :c2y 248.0 :x 504.0 :y 2860.0}}
   {:command :line-to :params {:x 455.0 :y 286.0}}
   {:command :curve-to :params {:c1x 437.0 :c1y 248.0 :c2x 414.0 :c2y 219.0 :x 385.0 :y 198.0}}
   {:command :curve-to :params {:c1x 355.0 :c1y 176.0 :c2x 324.0 :c2y 166.0 :x 289.0 :y 166.0}}
   {:command :curve-to :params {:c1x 245.0 :c1y 166.0 :c2x 210.0 :c2y 180.0 :x 182.0 :y 208.0}}
   {:command :curve-to :params {:c1x 154.0 :c1y 236.0 :c2x 140.0 :c2y 273.0 :x 140.0 :y 317.0}}
   {:command :curve-to :params {:c1x 140.0 :c1y 343.0 :c2x 145.0 :c2y 369.0 :x 155.0 :y 395.0}}
   {:command :curve-to :params {:c1x 165.0 :c1y 421.0 :c2x 183.0 :c2y 451.0 :x 209.0 :y 485.0}}
   {:command :curve-to :params {:c1x 235.0 :c1y 519.0 :c2x 270.0 :c2y 558.0 :x 314.0 :y 602.0}}
   {:command :curve-to :params {:c1x 358.0 :c1y 646.0 :c2x 413.0 :c2y 698.0 :x 480.0 :y 760.0}}
   {:command :close-path :params {}}
   {:command :move-to :params {:x 480.0 :y 463.0}}
   {:command :close-path :params {}}])

(def sample-bytes
  [0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 67 -16 0 0 68 81 -64 0
   0 2 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 67 -37 -128 0 68 72 -128 0
   0 3 0 0 67 -72 0 0 68 56 64 0 67 -101 0 0 68 42 64 0 67 -124 0 0 68 30 -128 0
   0 4 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0])

;; This means it implements IReduceInit/IReduce protocols
(t/deftest path-data-to-vector
  (let [pdata  (path/content sample-content)
        result (vec pdata)]
    (t/is (= 4 (count result)))
    (t/is (= (get-in sample-content [0 :command])
             (get-in result [0 :command])))
    (t/is (= (get-in sample-content [1 :command])
             (get-in result [1 :command])))
    (t/is (= (get-in sample-content [2 :command])
             (get-in result [2 :command])))
    (t/is (= (get-in sample-content [3 :command])
             (get-in result [3 :command])))

    (t/is (= (get-in sample-content [0 :params])
             (get-in result [0 :params])))
    (t/is (= (get-in sample-content [1 :params])
             (get-in result [1 :params])))
    (t/is (= (get-in sample-content [2 :params])
             (get-in result [2 :params])))
    (t/is (= (get-in sample-content [3 :params])
             (get-in result [3 :params])))))

(t/deftest path-data-plain-to-binary
  (let [pdata (path/content sample-content)]
    (t/is (= sample-bytes
             (vec
              #?(:cljs (js/Int8Array. (.-buffer pdata))
                 :clj  (.array (.-buffer pdata))))))
    (t/is (= sample-content
             (vec pdata)))))

(t/deftest path-data-from-binary
  (let [barray #?(:clj (byte-array sample-bytes)
                  :cljs (js/Int8Array.from sample-bytes))
        content (path/from-bytes barray)]

    (t/is (= (vec content) sample-content))))

(t/deftest path-data-transit-roundtrip
  (let [pdata    (path/content sample-content)
        result1  (trans/encode-str pdata)
        expected (str "[\"~#penpot/path-data\",\"~bAAEAAAAAAAAAAAAAAAAAAAAAAA"
                      "BD8AAARFHAAAACAAAAAAAAAAAAAAAAAAAAAAAAQ9uAAERIgAAAAwAA"
                      "Q7gAAEQ4QABDmwAARCpAAEOEAABEHoAAAAQAAAAAAAAAAAAAAAAAAA"
                      "AAAAAAAAAAAAAAAA==\"]")
        result2  (trans/decode-str result1)]
    (t/is (= expected result1))
    (t/is (= pdata result2))))

#?(:clj
   (t/deftest path-data-fresian
     (let [pdata (path/content sample-content)
           result1 (fres/encode pdata)
           result2 (fres/decode result1)]
       (t/is (= pdata result2)))))

(defn- transform-plain-content
  "Apply a transformation to a path content;

  This is a copy of previous impl, that uses plain format to calculate
  the new transformed path content"
  [content transform]
  (let [set-tr
        (fn [params px py]
          (let [tr-point (-> (gpt/point (get params px) (get params py))
                             (gpt/transform transform))]
            (assoc params
                   px (:x tr-point)
                   py (:y tr-point))))

        transform-params
        (fn [{:keys [x c1x c2x] :as params}]
          (cond-> params
            (some? x)   (set-tr :x :y)
            (some? c1x) (set-tr :c1x :c1y)
            (some? c2x) (set-tr :c2x :c2y)))]

    (into []
          (map #(update % :params transform-params))
          content)))

(t/deftest path-transform-1
  (let [matrix  (gmt/translate-matrix 10 10)
        content (path/content sample-content)

        result1 (path/transform-content content matrix)
        result2 (transform-plain-content sample-content matrix)
        result3 (transform-plain-content content matrix)]

    (t/is (= (vec result1) result2))
    (t/is (= result2 result3))))

(t/deftest path-transform-2
  (let [matrix  (gmt/translate-matrix 10 10)
        content (path/content sample-content-large)

        result1 (path/transform-content content matrix)
        result2 (transform-plain-content sample-content-large matrix)
        result3 (transform-plain-content content matrix)]

    (t/is (= (vec result1) result2))
    (t/is (= result2 result3))))

(defn- content->points
  "Given a content return all points.

  Legacy impl preserved for tests purposes"
  [content]
  (letfn [(segment->point [seg]
            (let [params (get seg :params)
                  x      (get params :x)
                  y      (get params :y)]
              (when (d/num? x y)
                (gpt/point x y))))]
    (some->> (seq content)
             (into [] (keep segment->point)))))

(t/deftest path-get-points
  (let [content (path/content sample-content-large)

        result1 (content->points content)
        result2 (content->points sample-content-large)
        result3 (path.segment/get-points content)]

    (t/is (= result1 result2))
    (t/is (= result2 result3))))
