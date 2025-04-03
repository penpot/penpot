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
   [clojure.test :as t]))

(def sample-content
  [{:command :move-to :params {:x 480.0 :y 839.0}}
   {:command :line-to :params {:x 439.0 :y 802.0}}
   {:command :curve-to :params {:c1x 368.0 :c1y 737.0 :c2x 310.0 :c2y 681.0 :x 264.0 :y 634.0}}
   {:command :close-path :params {}}])

(def sample-content-large
  [{:command :move-to :params {:x 480 :y 839}}
   {:command :line-to :params {:x 439 :y 802}}
   {:command :curve-to :params {:c1x 368 :c1y 737 :c2x 310 :c2y 681 :x 264 :y 634}}
   {:command :curve-to :params {:c1x 218 :c1y 587 :c2x 181 :c2y 545 :x 154 :y 508}}
   {:command :curve-to :params {:c1x 126 :c1y 471 :c2x 107 :c2y 438 :x 96 :y 408}}
   {:command :curve-to :params {:c1x 85 :c1y 378 :c2x 80 :c2y 347 :x 80 :y 317}}
   {:command :curve-to :params {:c1x 80 :c1y 256 :c2x 100 :c2y 206 :x 140 :y 166}}
   {:command :curve-to :params {:c1x 180 :c1y 126 :c2x 230 :c2y 106 :x 290 :y 106}}
   {:command :curve-to :params {:c1x 328 :c1y 106 :c2x 363 :c2y 115 :x 395 :y 133}}
   {:command :curve-to :params {:c1x 427 :c1y 151 :c2x 456 :c2y 177 :x 480 :y 211}}
   {:command :curve-to :params {:c1x 508 :c1y 175 :c2x 537 :c2y 148 :x 569 :y 131}}
   {:command :curve-to :params {:c1x 600 :c1y 114 :c2x 634 :c2y 106 :x 670 :y 106}}
   {:command :curve-to :params {:c1x 729 :c1y 106 :c2x 779 :c2y 126 :x 819 :y 166}}
   {:command :curve-to :params {:c1x 859 :c1y 206 :c2x 880 :c2y 256 :x 880 :y 317}}
   {:command :curve-to :params {:c1x 880 :c1y 347 :c2x 874 :c2y 378 :x 863 :y 408}}
   {:command :curve-to :params {:c1x 852 :c1y 438 :c2x 833 :c2y 471 :x 806 :y 508}}
   {:command :curve-to :params {:c1x 778 :c1y 545 :c2x 741 :c2y 587 :x 695 :y 634}}
   {:command :curve-to :params {:c1x 649 :c1y 681 :c2x 591 :c2y 737 :x 521 :y 802}}
   {:command :line-to :params {:x 480 :y 839}}
   {:command :close-path :params {}}
   {:command :move-to :params {:x 480.0 :y 760.0}}
   {:command :curve-to :params {:c1x 547 :c1y 698 :c2x 603 :c2y 644 :x 646 :y 600}}
   {:command :curve-to :params {:c1x 690 :c1y 556 :c2x 724 :c2y 517 :x 750 :y 484}}
   {:command :curve-to :params {:c1x 776 :c1y 450 :c2x 794 :c2y 420 :x 804 :y 394}}
   {:command :curve-to :params {:c1x 814 :c1y 368 :c2x 820 :c2y 342 :x 820 :y 317}}
   {:command :curve-to :params {:c1x 820 :c1y 273 :c2x 806 :c2y 236 :x 778 :y 2085}}
   {:command :curve-to :params {:c1x 750 :c1y 180 :c2x 714 :c2y 166 :x 670 :y 1660}}
   {:command :curve-to :params {:c1x 635 :c1y 166 :c2x 604 :c2y 176 :x 574 :y 1975}}
   {:command :curve-to :params {:c1x 545 :c1y 218 :c2x 522 :c2y 248 :x 504 :y 2860}}
   {:command :line-to :params {:x 455 :y 286}}
   {:command :curve-to :params {:c1x 437 :c1y 248 :c2x 414 :c2y 219 :x 385 :y 198}}
   {:command :curve-to :params {:c1x 355 :c1y 176 :c2x 324 :c2y 166 :x 289 :y 166}}
   {:command :curve-to :params {:c1x 245 :c1y 166 :c2x 210 :c2y 180 :x 182 :y 208}}
   {:command :curve-to :params {:c1x 154 :c1y 236 :c2x 140 :c2y 273 :x 140 :y 317}}
   {:command :curve-to :params {:c1x 140 :c1y 343 :c2x 145 :c2y 369 :x 155 :y 395}}
   {:command :curve-to :params {:c1x 165 :c1y 421 :c2x 183 :c2y 451 :x 209 :y 485}}
   {:command :curve-to :params {:c1x 235 :c1y 519 :c2x 270 :c2y 558 :x 314 :y 602}}
   {:command :curve-to :params {:c1x 358 :c1y 646 :c2x 413 :c2y 698 :x 480 :y 760}}
   {:command :close-path :params {}}
   {:command :move-to :params {:x 480 :y 463}}
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



