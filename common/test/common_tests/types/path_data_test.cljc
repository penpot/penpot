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
   [app.common.types.path.bool :as path.bool]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.path.impl :as path.impl]
   [app.common.types.path.segment :as path.segment]
   [clojure.test :as t]))

(def sample-content
  [{:command :move-to :params {:x 480.0 :y 839.0}}
   {:command :line-to :params {:x 439.0 :y 802.0}}
   {:command :curve-to :params {:c1x 368.0 :c1y 737.0 :c2x 310.0 :c2y 681.0 :x 264.0 :y 634.0}}
   {:command :close-path :params {}}])

(def sample-content-square
  [{:command :move-to, :params {:x 0, :y 0}}
   {:command :line-to, :params {:x 10, :y 0}}
   {:command :line-to, :params {:x 10, :y 10}}
   {:command :line-to, :params {:x 10, :y 0}}
   {:command :line-to, :params {:x 0, :y 10}}
   {:command :line-to, :params {:x 0, :y 0}}
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
  [1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 -16 67 0 -64 81 68
   2 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 -128 -37 67 0 -128 72 68
   3 0 0 0 0 0 -72 67 0 64 56 68 0 0 -101 67 0 64 42 68 0 0 -124 67 0 -128 30 68
   4 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0])

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
              #?(:cljs (js/Int8Array. (.-buffer (.-buffer pdata)))
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
        expected (str "[\"~#penpot/path-data\",\"~bAQAAAAAAAAAAAAA"
                      "AAAAAAAAAAAAAAPBDAMBRRAIAAAAAAAAAAAAAAAAAAA"
                      "AAAAAAAIDbQwCASEQDAAAAAAC4QwBAOEQAAJtDAEAqR"
                      "AAAhEMAgB5EBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                      "AAAAAA==\"]")
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

(t/deftest path-transform-3
  (let [matrix  (gmt/rotate-matrix 42 (gpt/point 0 0))
        content (path/content sample-content-square)

        result1 (path/transform-content content matrix)
        result2 (transform-plain-content sample-content-square matrix)
        result3 (transform-plain-content content matrix)]

    (t/is (= (count result1) (count result2)))
    (doseq [[seg-a seg-b] (map vector result1 result2)]
      (t/is (= (:command seg-a)
               (:command seg-b)))

      (let [params-a (get seg-a :params)
            params-b (get seg-b :params)]

        (t/is (mth/close? (get params-a :x 0)
                          (get params-b :x 0)))
        (t/is (mth/close? (get params-a :y 0)
                          (get params-b :y 0)))))


    (doseq [[seg-a seg-b] (map vector result1 result3)]
      (t/is (= (:command seg-a)
               (:command seg-b)))

      (let [params-a (get seg-a :params)
            params-b (get seg-b :params)]

        (t/is (mth/close? (get params-a :x 0)
                          (get params-b :x 0)))
        (t/is (mth/close? (get params-a :y 0)
                          (get params-b :y 0)))))))



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

(defn calculate-extremities
  "Calculate extremities for the provided content.
  A legacy implementation used mainly as reference for testing"
  [content]
  (loop [points #{}
         from-p nil
         move-p nil
         content (seq content)]
    (if content
      (let [last-p (last content)
            content (if (= :move-to (:command last-p))
                      (butlast content)
                      content)
            command (first content)
            to-p    (path.helpers/segment->point command)

            [from-p move-p command-pts]
            (case (:command command)
              :move-to    [to-p   to-p   (when to-p [to-p])]
              :close-path [move-p move-p (when move-p [move-p])]
              :line-to    [to-p   move-p (when (and from-p to-p) [from-p to-p])]
              :curve-to   [to-p   move-p
                           (let [c1 (path.helpers/segment->point command :c1)
                                 c2 (path.helpers/segment->point command :c2)
                                 curve [from-p to-p c1 c2]]
                             (when (and from-p to-p c1 c2)
                               (into [from-p to-p]
                                     (->> (path.helpers/curve-extremities curve)
                                          (map #(path.helpers/curve-values curve %))))))]
              [to-p move-p []])]

        (recur (apply conj points command-pts) from-p move-p (next content)))
      points)))

(t/deftest extremities-1
  (let [pdata   (path/content sample-content)
        result1 (calculate-extremities sample-content)
        result2 (calculate-extremities pdata)
        result3 (path.segment/calculate-extremities sample-content)
        result4 (path.segment/calculate-extremities pdata)
        expect  #{(gpt/point 480.0 839.0)
                  (gpt/point 439.0 802.0)
                  (gpt/point 264.0 634.0)}
        n-iter  100000]

    (t/is (= result1 result3))
    (t/is (= result1 expect))
    (t/is (= result2 expect))
    (t/is (= result3 expect))
    (t/is (= result4 expect))))

(def sample-content-2
  [{:command :move-to, :params {:x 480.0, :y 839.0}}
   {:command :line-to, :params {:x 439.0, :y 802.0}}
   {:command :curve-to, :params {:c1x 368.0, :c1y 737.0, :c2x 310.0, :c2y 681.0, :x 4.0, :y 4.0}}
   {:command :curve-to, :params {:c1x 3.0, :c1y 7.0, :c2x 30.0, :c2y -68.0, :x 20.0, :y 20.0}}
   {:command :close-path :params {}}])

(t/deftest extremities-2
  (let [result1 (path.segment/calculate-extremities sample-content-2)
        result2 (calculate-extremities sample-content-2)]
    (t/is (= result1 result2))))

(t/deftest extremities-3
  (let [segments [{:command :move-to, :params {:x -310.5355224609375, :y 452.62115478515625}}]
        content  (path/content segments)
        result1  (calculate-extremities segments)
        result2  (path.segment/calculate-extremities segments)
        result3  (path.segment/calculate-extremities content)
        expect   #{}]
    (t/is (= result1 expect))
    (t/is (= result1 expect))
    (t/is (= result2 expect))
    (t/is (= result3 expect))))

(t/deftest points-to-content
  (let [initial  [(gpt/point 0.0 0.0)
                  (gpt/point 10.0 10.0)
                  (gpt/point 10.0 5.0)]
        content  (path.segment/points->content initial)
        segments (vec content)]
    (t/is (= 3 (count segments)))
    (t/is (= {:command :move-to, :params {:x 0.0, :y 0.0}} (nth segments 0)))
    (t/is (= {:command :line-to, :params {:x 10.0, :y 10.0}} (nth segments 1)))
    (t/is (= {:command :line-to, :params {:x 10.0, :y 5.0}} (nth segments 2)))))

(t/deftest get-segments
  (let [content (path/content sample-content-square)
        points  #{(gpt/point 10.0 0.0)
                  (gpt/point 0.0 0.0)}
        result  (path.segment/get-segments-with-points content points)
        expect  [{:command :line-to,
                  :params {:x 10.0, :y 0.0},
                  :start (gpt/point 0.0 0.0)
                  :end (gpt/point 10.0 0.0)
                  :index 1}
                 {:command :close-path,
                  :params {},
                  :start (gpt/point 0.0 0.0)
                  :end (gpt/point 0.0 0.0)
                  :index 6}]]

    (t/is (= result expect))))

(defn handler->point
  "A legacy impl of handler point, used as reference for test"
  [content index prefix]
  (when (and (some? index)
             (some? prefix))
    (when (and (<= 0 index)
               (< index (count content)))
      (let [segment (nth content index)
            params  (get segment :params)]
        (if (= :curve-to (:command segment))
          (let [[cx cy] (path.helpers/prefix->coords prefix)]
            (gpt/point (get params cx)
                       (get params cy)))
          (gpt/point (get params :x)
                     (get params :y)))))))

(t/deftest handler-to-point
  (let [content (path/content sample-content-2)
        result1 (handler->point content 3 :c1)
        result2 (handler->point content 1 :c1)
        result3 (handler->point content 0 :c1)

        expect1 (gpt/point 3.0 7.0)
        expect2 (gpt/point 439.0 802.0)
        expect3 (gpt/point 480.0 839.0)

        result4 (path.segment/get-handler-point content 3 :c1)
        result5 (path.segment/get-handler-point content 1 :c1)
        result6 (path.segment/get-handler-point content 0 :c1)]

    (t/is (= result1 expect1))
    (t/is (= result2 expect2))
    (t/is (= result3 expect3))
    (t/is (= result4 expect1))
    (t/is (= result5 expect2))
    (t/is (= result6 expect3))))

(defn get-handlers
  "Retrieve a map where for every point will retrieve a list of
  the handlers that are associated with that point.
  point -> [[index, prefix]].

  Legacy impl"
  [content]
  (->> (d/with-prev content)
       (d/enumerate)
       (mapcat (fn [[index [cur-segment pre-segment]]]
                 (if (and pre-segment (= :curve-to (:command cur-segment)))
                   (let [cur-pos (path.helpers/segment->point cur-segment)
                         pre-pos (path.helpers/segment->point pre-segment)]
                     (-> [[pre-pos [index :c1]]
                          [cur-pos [index :c2]]]))
                   [])))

       (group-by first)
       (d/mapm #(mapv second %2))))

(t/deftest content-to-handlers
  (let [content (path/content sample-content-large)
        result1 (get-handlers sample-content-large)
        result2 (path.segment/get-handlers content)]
    (t/is (= result1 result2))))


(def contents-for-bool
  [[{:command :move-to, :params {:x 1682.9000244140625, :y 48.0}}
    {:command :line-to, :params {:x 1682.9000244140625, :y 44.0}}
    {:command :curve-to, :params {:x 1683.9000244140625, :y 43.0, :c1x 1682.9000244140625, :c1y 43.400001525878906, :c2x 1683.300048828125, :c2y 43.0}}
    {:command :line-to, :params {:x 1687.9000244140625, :y 43.0}}
    {:command :curve-to, :params {:x 1688.9000244140625, :y 44.0, :c1x 1688.5, :c1y 43.0, :c2x 1688.9000244140625, :c2y 43.400001525878906}}
    {:command :line-to, :params {:x 1688.9000244140625, :y 48.0}}
    {:command :curve-to, :params {:x 1687.9000244140625, :y 49.0, :c1x 1688.9000244140625, :c1y 48.599998474121094, :c2x 1688.5, :c2y 49.0}}
    {:command :line-to, :params {:x 1683.9000244140625, :y 49.0}}
    {:command :curve-to, :params {:x 1682.9000244140625, :y 48.0, :c1x 1683.300048828125, :c1y 49.0, :c2x 1682.9000244140625, :c2y 48.599998474121094}}
    {:command :close-path, :params {}}
    {:command :close-path, :params {}}
    {:command :move-to, :params {:x 1684.9000244140625, :y 45.0}}
    {:command :line-to, :params {:x 1684.9000244140625, :y 47.0}}
    {:command :line-to, :params {:x 1686.9000244140625, :y 47.0}}
    {:command :line-to, :params {:x 1686.9000244140625, :y 45.0}}
    {:command :line-to, :params {:x 1684.9000244140625, :y 45.0}}
    {:command :close-path, :params {}}
    {:command :close-path, :params {}}]

   [{:command :move-to, :params {:x 1672.9000244140625, :y 48.0}}
    {:command :line-to, :params {:x 1672.9000244140625, :y 44.0}}
    {:command :curve-to, :params {:x 1673.9000244140625, :y 43.0, :c1x 1672.9000244140625, :c1y 43.400001525878906, :c2x 1673.300048828125, :c2y 43.0}}
    {:command :line-to, :params {:x 1677.9000244140625, :y 43.0}}
    {:command :curve-to, :params {:x 1678.9000244140625, :y 44.0, :c1x 1678.5, :c1y 43.0, :c2x 1678.9000244140625, :c2y 43.400001525878906}}
    {:command :line-to, :params {:x 1678.9000244140625, :y 48.0}}
    {:command :curve-to, :params {:x 1677.9000244140625, :y 49.0, :c1x 1678.9000244140625, :c1y 48.599998474121094, :c2x 1678.5, :c2y 49.0}}
    {:command :line-to, :params {:x 1673.9000244140625, :y 49.0}}
    {:command :curve-to, :params {:x 1672.9000244140625, :y 48.0, :c1x 1673.300048828125, :c1y 49.0, :c2x 1672.9000244140625, :c2y 48.599998474121094}}
    {:command :close-path, :params {}}
    {:command :close-path, :params {}}
    {:command :move-to, :params {:x 1674.9000244140625, :y 45.0}}
    {:command :line-to, :params {:x 1674.9000244140625, :y 47.0}}
    {:command :line-to, :params {:x 1676.9000244140625, :y 47.0}}
    {:command :line-to, :params {:x 1676.9000244140625, :y 45.0}}
    {:command :line-to, :params {:x 1674.9000244140625, :y 45.0}}
    {:command :close-path, :params {}}
    {:command :close-path, :params {}}]])

(def bool-result
  [{:command :move-to, :params {:x 1682.9000244140625, :y 48.0}}
   {:command :line-to, :params {:x 1682.9000244140625, :y 44.0}}
   {:command :curve-to,
    :params
    {:x 1683.9000244140625, :y 43.0, :c1x 1682.9000244140625, :c1y 43.400001525878906, :c2x 1683.300048828125, :c2y 43.0}}
   {:command :line-to, :params {:x 1687.9000244140625, :y 43.0}}
   {:command :curve-to,
    :params {:x 1688.9000244140625, :y 44.0, :c1x 1688.5, :c1y 43.0, :c2x 1688.9000244140625, :c2y 43.400001525878906}}
   {:command :line-to, :params {:x 1688.9000244140625, :y 48.0}}
   {:command :curve-to,
    :params {:x 1687.9000244140625, :y 49.0, :c1x 1688.9000244140625, :c1y 48.599998474121094, :c2x 1688.5, :c2y 49.0}}
   {:command :line-to, :params {:x 1683.9000244140625, :y 49.0}}
   {:command :curve-to,
    :params
    {:x 1682.9000244140625, :y 48.0, :c1x 1683.300048828125, :c1y 49.0, :c2x 1682.9000244140625, :c2y 48.599998474121094}}
   {:command :move-to, :params {:x 1684.9000244140625, :y 45.0}}
   {:command :line-to, :params {:x 1684.9000244140625, :y 47.0}}
   {:command :line-to, :params {:x 1686.9000244140625, :y 47.0}}
   {:command :line-to, :params {:x 1686.9000244140625, :y 45.0}}
   {:command :line-to, :params {:x 1684.9000244140625, :y 45.0}}
   {:command :move-to, :params {:x 1672.9000244140625, :y 48.0}}
   {:command :line-to, :params {:x 1672.9000244140625, :y 44.0}}
   {:command :curve-to,
    :params
    {:x 1673.9000244140625, :y 43.0, :c1x 1672.9000244140625, :c1y 43.400001525878906, :c2x 1673.300048828125, :c2y 43.0}}
   {:command :line-to, :params {:x 1677.9000244140625, :y 43.0}}
   {:command :curve-to,
    :params {:x 1678.9000244140625, :y 44.0, :c1x 1678.5, :c1y 43.0, :c2x 1678.9000244140625, :c2y 43.400001525878906}}
   {:command :line-to, :params {:x 1678.9000244140625, :y 48.0}}
   {:command :curve-to,
    :params {:x 1677.9000244140625, :y 49.0, :c1x 1678.9000244140625, :c1y 48.599998474121094, :c2x 1678.5, :c2y 49.0}}
   {:command :line-to, :params {:x 1673.9000244140625, :y 49.0}}
   {:command :curve-to,
    :params
    {:x 1672.9000244140625, :y 48.0, :c1x 1673.300048828125, :c1y 49.0, :c2x 1672.9000244140625, :c2y 48.599998474121094}}
   {:command :move-to, :params {:x 1674.9000244140625, :y 45.0}}
   {:command :line-to, :params {:x 1674.9000244140625, :y 47.0}}
   {:command :line-to, :params {:x 1676.9000244140625, :y 47.0}}
   {:command :line-to, :params {:x 1676.9000244140625, :y 45.0}}
   {:command :line-to, :params {:x 1674.9000244140625, :y 45.0}}])

(t/deftest calculate-bool-content
  (let [result (path.bool/calculate-content :union contents-for-bool)]
    (t/is (= result bool-result))))
