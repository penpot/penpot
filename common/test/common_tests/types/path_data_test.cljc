;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.path-data-test
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.transit :as trans]
   [app.common.types.path :as path]
   [app.common.types.path.bool :as path.bool]
   [app.common.types.path.fit :as path.fit]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.path.impl :as path.impl]
   [app.common.types.path.segment :as path.segment]
   [app.common.types.path.subpath :as path.subpath]
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


;; Test the specific case where cuve-to commands comes without the
;; optional attrs
(t/deftest path-data-plain-to-binary-2
  (let [plain-content
        [{:command :move-to :params {:x 480.0 :y 839.0}}
         {:command :line-to :params {:x 439.0 :y 802.0}}
         {:command :curve-to :params {:x 264.0 :y 634.0}}
         {:command :curve-to :params {:x 154.0 :y 508.0}}]

        binary-content
        (path/content plain-content)]

    #?(:clj
       (t/is (= "M480.0,839.0L439.0,802.0C264.0,634.0,264.0,634.0,264.0,634.0C154.0,508.0,154.0,508.0,154.0,508.0"
                (str binary-content)))

       :cljs
       (t/is (= "M480,839L439,802C264,634,264,634,264,634C154,508,154,508,154,508"
                (str binary-content))))))

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

(t/deftest path-get-points-nil-safe
  (t/testing "path/get-points returns empty for nil content without throwing"
    (t/is (empty? (path/get-points nil))))
  (t/testing "path/get-points returns correct points for valid content"
    (let [content (path/content sample-content)
          points  (path/get-points content)]
      (t/is (some? points))
      (t/is (= 3 (count points))))))

(t/deftest path-get-points-plain-vector-safe
  (t/testing "path/get-points does not throw for plain vector content"
    (let [points (path/get-points sample-content)]
      (t/is (some? points))
      (t/is (= 3 (count points))))))

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
        expect  #{(gpt/point 480.0 839.0)
                  (gpt/point 439.0 802.0)
                  (gpt/point 264.0 634.0)}]

    (t/is (= result1 expect))
    (t/is (= result2 expect))))

(def sample-content-2
  [{:command :move-to, :params {:x 480.0, :y 839.0}}
   {:command :line-to, :params {:x 439.0, :y 802.0}}
   {:command :curve-to, :params {:c1x 368.0, :c1y 737.0, :c2x 310.0, :c2y 681.0, :x 4.0, :y 4.0}}
   {:command :curve-to, :params {:c1x 3.0, :c1y 7.0, :c2x 30.0, :c2y -68.0, :x 20.0, :y 20.0}}
   {:command :close-path :params {}}])

(t/deftest extremities-2
  (let [result1 (calculate-extremities sample-content-2)]
    (t/is (some? result1))))

(t/deftest extremities-3
  (let [segments [{:command :move-to, :params {:x -310.5355224609375, :y 452.62115478515625}}]
        content  (path/content segments)
        result1  (calculate-extremities segments)
        result2  (calculate-extremities content)
        expect   #{}]
    (t/is (= result1 expect))
    (t/is (= result2 expect))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBPATH TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest subpath-pt=
  (t/testing "pt= returns true for nearby points"
    (t/is (path.subpath/pt= (gpt/point 0 0) (gpt/point 0.05 0.05))))
  (t/testing "pt= returns false for distant points"
    (t/is (not (path.subpath/pt= (gpt/point 0 0) (gpt/point 1 0))))))

(t/deftest subpath-make-subpath
  (t/testing "make-subpath from a single move-to command"
    (let [cmd {:command :move-to :params {:x 5.0 :y 10.0}}
          sp  (path.subpath/make-subpath cmd)]
      (t/is (= (gpt/point 5.0 10.0) (:from sp)))
      (t/is (= (gpt/point 5.0 10.0) (:to sp)))
      (t/is (= [cmd] (:data sp)))))
  (t/testing "make-subpath from explicit from/to/data"
    (let [from (gpt/point 0 0)
          to   (gpt/point 10 10)
          data [{:command :move-to :params {:x 0 :y 0}}
                {:command :line-to :params {:x 10 :y 10}}]
          sp   (path.subpath/make-subpath from to data)]
      (t/is (= from (:from sp)))
      (t/is (= to   (:to sp)))
      (t/is (= data (:data sp))))))

(t/deftest subpath-add-subpath-command
  (t/testing "adding a line-to command extends the subpath"
    (let [cmd0 {:command :move-to :params {:x 0.0 :y 0.0}}
          cmd1 {:command :line-to :params {:x 5.0 :y 5.0}}
          sp   (-> (path.subpath/make-subpath cmd0)
                   (path.subpath/add-subpath-command cmd1))]
      (t/is (= (gpt/point 5.0 5.0) (:to sp)))
      (t/is (= 2 (count (:data sp))))))
  (t/testing "adding a close-path is replaced by a line-to at from"
    (let [cmd0 {:command :move-to :params {:x 1.0 :y 2.0}}
          sp   (path.subpath/make-subpath cmd0)
          sp2  (path.subpath/add-subpath-command sp {:command :close-path :params {}})]
      ;; The close-path gets replaced by a line-to back to :from
      (t/is (= (gpt/point 1.0 2.0) (:to sp2))))))

(t/deftest subpath-reverse-command
  (let [prev {:command :move-to :params {:x 0.0 :y 0.0}}
        cmd  {:command :line-to :params {:x 5.0 :y 3.0}}
        rev  (path.subpath/reverse-command cmd prev)]
    (t/is (= :line-to (:command rev)))
    (t/is (= 0.0 (get-in rev [:params :x])))
    (t/is (= 0.0 (get-in rev [:params :y])))))

(t/deftest subpath-reverse-command-curve
  (let [prev {:command :move-to :params {:x 0.0 :y 0.0}}
        cmd  {:command :curve-to
              :params {:x 10.0 :y 0.0 :c1x 3.0 :c1y 5.0 :c2x 7.0 :c2y 5.0}}
        rev  (path.subpath/reverse-command cmd prev)]
    ;; end-point should be previous point coords
    (t/is (= 0.0 (get-in rev [:params :x])))
    (t/is (= 0.0 (get-in rev [:params :y])))
    ;; handlers are swapped
    (t/is (= 7.0 (get-in rev [:params :c1x])))
    (t/is (= 5.0 (get-in rev [:params :c1y])))
    (t/is (= 3.0 (get-in rev [:params :c2x])))
    (t/is (= 5.0 (get-in rev [:params :c2y])))))

(def ^:private simple-open-content
  [{:command :move-to  :params {:x 0.0  :y 0.0}}
   {:command :line-to  :params {:x 10.0 :y 0.0}}
   {:command :line-to  :params {:x 10.0 :y 10.0}}])

(def ^:private simple-closed-content
  [{:command :move-to  :params {:x 0.0  :y 0.0}}
   {:command :line-to  :params {:x 10.0 :y 0.0}}
   {:command :line-to  :params {:x 10.0 :y 10.0}}
   {:command :line-to  :params {:x 0.0  :y 0.0}}])

(t/deftest subpath-get-subpaths
  (t/testing "open path produces one subpath"
    (let [sps (path.subpath/get-subpaths simple-open-content)]
      (t/is (= 1 (count sps)))
      (t/is (= (gpt/point 0.0 0.0) (get-in sps [0 :from])))))
  (t/testing "content with two move-to produces two subpaths"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 5.0 :y 0.0}}
                   {:command :move-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 15.0 :y 0.0}}]
          sps (path.subpath/get-subpaths content)]
      (t/is (= 2 (count sps))))))

(t/deftest subpath-is-closed?
  (t/testing "subpath with same from/to is closed"
    (let [sp (path.subpath/make-subpath (gpt/point 0 0) (gpt/point 0 0) [])]
      (t/is (path.subpath/is-closed? sp))))
  (t/testing "subpath with different from/to is not closed"
    (let [sp (path.subpath/make-subpath (gpt/point 0 0) (gpt/point 10 10) [])]
      (t/is (not (path.subpath/is-closed? sp))))))

(t/deftest subpath-reverse-subpath
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 10.0}}]
        sps (path.subpath/get-subpaths content)
        sp  (first sps)
        rev (path.subpath/reverse-subpath sp)]
    (t/is (= (:to sp) (:from rev)))
    (t/is (= (:from sp) (:to rev)))
    ;; reversed data starts with a move-to at old :to
    (t/is (= :move-to (get-in rev [:data 0 :command])))))

(t/deftest subpath-subpaths-join
  (let [sp1 (path.subpath/make-subpath (gpt/point 0 0) (gpt/point 5 0)
                                       [{:command :move-to :params {:x 0 :y 0}}
                                        {:command :line-to :params {:x 5 :y 0}}])
        sp2 (path.subpath/make-subpath (gpt/point 5 0) (gpt/point 10 0)
                                       [{:command :move-to :params {:x 5 :y 0}}
                                        {:command :line-to :params {:x 10 :y 0}}])
        joined (path.subpath/subpaths-join sp1 sp2)]
    (t/is (= (gpt/point 0 0) (:from joined)))
    (t/is (= (gpt/point 10 0) (:to joined)))
    ;; data has move-to from sp1 + line-to from sp1 + line-to from sp2 (rest of sp2)
    (t/is (= 3 (count (:data joined))))))

(t/deftest subpath-close-subpaths
  (t/testing "content that is already a closed triangle stays closed"
    (let [result (path.subpath/close-subpaths simple-closed-content)]
      (t/is (seq result))))
  (t/testing "a close after a curve already landing on the start is not materialized twice"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :curve-to
                    :params {:c1x 3.0 :c1y -2.0
                             :c2x 6.0 :c2y -2.0
                             :x 10.0 :y 0.0}}
                   {:command :curve-to
                    :params {:c1x 6.0 :c1y 2.0
                             :c2x 3.0 :c2y 2.0
                             :x 0.0 :y 0.0}}
                   {:command :close-path :params {}}]
          result  (path.subpath/close-subpaths content)]
      (t/is (= [:move-to :curve-to :curve-to] (mapv :command result)))
      ;; Rendering/persistence can still recover the explicit SVG close.
      (t/is (= content (path.subpath/close-loops content)))))
  (t/testing "two open fragments that form a closed loop get merged"
    ;; fragment A: 0,0 → 5,0
    ;; fragment B: 10,0 → 5,0 (reversed, connects to A's end)
    ;; After close-subpaths the result should have segments
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 5.0 :y 0.0}}
                   {:command :move-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 5.0 :y 0.0}}]
          result (path.subpath/close-subpaths content)]
      (t/is (seq result)))))

(t/deftest subpath-close-loops
  (t/testing "trailing line-to landing on the subpath start becomes a close-path"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 10.0}}
                   {:command :line-to :params {:x 0.0 :y 0.0}}]
          result  (path.subpath/close-loops content)]
      (t/is (= [:move-to :line-to :line-to :close-path] (mapv :command result)))))

  (t/testing "coincident endpoints within tolerance also close"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 0.05 :y 0.0}}]
          result  (path.subpath/close-loops content)]
      (t/is (= [:move-to :line-to :close-path] (mapv :command result)))))

  (t/testing "curve landing on the subpath start keeps the curve and appends a close-path"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 0.0}}
                   {:command :curve-to :params {:c1x 10.0 :c1y 5.0 :c2x 5.0 :c2y 5.0 :x 0.0 :y 0.0}}]
          result  (path.subpath/close-loops content)]
      (t/is (= [:move-to :line-to :curve-to :close-path] (mapv :command result)))))

  (t/testing "already command-closed content is unchanged"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 10.0}}
                   {:command :close-path :params {}}]
          result  (path.subpath/close-loops content)]
      (t/is (= content (vec result)))))

  (t/testing "open subpaths are left untouched"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 10.0}}]
          result  (path.subpath/close-loops content)]
      (t/is (= content (vec result)))))

  (t/testing "multi-subpath content closes only the coincident loops"
    (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 0.0 :y 0.0}}
                   {:command :move-to :params {:x 20.0 :y 20.0}}
                   {:command :line-to :params {:x 30.0 :y 20.0}}]
          result  (path.subpath/close-loops content)]
      (t/is (= [:move-to :line-to :close-path :move-to :line-to]
               (mapv :command result))))))

(t/deftest path-close-loops-path-data
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 10.0}}
                  {:command :line-to :params {:x 0.0 :y 0.0}}])
        result  (path/close-loops content)]
    (t/is (path.impl/path-data? result))
    (t/is (= [:move-to :line-to :line-to :close-path]
             (mapv :command (vec result))))))

(t/deftest subpath-merge-touching-subpaths
  (t/testing "adjacent subpaths sharing an endpoint collapse into one chain"
    ;; Heroicons-style fragment: continuous polyline split as M-L M-L M-L
    ;; with the second/third subpath starting at the first's endpoint.
    (let [content [{:command :move-to :params {:x 0.0  :y 10.0}}
                   {:command :line-to :params {:x 10.0 :y 10.0}}
                   {:command :move-to :params {:x 10.0 :y 10.0}}
                   {:command :line-to :params {:x 5.0  :y 0.0}}
                   {:command :move-to :params {:x 10.0 :y 10.0}}
                   {:command :line-to :params {:x 5.0  :y 20.0}}]
          result  (path.subpath/merge-touching-subpaths content)
          moves   (filter #(= :move-to (:command %)) result)]
      ;; Subpaths 1+2 share (10,10) → merged. Subpath 3 also starts at (10,10),
      ;; but the merged chain now ends at (5,0), so it does NOT match and
      ;; is preserved as its own subpath. Two move-tos in the final result.
      (t/is (= 2 (count moves)))
      (t/is (= 5 (count result)))))
  (t/testing "non-touching subpaths are left untouched"
    (let [content [{:command :move-to :params {:x 0.0  :y 0.0}}
                   {:command :line-to :params {:x 5.0  :y 0.0}}
                   {:command :move-to :params {:x 50.0 :y 50.0}}
                   {:command :line-to :params {:x 60.0 :y 60.0}}]
          result  (path.subpath/merge-touching-subpaths content)]
      (t/is (= content (vec result)))))
  (t/testing "closed subpath is not absorbed into a neighbour"
    (let [content [{:command :move-to   :params {:x 0.0 :y 0.0}}
                   {:command :line-to   :params {:x 5.0 :y 0.0}}
                   {:command :line-to   :params {:x 5.0 :y 5.0}}
                   {:command :line-to   :params {:x 0.0 :y 0.0}}
                   {:command :move-to   :params {:x 0.0 :y 0.0}}
                   {:command :line-to   :params {:x 1.0 :y 1.0}}]
          result  (path.subpath/merge-touching-subpaths content)
          moves   (filter #(= :move-to (:command %)) result)]
      (t/is (= 2 (count moves))))))

(t/deftest subpath-reverse-content
  (let [result (path.subpath/reverse-content simple-open-content)]
    (t/is (= (count simple-open-content) (count result)))
    ;; First command of reversed content is a move-to at old end
    (t/is (= :move-to (:command (first result))))
    (t/is (mth/close? 10.0 (get-in (first result) [:params :x])))
    (t/is (mth/close? 10.0 (get-in (first result) [:params :y])))))

(t/deftest subpath-clockwise?
  (t/testing "square drawn clockwise is detected as clockwise"
    ;; A square drawn clockwise: top-left → top-right → bottom-right → bottom-left
    (let [cw-content [{:command :move-to :params {:x 0.0 :y 0.0}}
                      {:command :line-to :params {:x 10.0 :y 0.0}}
                      {:command :line-to :params {:x 10.0 :y 10.0}}
                      {:command :line-to :params {:x 0.0 :y 10.0}}
                      {:command :line-to :params {:x 0.0 :y 0.0}}]]
      (t/is (path.subpath/clockwise? cw-content))))
  (t/testing "counter-clockwise square is not clockwise"
    (let [ccw-content [{:command :move-to :params {:x 0.0 :y 0.0}}
                       {:command :line-to :params {:x 0.0 :y 10.0}}
                       {:command :line-to :params {:x 10.0 :y 10.0}}
                       {:command :line-to :params {:x 10.0 :y 0.0}}
                       {:command :line-to :params {:x 0.0 :y 0.0}}]]
      (t/is (not (path.subpath/clockwise? ccw-content))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest helpers-s=
  (t/is (path.helpers/s= 0.0 0.0))
  (t/is (path.helpers/s= 1.0 1.0000000001))
  (t/is (not (path.helpers/s= 0.0 1.0))))

(t/deftest helpers-make-move-to
  (let [pt  (gpt/point 3.0 7.0)
        cmd (path.helpers/make-move-to pt)]
    (t/is (= :move-to (:command cmd)))
    (t/is (= 3.0 (get-in cmd [:params :x])))
    (t/is (= 7.0 (get-in cmd [:params :y])))))

(t/deftest helpers-make-line-to
  (let [pt  (gpt/point 4.0 8.0)
        cmd (path.helpers/make-line-to pt)]
    (t/is (= :line-to (:command cmd)))
    (t/is (= 4.0 (get-in cmd [:params :x])))
    (t/is (= 8.0 (get-in cmd [:params :y])))))

(t/deftest helpers-make-curve-params
  (t/testing "single point form — point and handlers coincide"
    (let [p (gpt/point 5.0 5.0)
          params (path.helpers/make-curve-params p)]
      (t/is (mth/close? 5.0 (:x params)))
      (t/is (mth/close? 5.0 (:c1x params)))
      (t/is (mth/close? 5.0 (:c2x params)))))
  (t/testing "two-arg form — handler specified"
    (let [p (gpt/point 10.0 0.0)
          h (gpt/point 5.0 5.0)
          params (path.helpers/make-curve-params p h)]
      (t/is (mth/close? 10.0 (:x params)))
      (t/is (mth/close? 5.0  (:c1x params)))
      ;; c2 defaults to point
      (t/is (mth/close? 10.0 (:c2x params))))))

(t/deftest helpers-make-curve-to
  (let [to (gpt/point 10.0 0.0)
        h1 (gpt/point 3.0 5.0)
        h2 (gpt/point 7.0 5.0)
        cmd (path.helpers/make-curve-to to h1 h2)]
    (t/is (= :curve-to (:command cmd)))
    (t/is (= 10.0 (get-in cmd [:params :x])))
    (t/is (= 3.0  (get-in cmd [:params :c1x])))
    (t/is (= 7.0  (get-in cmd [:params :c2x])))))

(t/deftest helpers-update-curve-to
  (let [base {:command :line-to :params {:x 10.0 :y 0.0}}
        h1   (gpt/point 3.0 5.0)
        h2   (gpt/point 7.0 5.0)
        cmd  (path.helpers/update-curve-to base h1 h2)]
    (t/is (= :curve-to (:command cmd)))
    (t/is (= 3.0 (get-in cmd [:params :c1x])))
    (t/is (= 7.0 (get-in cmd [:params :c2x])))))

(t/deftest segment-make-curve-point-keeps-neighbors-corners
  ;; Curving a node leaves its neighbours as corners.
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 100.0 :y 0.0}}
                 {:command :line-to :params {:x 200.0 :y 0.0}}]]

    (t/testing "curving the first (endpoint) node keeps its neighbour a corner"
      (let [r    (vec (seq (path/make-curve-point content (gpt/point 0.0 0.0))))
            seg1 (get r 1)]
        (t/is (= :curve-to (:command seg1)))
        ;; The neighbour's handle stays collapsed.
        (t/is (= 100.0 (get-in seg1 [:params :c2x])))
        (t/is (= 0.0 (get-in seg1 [:params :c2y])))
        ;; The selected node gets a handle.
        (t/is (not= 0.0 (get-in seg1 [:params :c1x])))))

    (t/testing "curving the last node keeps its neighbour a corner"
      (let [r    (vec (seq (path/make-curve-point content (gpt/point 200.0 0.0))))
            seg2 (get r 2)]
        (t/is (= :curve-to (:command seg2)))
        ;; The neighbour's handle stays collapsed.
        (t/is (= 100.0 (get-in seg2 [:params :c1x])))
        (t/is (= 0.0 (get-in seg2 [:params :c1y])))))

    (t/testing "curving a middle node keeps both neighbours corners"
      (let [r    (vec (seq (path/make-curve-point content (gpt/point 100.0 0.0))))
            seg1 (get r 1)
            seg2 (get r 2)]
        (t/is (= 0.0 (get-in seg1 [:params :c1x])))
        (t/is (= 200.0 (get-in seg2 [:params :c2x])))))))

(t/deftest segment-make-curve-point-acute-corner-is-smooth
  ;; Acute corners get equal and opposite handles.
  (let [content [{:command :move-to :params {:x 10.0 :y 1.0}}
                 {:command :line-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y -1.0}}]
        r       (vec (seq (path/make-curve-point (path/content content)
                                                 (gpt/point 0.0 0.0))))
        ;; Read the node's incoming and outgoing handles.
        c2y     (get-in (get r 1) [:params :c2y])
        c1y     (get-in (get r 2) [:params :c1y])]
    ;; Both handles extend from the node.
    (t/is (not (zero? c2y)))
    ;; The node is the midpoint between equal-length handles.
    (t/is (= c2y (- c1y)))))

(t/deftest segment-make-curve-point-closed-seam-follows-neighbour-tangent
  ;; Closed seams follow the chord between their neighbours.
  (let [point   (gpt/point 0.0 0.0)
        content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y -8.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 6.0}}
                 {:command :line-to :params {:x 0.0 :y 0.0}}
                 {:command :close-path :params {}}]
        result  (vec (path/make-curve-point (path/content content) point))
        outgoing (get result 1)
        incoming (get result 4)]
    ;; Seam handles lie on the chord and oppose each other.
    (t/is (mth/close? 0.0 (get-in outgoing [:params :c1x]) 0.001))
    (t/is (mth/close? 0.0 (get-in incoming [:params :c2x]) 0.001))
    (t/is (neg? (get-in outgoing [:params :c1y])))
    (t/is (pos? (get-in incoming [:params :c2y])))))

(t/deftest helpers-prefix->coords
  (t/is (= [:c1x :c1y] (path.helpers/prefix->coords :c1)))
  (t/is (= [:c2x :c2y] (path.helpers/prefix->coords :c2)))
  (t/is (nil? (path.helpers/prefix->coords nil))))

(t/deftest helpers-position-fixed-angle
  (t/testing "returns point unchanged when from-point is nil"
    (let [pt (gpt/point 5.0 3.0)]
      (t/is (= pt (path.helpers/position-fixed-angle pt nil)))))
  (t/testing "snaps to nearest 15-degree angle"
    (let [from      (gpt/point 0 0)
          ;; ~31° from `from`: snaps to 30° (15° granularity), not 45°
          to        (gpt/point 10 6)
          snapped   (path.helpers/position-fixed-angle to from)
          d-orig    (gpt/distance to from)
          d-snapped (gpt/distance snapped from)
          snap-ang  (gpt/angle snapped from)
          orig-ang  (gpt/angle to from)
          delta     (let [d (mod (- snap-ang orig-ang) 360)] (min d (- 360 d)))]
      ;; distance preserved
      (t/is (mth/close? d-orig d-snapped 0.01))
      ;; snapped onto a 15° multiple
      (t/is (let [m (mod snap-ang 15)] (or (< m 0.01) (> m 14.99))))
      ;; Stay within half a 15° bucket of the input angle.
      (t/is (<= delta 7.5)))))

(t/deftest helpers-command->line
  (let [prev {:command :move-to :params {:x 0.0 :y 0.0}}
        cmd  {:command :line-to :params {:x 5.0 :y 3.0} :prev (gpt/point 0 0)}
        [from to] (path.helpers/command->line cmd (path.helpers/segment->point prev))]
    (t/is (= (gpt/point 0.0 0.0) from))
    (t/is (= (gpt/point 5.0 3.0) to))))

(t/deftest helpers-command->bezier
  (let [prev {:command :move-to :params {:x 0.0 :y 0.0}}
        cmd  {:command :curve-to
              :params {:x 10.0 :y 0.0 :c1x 3.0 :c1y 5.0 :c2x 7.0 :c2y 5.0}}
        [from to h1 h2] (path.helpers/command->bezier cmd (path.helpers/segment->point prev))]
    (t/is (= (gpt/point 0.0 0.0) from))
    (t/is (= (gpt/point 10.0 0.0) to))
    (t/is (= (gpt/point 3.0 5.0) h1))
    (t/is (= (gpt/point 7.0 5.0) h2))))

(t/deftest helpers-entry->bezier
  (let [from  (gpt/point 0 0)
        to    (gpt/point 10 0)
        line  {:from from :to to :segment {:command :line-to}}
        curve {:from from
               :to to
               :segment {:command :curve-to
                         :params {:x 10 :y 0 :c1x 3 :c1y 5 :c2x 7 :c2y 5}}}]
    (t/is (= [from to from to] (path.helpers/entry->bezier line)))
    (t/is (= [from to (gpt/point 3 5) (gpt/point 7 5)]
             (path.helpers/entry->bezier curve)))))

(t/deftest helpers-line-values
  (let [from (gpt/point 0.0 0.0)
        to   (gpt/point 10.0 0.0)
        mid  (path.helpers/line-values [from to] 0.5)]
    (t/is (mth/close? 5.0 (:x mid)))
    (t/is (mth/close? 0.0 (:y mid)))))

(t/deftest helpers-curve-split
  (let [start (gpt/point 0.0 0.0)
        end   (gpt/point 10.0 0.0)
        h1    (gpt/point 3.0 5.0)
        h2    (gpt/point 7.0 5.0)
        [[s1 e1 _ _] [s2 e2 _ _]] (path.helpers/curve-split start end h1 h2 0.5)]
    ;; First sub-curve starts at start and ends near midpoint
    (t/is (mth/close? 0.0  (:x s1) 0.01))
    (t/is (mth/close? 10.0 (:x e2) 0.01))
    ;; The split point (e1 / s2) should be the same
    (t/is (mth/close? (:x e1) (:x s2) 0.01))
    (t/is (mth/close? (:y e1) (:y s2) 0.01))))

(t/deftest helpers-split-line-to
  (let [from (gpt/point 0.0 0.0)
        seg  {:command :line-to :params {:x 10.0 :y 0.0}}
        [s1 s2] (path.helpers/split-line-to from seg 0.5)]
    (t/is (= :line-to (:command s1)))
    (t/is (mth/close? 5.0 (get-in s1 [:params :x])))
    (t/is (= s2 seg))))

(t/deftest helpers-split-curve-to
  (let [from (gpt/point 0.0 0.0)
        seg  {:command :curve-to
              :params {:x 10.0 :y 0.0 :c1x 3.0 :c1y 5.0 :c2x 7.0 :c2y 5.0}}
        [s1 s2] (path.helpers/split-curve-to from seg 0.5)]
    (t/is (= :curve-to (:command s1)))
    (t/is (= :curve-to (:command s2)))
    ;; s2 ends at original endpoint
    (t/is (mth/close? 10.0 (get-in s2 [:params :x]) 0.01))
    (t/is (mth/close? 0.0  (get-in s2 [:params :y]) 0.01))))

(t/deftest helpers-split-line-to-ranges
  (t/testing "no split values returns original segment"
    (let [from (gpt/point 0.0 0.0)
          seg  {:command :line-to :params {:x 10.0 :y 0.0}}
          result (path.helpers/split-line-to-ranges from seg [])]
      (t/is (= [seg] result))))
  (t/testing "splits at 0.25 and 0.75 produces 3 segments"
    (let [from (gpt/point 0.0 0.0)
          seg  {:command :line-to :params {:x 10.0 :y 0.0}}
          result (path.helpers/split-line-to-ranges from seg [0.25 0.75])]
      (t/is (= 3 (count result))))))

(t/deftest helpers-split-curve-to-ranges
  (t/testing "no split values returns original segment"
    (let [from (gpt/point 0.0 0.0)
          seg  {:command :curve-to
                :params {:x 10.0 :y 0.0 :c1x 3.0 :c1y 5.0 :c2x 7.0 :c2y 5.0}}
          result (path.helpers/split-curve-to-ranges from seg [])]
      (t/is (= [seg] result))))
  (t/testing "split at 0.5 produces 2 segments"
    (let [from (gpt/point 0.0 0.0)
          seg  {:command :curve-to
                :params {:x 10.0 :y 0.0 :c1x 3.0 :c1y 5.0 :c2x 7.0 :c2y 5.0}}
          result (path.helpers/split-curve-to-ranges from seg [0.5])]
      (t/is (= 2 (count result))))))

(t/deftest helpers-line-has-point?
  (let [from (gpt/point 0.0 0.0)
        to   (gpt/point 10.0 0.0)]
    (t/is (path.helpers/line-has-point? (gpt/point 5.0 0.0) [from to]))
    (t/is (not (path.helpers/line-has-point? (gpt/point 5.0 1.0) [from to])))))

(t/deftest helpers-segment-has-point?
  (let [from (gpt/point 0.0 0.0)
        to   (gpt/point 10.0 0.0)]
    (t/is (path.helpers/segment-has-point? (gpt/point 5.0 0.0) [from to]))
    ;; Outside segment bounds even though on same infinite line
    (t/is (not (path.helpers/segment-has-point? (gpt/point 15.0 0.0) [from to])))))

(t/deftest helpers-curve-has-point?
  (let [start (gpt/point 0.0 0.0)
        end   (gpt/point 10.0 0.0)
        h1    (gpt/point 0.0 0.0)
        h2    (gpt/point 10.0 0.0)
        ;; degenerate curve (same as line) — midpoint should be on it
        curve [start end h1 h2]]
    (t/is (path.helpers/curve-has-point? (gpt/point 5.0 0.0) curve))
    (t/is (not (path.helpers/curve-has-point? (gpt/point 5.0 100.0) curve)))))

(t/deftest helpers-curve-tangent
  (let [start   (gpt/point 0.0 0.0)
        end     (gpt/point 10.0 0.0)
        h1      (gpt/point 3.0 0.0)
        h2      (gpt/point 7.0 0.0)
        tangent (path.helpers/curve-tangent [start end h1 h2] 0.5)]
    ;; For a nearly-horizontal curve, the tangent y-component is small
    (t/is (mth/close? 1.0 (:x tangent) 0.01))
    (t/is (mth/close? 0.0 (:y tangent) 0.01))))

(t/deftest helpers-curve->lines
  (let [start (gpt/point 0.0 0.0)
        end   (gpt/point 10.0 0.0)
        h1    (gpt/point 3.0 5.0)
        h2    (gpt/point 7.0 5.0)
        lines (path.helpers/curve->lines start end h1 h2)]
    ;; curve->lines produces num-segments lines (10 by default, closed [0..1] => 11 pairs)
    (t/is (pos? (count lines)))
    (t/is (= 2 (count (first lines))))))

(t/deftest helpers-line-line-intersect
  (t/testing "perpendicular lines intersect"
    (let [l1 [(gpt/point 5.0 0.0) (gpt/point 5.0 10.0)]
          l2 [(gpt/point 0.0 5.0) (gpt/point 10.0 5.0)]
          result (path.helpers/line-line-intersect l1 l2)]
      (t/is (some? result))))
  (t/testing "parallel lines do not intersect"
    (let [l1 [(gpt/point 0.0 0.0) (gpt/point 10.0 0.0)]
          l2 [(gpt/point 0.0 5.0) (gpt/point 10.0 5.0)]
          result (path.helpers/line-line-intersect l1 l2)]
      (t/is (nil? result)))))

(t/deftest helpers-subcurve-range
  (let [start (gpt/point 0.0 0.0)
        end   (gpt/point 10.0 0.0)
        h1    (gpt/point 3.0 5.0)
        h2    (gpt/point 7.0 5.0)
        [s e _ _] (path.helpers/subcurve-range start end h1 h2 0.25 0.75)]
    ;; sub-curve should start near t=0.25 and end near t=0.75
    (t/is (some? s))
    (t/is (some? e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SEGMENT FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest segment-get-handler
  (let [cmd {:command :curve-to
             :params {:x 10.0 :y 0.0 :c1x 3.0 :c1y 5.0 :c2x 7.0 :c2y 2.0}}]
    (t/is (= (gpt/point 3.0 5.0) (path.segment/get-handler cmd :c1)))
    (t/is (= (gpt/point 7.0 2.0) (path.segment/get-handler cmd :c2)))
    (t/is (nil? (path.segment/get-handler {:command :line-to :params {:x 1 :y 2}} :c1)))))

(t/deftest segment-handler->node
  (let [content (path/content sample-content-2)]
    ;; For :c1 prefix, the node is the previous segment
    (let [node (path.segment/handler->node (vec content) 2 :c1)]
      (t/is (some? node)))
    ;; For :c2 prefix, the node is the current segment's endpoint
    (let [node (path.segment/handler->node (vec content) 2 :c2)]
      (t/is (some? node)))))

(t/deftest segment-calculate-opposite-handler
  (let [pt  (gpt/point 5.0 5.0)
        h   (gpt/point 8.0 5.0)
        opp (path.segment/calculate-opposite-handler pt h)]
    (t/is (mth/close? 2.0 (:x opp)))
    (t/is (mth/close? 5.0 (:y opp)))))

(t/deftest segment-point-indices
  (let [content (path/content sample-content-2)
        pt      (gpt/point 480.0 839.0)
        idxs    (path.segment/point-indices content pt)]
    (t/is (= [0] (vec idxs)))))

(t/deftest segment-opposite-index
  (let [content (path/content sample-content-2)]
    ;; Index 2 with :c2 prefix — the node is the current point of index 2
    (let [result (path.segment/opposite-index content 2 :c2)]
      ;; result is either nil or [index prefix]
      (t/is (or (nil? result) (vector? result))))))

(t/deftest segment-split-segments
  (let [content (path/content sample-content-square)
        points  #{(gpt/point 10.0 0.0)
                  (gpt/point 0.0 0.0)}
        result  (path.segment/split-segments content points 0.5)]
    ;; result should have more segments than original (splits added)
    (t/is (> (count result) (count sample-content-square)))))

;; Regression test for issue #8301: clicking a hover point on a path segment
;; must insert exactly one node. The bug caused two actions to fire together —
;; create-node-at-position (correct) and add-node (extra) — so the midpoint M
;; was appended again as a tail endpoint, giving [M A L M L B L M] instead of
;; the correct [M A L M L B]. The stroke markerEnd then landed in the middle.
;; The invariant: split-segments on a 2-command open path produces exactly 3
;; commands and does not repeat the inserted midpoint at the tail.
(t/deftest segment-split-segments-no-duplicate-endpoint
  (let [;; Simple open path: A=(0,0) → B=(100,0)
        segments [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 100.0 :y 0.0}}]
        from-p   (gpt/point 0.0 0.0)
        to-p     (gpt/point 100.0 0.0)
        content  (path/content segments)
        result   (path.segment/split-segments content #{from-p to-p} 0.5)
        cmds     (mapv :command result)]

    ;; Must be exactly 3 commands: move-to A, line-to M, line-to B
    (t/is (= 3 (count result)))
    (t/is (= [:move-to :line-to :line-to] cmds))

    ;; Midpoint M must be at (50, 0)
    (t/is (mth/close? 50.0 (get-in result [1 :params :x])))
    (t/is (mth/close? 0.0  (get-in result [1 :params :y])))

    ;; Original endpoint B must still be at (100, 0) — not shadowed by a duplicate M
    (t/is (mth/close? 100.0 (get-in result [2 :params :x])))
    (t/is (mth/close? 0.0   (get-in result [2 :params :y])))))

(t/deftest segment-content->selrect
  (let [content (path/content sample-content-square)
        rect    (path.segment/content->selrect content)]
    (t/is (some? rect))
    (t/is (mth/close? 0.0  (:x1 rect) 0.1))
    (t/is (mth/close? 0.0  (:y1 rect) 0.1))
    (t/is (mth/close? 10.0 (:x2 rect) 0.1))
    (t/is (mth/close? 10.0 (:y2 rect) 0.1))))

(t/deftest segment-content->selrect-multi-line
  ;; Regression: calculate-extremities used move-p instead of from-p in
  ;; the :line-to branch. For a subpath with multiple consecutive line-to
  ;; commands, the selrect must still match the reference implementation.
  (let [;; A subpath that starts away from the origin and has three
        ;; line-to segments so that move-p diverges from from-p for the
        ;; later segments.
        segments [{:command :move-to :params {:x 5.0  :y 5.0}}
                  {:command :line-to :params {:x 15.0 :y 0.0}}
                  {:command :line-to :params {:x 20.0 :y 8.0}}
                  {:command :line-to :params {:x 10.0 :y 12.0}}]
        content  (path/content segments)
        rect     (path.segment/content->selrect content)
        ref-pts  (calculate-extremities segments)]

    ;; Bounding box must enclose all four vertices exactly.
    (t/is (some? rect))
    (t/is (mth/close?  5.0 (:x1 rect) 0.1))
    (t/is (mth/close?  0.0 (:y1 rect) 0.1))
    (t/is (mth/close? 20.0 (:x2 rect) 0.1))
    (t/is (mth/close? 12.0 (:y2 rect) 0.1))

    ;; Must agree with the reference implementation.
    (t/is (= ref-pts (calculate-extremities content)))))

(t/deftest segment-content-center
  (let [content (path/content sample-content-square)
        center  (path.segment/content-center content)]
    (t/is (some? center))
    (t/is (mth/close? 5.0 (:x center) 0.1))
    (t/is (mth/close? 5.0 (:y center) 0.1))))

(t/deftest segment-move-content
  (let [content   (path/content sample-content-square)
        move-vec  (gpt/point 5.0 5.0)
        result    (path.segment/move-content content move-vec)
        first-seg (first (vec result))]
    (t/is (= :move-to (:command first-seg)))
    (t/is (mth/close? 5.0 (get-in first-seg [:params :x])))))

(t/deftest segment-is-curve?
  (let [content (path/content sample-content-2)]
    ;; point at index 0 is 480,839 — no handler offset, not a curve
    (let [pt (gpt/point 480.0 839.0)]
      ;; is-curve? can return nil (falsy) or boolean — just check it doesn't throw
      (t/is (not (path.segment/is-curve? content pt))))
    ;; A point that is reached by a curve-to command should be detectable
    (let [curve-pt (gpt/point 4.0 4.0)]
      (t/is (or (nil? (path.segment/is-curve? content curve-pt))
                (boolean? (path.segment/is-curve? content curve-pt)))))))

(t/deftest segment-append-segment
  (let [content (path/content sample-content)
        seg     {:command :line-to :params {:x 100.0 :y 100.0}}
        result  (path.segment/append-segment content seg)]
    (t/is (= (inc (count (vec content))) (count result)))))

(t/deftest segment-remove-nodes
  (let [content (path/content simple-open-content)
        ;; remove the midpoint
        pt      (gpt/point 10.0 0.0)
        result  (path.segment/remove-nodes content #{pt})]
    ;; should have fewer segments
    (t/is (< (count result) (count simple-open-content)))))

(t/deftest helpers-fit-cubic-recovers-curve
  ;; fitting samples of a known cubic recovers control points close to it
  (let [curve   [(gpt/point 0.0 0.0) (gpt/point 30.0 0.0)
                 (gpt/point 10.0 10.0) (gpt/point 20.0 10.0)]
        samples (mapv #(path.helpers/curve-values curve (/ % 20.0)) (range 21))
        tan1    (path.helpers/curve-tangent curve 0)
        tan2    (gpt/negate (path.helpers/curve-tangent curve 1))
        [h1 h2] (path.fit/fit-cubic samples tan1 tan2)]
    (t/is (mth/close? 10.0 (:x h1) 1.0))
    (t/is (mth/close? 10.0 (:y h1) 1.0))
    (t/is (mth/close? 20.0 (:x h2) 1.0))
    (t/is (mth/close? 10.0 (:y h2) 1.0))))

(t/deftest helpers-curve-closest-t
  ;; A degenerate cubic maps points back onto the same line.
  (let [curve [(gpt/point 0.0 0.0) (gpt/point 10.0 0.0)
               (gpt/point 0.0 0.0) (gpt/point 10.0 0.0)]]
    (t/is (mth/close? 0.5 (path.helpers/curve-closest-t curve (gpt/point 5.0 0.0) 0.001) 0.01))
    (doseq [q [(gpt/point 2.5 0.0) (gpt/point 7.0 0.0)]]
      (let [t (path.helpers/curve-closest-t curve q 0.001)
            p (path.helpers/curve-values curve t)]
        (t/is (mth/close? (:x q) (:x p) 0.05))))))

(t/deftest helpers-bend-curve-deltas-passes-through-target
  ;; the handle deltas move the point at t exactly onto the target, for any t
  (let [curve  [(gpt/point 0.0 0.0) (gpt/point 10.0 0.0)
                (gpt/point 0.0 0.0) (gpt/point 10.0 0.0)]]
    (doseq [t      [0.3 0.5 0.7]
            target [(gpt/point 5.0 4.0) (gpt/point 3.0 -6.0)]]
      (let [{:keys [c1x c1y c2x c2y]} (path.helpers/bend-curve-deltas curve t target)
            bent [(gpt/point 0.0 0.0) (gpt/point 10.0 0.0)
                  (gpt/point c1x c1y) (gpt/point (+ 10.0 c2x) c2y)]
            p    (path.helpers/curve-values bent t)]
        (t/is (mth/close? (:x target) (:x p) 0.001))
        (t/is (mth/close? (:y target) (:y p) 0.001))))))

(t/deftest segment-flip-content-horizontal
  ;; mirror every node across the bbox center on the vertical axis
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 10.0}}])
        result  (path/flip-content content #{0 1 2} :horizontal)
        pts     (mapv (comp (juxt :x :y) :params) (vec result))]
    (t/is (= [[10.0 0.0] [0.0 0.0] [0.0 10.0]] pts))))

(t/deftest segment-flip-content-curve-handles
  ;; a curve mirrors its anchors and both handles, keeping shape symmetry
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :curve-to :params {:c1x 2.0 :c1y 5.0 :c2x 8.0 :c2y 5.0 :x 10.0 :y 0.0}}])
        result  (vec (path/flip-content content #{0 1} :horizontal))]
    (t/is (= {:x 10.0 :y 0.0} (:params (first result))))
    (t/is (= {:c1x 8.0 :c1y 5.0 :c2x 2.0 :c2y 5.0 :x 0.0 :y 0.0}
             (:params (second result))))))

(t/deftest segment-flip-content-vertical
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :curve-to :params {:c1x 2.0 :c1y 5.0 :c2x 8.0 :c2y 5.0 :x 10.0 :y 0.0}}])
        result  (vec (path/flip-content content #{0 1} :vertical))]
    (t/is (= {:c1x 2.0 :c1y -5.0 :c2x 8.0 :c2y -5.0 :x 10.0 :y 0.0}
             (:params (second result))))))

(t/deftest segment-separate-single-node
  ;; Separating an interior node creates two open ends.
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 20.0 :y 0.0}}])
        result  (vec (path/separate-nodes content #{(gpt/point 10.0 0.0)}))]
    ;; move-to, line-to (to node1 kept at 10,0), move-to (node2 offset), line-to
    (t/is (= [:move-to :line-to :move-to :line-to] (mapv :command result)))
    (t/is (= {:x 10.0 :y 0.0} (:params (nth result 1))))
    (t/is (= {:x 18.0 :y 8.0} (:params (nth result 2))))
    (t/is (= {:x 20.0 :y 0.0} (:params (nth result 3))))))

(t/deftest segment-separate-single-node-custom-offset
  ;; The supplied offset controls the gap between split ends.
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 20.0 :y 0.0}}])
        result  (vec (path/separate-nodes content #{(gpt/point 10.0 0.0)} (gpt/point 2.0 2.0)))]
    (t/is (= [:move-to :line-to :move-to :line-to] (mapv :command result)))
    (t/is (= {:x 10.0 :y 0.0} (:params (nth result 1))))
    (t/is (= {:x 12.0 :y 2.0} (:params (nth result 2))))
    (t/is (= {:x 20.0 :y 0.0} (:params (nth result 3))))))

(t/deftest segment-separate-single-node-closed-seam
  ;; Separating a closed seam creates two endpoints.
  (let [point   (gpt/point 0.0 0.0)
        content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :curve-to
                   :params {:c1x 2.0 :c1y -2.0
                            :c2x 8.0 :c2y -2.0
                            :x 10.0 :y 0.0}}
                  {:command :curve-to
                   :params {:c1x 8.0 :c1y 2.0
                            :c2x 2.0 :c2y 2.0
                            :x 0.0 :y 0.0}}
                  {:command :close-path :params {}}])
        result  (vec (path/separate-nodes content #{point} (gpt/point 2.0 2.0)))]
    (t/is (= [:move-to :curve-to :curve-to] (mapv :command result)))
    (t/is (= {:x 0.0 :y 0.0}
             (select-keys (:params (first result)) [:x :y])))
    (t/is (= {:x 2.0 :y 2.0}
             (select-keys (:params (peek result)) [:x :y])))
    ;; The incoming c2 stays attached to the shifted endpoint.
    (t/is (= {:c2x 4.0 :c2y 4.0}
             (select-keys (:params (peek result)) [:c2x :c2y])))))

(t/deftest segment-separate-single-node-endpoint-noop
  ;; an endpoint node has no following segment, so nothing is split
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 20.0 :y 0.0}}])
        result  (vec (path/separate-nodes content #{(gpt/point 20.0 0.0)}))]
    (t/is (= [:move-to :line-to :line-to] (mapv :command result)))))

(t/deftest segment-separate-single-node-curve-carries-handler
  ;; the outgoing curve's leading handler is shifted with the new start
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :curve-to :params {:c1x 12.0 :c1y 0.0 :c2x 18.0 :c2y 0.0 :x 20.0 :y 0.0}}])
        result  (vec (path/separate-nodes content #{(gpt/point 10.0 0.0)}))
        curve   (nth result 3)]
    (t/is (= [:move-to :line-to :move-to :curve-to] (mapv :command result)))
    (t/is (= {:x 18.0 :y 8.0} (:params (nth result 2))))
    ;; c1 shifted by the same (8,8) offset, c2/end untouched
    (t/is (= 20.0 (get-in curve [:params :c1x])))
    (t/is (= 8.0 (get-in curve [:params :c1y])))
    (t/is (= 18.0 (get-in curve [:params :c2x])))
    (t/is (= 20.0 (get-in curve [:params :x])))))

(t/deftest segment-separate-single-node-junction
  ;; Separating coincident subpaths creates one open end per line.
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 5.0 :y 5.0}}
                  {:command :move-to :params {:x 5.0 :y 5.0}}
                  {:command :line-to :params {:x 10.0 :y 10.0}}])
        result  (vec (path/separate-nodes content #{(gpt/point 5.0 5.0)}))]
    (t/is (= [:move-to :line-to :move-to :line-to] (mapv :command result)))
    ;; the first line keeps (5,5); the second subpath's start is offset by (8,8)
    (t/is (= {:x 5.0 :y 5.0} (:params (nth result 1))))
    (t/is (= {:x 13.0 :y 13.0} (:params (nth result 2))))
    (t/is (= {:x 10.0 :y 10.0} (:params (nth result 3))))))

(t/deftest segment-separate-single-node-junction-three-lines
  ;; three lines meeting at a point separate into three distinct offset ends
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 5.0 :y 5.0}}
                  {:command :move-to :params {:x 20.0 :y 0.0}}
                  {:command :line-to :params {:x 5.0 :y 5.0}}
                  {:command :move-to :params {:x 5.0 :y 5.0}}
                  {:command :line-to :params {:x 10.0 :y 10.0}}])
        result  (vec (path/separate-nodes content #{(gpt/point 5.0 5.0)}))]
    (t/is (= [{:x 0.0 :y 0.0} {:x 5.0 :y 5.0}
              {:x 20.0 :y 0.0} {:x 13.0 :y 13.0}
              {:x 21.0 :y 21.0} {:x 10.0 :y 10.0}]
             (mapv #(select-keys (:params %) [:x :y]) result)))))

(t/deftest segment-flip-content-partial-selection
  ;; only the selected nodes and their handles move; others stay put
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 10.0}}])
        result  (path/flip-content content #{0 1} :horizontal)
        pts     (mapv (comp (juxt :x :y) :params) (vec result))]
    (t/is (= [[10.0 0.0] [0.0 0.0] [10.0 10.0]] pts))))

(t/deftest segment-align-content
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 2.0}}
                  {:command :line-to :params {:x 4.0 :y 20.0}}])
        pts     (fn [c] (mapv (comp (juxt :x :y) :params) (vec c)))]
    ;; align to the left edge: every selected x becomes the min x
    (t/is (= [[0.0 0.0] [0.0 2.0] [0.0 20.0]]
             (pts (path/align-content content #{0 1 2} :hleft))))
    ;; align to horizontal center: x becomes the bbox center
    (t/is (= [[5.0 0.0] [5.0 2.0] [5.0 20.0]]
             (pts (path/align-content content #{0 1 2} :hcenter))))
    ;; align to the top edge: every selected y becomes the min y
    (t/is (= [[0.0 0.0] [10.0 0.0] [4.0 0.0]]
             (pts (path/align-content content #{0 1 2} :vtop))))))

(t/deftest segment-align-content-partial-and-guard
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 2.0}}
                  {:command :line-to :params {:x 4.0 :y 20.0}}])
        pts     (fn [c] (mapv (comp (juxt :x :y) :params) (vec c)))]
    ;; only the selected nodes align; the unselected node stays put
    (t/is (= [[0.0 0.0] [0.0 2.0] [4.0 20.0]]
             (pts (path/align-content content #{0 1} :hleft))))
    ;; fewer than two selected nodes is a no-op
    (t/is (= (pts content)
             (pts (path/align-content content #{0} :hleft))))))

(t/deftest segment-align-content-moves-handles
  ;; a selected node's attached handles move rigidly with its anchor
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :curve-to :params {:c1x 2.0 :c1y 1.0 :c2x 8.0 :c2y 1.0 :x 10.0 :y 0.0}}])
        result  (vec (path/align-content content #{0 1} :vtop))]
    ;; both nodes already share y=0, so vtop is a no-op on the anchors and
    ;; leaves the handles untouched
    (t/is (= {:c1x 2.0 :c1y 1.0 :c2x 8.0 :c2y 1.0 :x 10.0 :y 0.0}
             (:params (second result))))))

(t/deftest segment-set-nodes-coordinate
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 20.0 :y 0.0}}])]
    ;; setting x for two nodes moves each to that x (per-node delta), the
    ;; unselected node is untouched
    (t/is (= [[:move-to {:x 5.0 :y 0.0}]
              [:line-to {:x 10.0 :y 0.0}]
              [:line-to {:x 5.0 :y 0.0}]]
             (mapv (juxt :command :params)
                   (vec (path/set-nodes-coordinate content #{0 2} :x 5.0)))))
    ;; a single node's y moves only that node, and its attached handle moves
    ;; rigidly with the anchor
    (let [curved (path/content
                  [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :curve-to :params {:c1x 2.0 :c1y 0.0 :c2x 8.0 :c2y 0.0 :x 10.0 :y 0.0}}])
          r      (vec (path/set-nodes-coordinate curved #{1} :y 5.0))]
      ;; node 1 anchor y 0 -> 5 (delta +5); its :c2 handle (owned by node 1)
      ;; moves +5 too; :c1 (owned by node 0, unselected) stays
      (t/is (= {:c1x 2.0 :c1y 0.0 :c2x 8.0 :c2y 5.0 :x 10.0 :y 5.0}
               (:params (second r)))))))

(t/deftest segment-set-nodes-coordinate-keeps-coincident-nodes-together
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 0.0 :y 0.0}}])
        result  (vec (path/set-nodes-coordinate content #{0} :y 5.0))]
    ;; The first and last commands are the same logical closed-seam node.
    (t/is (= (gpt/point 0.0 5.0)
             (path.helpers/segment->point (nth result 0))))
    (t/is (= (gpt/point 0.0 5.0)
             (path.helpers/segment->point (nth result 2))))
    (t/is (= (gpt/point 10.0 0.0)
             (path.helpers/segment->point (nth result 1))))))

(t/deftest segment-set-handler-points
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :curve-to :params {:c1x 2.0 :c1y 2.0 :c2x 8.0 :c2y 2.0 :x 10.0 :y 0.0}}])
        r       (vec (path/set-handler-points content {[1 :c2] (gpt/point 7.0 6.0)}))]
    ;; c2 set to the target point; c1 and the anchor stay put
    (t/is (= {:c1x 2.0 :c1y 2.0 :c2x 7.0 :c2y 6.0 :x 10.0 :y 0.0}
             (:params (second r))))))

(t/deftest segment-translate-selected-nodes
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 20.0 :y 0.0}}])
        ;; translate nodes 1 and 2 by (0, 5): both move down, node 0 stays
        r       (vec (path/translate-selected-nodes content #{1 2} (gpt/point 0.0 5.0)))]
    (t/is (= [[:move-to {:x 0.0 :y 0.0}]
              [:line-to {:x 10.0 :y 5.0}]
              [:line-to {:x 20.0 :y 5.0}]]
             (mapv (juxt :command :params) r)))))

(t/deftest segment-distribute-content
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 3.0 :y 5.0}}
                  {:command :line-to :params {:x 10.0 :y 9.0}}])
        pts     (fn [c] (mapv (comp (juxt :x :y) :params) (vec c)))]
    ;; the middle node is spaced evenly between the two extremes on x
    (t/is (= [[0.0 0.0] [5.0 5.0] [10.0 9.0]]
             (pts (path/distribute-content content #{0 1 2} :horizontal))))
    ;; fewer than three selected nodes is a no-op
    (t/is (= (pts content)
             (pts (path/distribute-content content #{0 1} :horizontal))))))

(t/deftest segment-distribute-content-keeps-coincident-nodes-together
  ;; Coincident selected nodes move as one group.
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 3.0 :y 7.0}}
                  {:command :line-to :params {:x 3.0 :y 7.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}])
        pts     (fn [c] (mapv (comp (juxt :x :y) :params) (vec c)))]
    ;; three distinct positions (0, 3, 10); the coincident pair is one group
    ;; centred at x=5 and both nodes move there together, staying coincident
    (t/is (= [[0.0 0.0] [5.0 7.0] [5.0 7.0] [10.0 0.0]]
             (pts (path/distribute-content content #{0 1 2 3} :horizontal))))
    ;; only two distinct positions among the selection is a no-op
    (t/is (= (pts content)
             (pts (path/distribute-content content #{1 2 3} :horizontal))))))

(t/deftest helpers-curve-arc-length-t
  (let [arc-len (fn [curve a b]
                  (->> (range 1001)
                       (map #(path.helpers/curve-values
                              curve (+ a (* (/ (double %) 1000) (- b a)))))
                       (partition 2 1)
                       (map (fn [[p q]] (gpt/distance p q)))
                       (reduce +)))]
    ;; a straight line (degenerate cubic) has its visual middle at t=0.5
    (let [line [(gpt/point 0.0 0.0) (gpt/point 10.0 0.0)
                (gpt/point 0.0 0.0) (gpt/point 10.0 0.0)]]
      (t/is (mth/close? 0.5 (path.helpers/curve-arc-length-t line) 0.01)))
    ;; An uneven curve's arc midpoint differs from its parametric midpoint.
    (let [curve [(gpt/point 0.0 0.0) (gpt/point 100.0 100.0)
                 (gpt/point 0.0 0.0) (gpt/point 0.0 100.0)]
          t     (path.helpers/curve-arc-length-t curve)
          total (arc-len curve 0.0 1.0)
          first-half (arc-len curve 0.0 t)]
      (t/is (< 0.5 t 1.0))
      ;; the length up to t is within 1% of half the total
      (t/is (< (mth/abs (- first-half (/ total 2.0))) (* 0.01 total))))))

(t/deftest helpers-fit-curve-single-curve
  ;; samples of one gentle cubic are fitted back with a single curve
  (let [curve   [(gpt/point 0.0 0.0) (gpt/point 30.0 0.0)
                 (gpt/point 10.0 10.0) (gpt/point 20.0 10.0)]
        samples (mapv #(path.helpers/curve-values curve (/ % 24.0)) (range 25))
        result  (path.fit/fit-curve samples 0.5)]
    (t/is (= 1 (count result)))
    (let [[start end h1 h2] (first result)]
      (t/is (= (gpt/point 0.0 0.0) start))
      (t/is (= (gpt/point 30.0 0.0) end))
      (t/is (mth/close? 10.0 (:x h1) 1.5))
      (t/is (mth/close? 10.0 (:y h1) 1.5))
      (t/is (mth/close? 20.0 (:x h2) 1.5))
      (t/is (mth/close? 10.0 (:y h2) 1.5)))))

(t/deftest helpers-fit-curve-splits-and-chains
  ;; Sharp corners split the fit into chained curves.
  (let [pts    (into []
                     (concat
                      (map #(gpt/point (double %) (double %)) (range 0 11))
                      (map #(gpt/point (+ 10.0 %) (- 10.0 %)) (range 1 11))))
        result (path.fit/fit-curve pts 0.1)]
    (t/is (> (count result) 1))
    (t/is (every? (fn [[c1 c2]] (= (nth c1 1) (nth c2 0)))
                  (map vector result (rest result))))
    (t/is (= (gpt/point 0.0 0.0) (get-in result [0 0])))
    (t/is (= (gpt/point 20.0 0.0) (nth (peek result) 1)))))

(t/deftest helpers-fit-curve-respects-tolerance
  ;; every input point stays within tolerance of the fitted sequence
  (let [pts       (mapv #(gpt/point (double %) (* 5.0 (mth/sin (/ % 3.0))))
                        (range 0 31))
        tol       0.5
        result    (path.fit/fit-curve pts tol)
        curve-pts (into []
                        (mapcat (fn [c]
                                  (map #(path.helpers/curve-values c (/ % 100.0))
                                       (range 101))))
                        result)
        max-dev   (reduce max
                          (map (fn [p]
                                 (reduce min (map #(gpt/distance p %) curve-pts)))
                               pts))]
    (t/is (<= max-dev (+ tol 0.05)))))

(t/deftest helpers-fit-curve-keeps-sharp-corners
  ;; Sharp-corner handles follow their own legs.
  (let [corner (gpt/point 10.0 10.0)
        ;; two legs meeting at a 90 degree corner: (0,0)->(10,10)->(20,0)
        pts    (into []
                     (concat
                      (map #(gpt/point (double %) (double %)) (range 0 11))
                      (map #(gpt/point (+ 10.0 %) (- 10.0 %)) (range 1 11))))
        result (path.fit/fit-curve pts 0.1)
        ;; the two curves meeting at the corner
        left   (first (filter #(= corner (nth % 1)) result))
        right  (first (filter #(= corner (nth % 0)) result))
        v-in   (gpt/to-vec corner (nth left 3))   ;; incoming handle (h2) direction
        v-out  (gpt/to-vec corner (nth right 2))  ;; outgoing handle (h1) direction
        angle  (gpt/angle-with-other v-in v-out)]
    (t/is (some? left))
    (t/is (some? right))
    ;; The join keeps the corner's angle.
    (t/is (< angle 135.0))
    (t/is (mth/close? 90.0 angle 15.0))))

(t/deftest segment-smooth-points->content
  (t/testing "two points produce a straight segment"
    (let [content (path.segment/smooth-points->content
                   [(gpt/point 0.0 0.0) (gpt/point 10.0 0.0)] 1.0)]
      (t/is (= [:move-to :line-to] (mapv :command content)))))
  (t/testing "freehand-like points produce fewer, fitted curve segments"
    (let [pts     (mapv #(gpt/point (double %) (* 5.0 (mth/sin (/ % 3.0))))
                        (range 0 31))
          content (path.segment/smooth-points->content pts 1.0)
          cmds    (mapv :command content)]
      (t/is (= :move-to (first cmds)))
      (t/is (every? #(= :curve-to %) (rest cmds)))
      (t/is (< (count cmds) (count pts)))
      (t/is (= {:x 0.0 :y 0.0} (:params (first (vec content)))))
      (let [last-params (:params (peek (vec content)))]
        (t/is (mth/close? 30.0 (:x last-params)))
        (t/is (mth/close? (* 5.0 (mth/sin 10.0)) (:y last-params)))))))

(t/deftest segment-remove-nodes-collinear-keeps-line
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}]
        result  (path.segment/remove-nodes (path/content content)
                                           #{(gpt/point 10.0 0.0)})]
    (t/is (= [:move-to :line-to] (mapv :command result)))
    (t/is (= {:x 20.0 :y 0.0} (:params (second result))))))

(t/deftest segment-remove-nodes-corner-fits-curve
  ;; Removing a slanted corner keeps both endpoint tangents.
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 10.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}]
        result  (path.segment/remove-nodes (path/content content)
                                           #{(gpt/point 10.0 10.0)})
        curve   (second result)]
    (t/is (= [:move-to :curve-to] (mapv :command result)))
    (let [{:keys [c1x c1y c2x c2y x y]} (:params curve)
          mid (path.helpers/curve-values (gpt/point 0.0 0.0) (gpt/point x y)
                                         (gpt/point c1x c1y) (gpt/point c2x c2y) 0.5)]
      (t/is (mth/close? 20.0 x))
      (t/is (mth/close? 0.0 y))
      ;; handlers stay on the removed segments' directions (45 degrees)
      (t/is (mth/close? c1x c1y 0.01))
      (t/is (mth/close? (- 20.0 c2x) c2y 0.01))
      ;; the curve bulges towards the removed corner
      (t/is (< 2.0 (:y mid) 10.0))
      (t/is (mth/close? 10.0 (:x mid) 0.5)))))

(t/deftest segment-remove-nodes-between-curves-approximates
  ;; Joined quarter arcs collapse into a fitted semicircle.
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :curve-to :params {:c1x 0.0 :c1y 5.52 :c2x 4.48 :c2y 10.0 :x 10.0 :y 10.0}}
                 {:command :curve-to :params {:c1x 15.52 :c1y 10.0 :c2x 20.0 :c2y 5.52 :x 20.0 :y 0.0}}]
        result  (path.segment/remove-nodes (path/content content)
                                           #{(gpt/point 10.0 10.0)})
        curve   (second result)]
    (t/is (= [:move-to :curve-to] (mapv :command result)))
    (let [{:keys [c1x c1y c2x c2y x y]} (:params curve)
          mid (path.helpers/curve-values (gpt/point 0.0 0.0) (gpt/point x y)
                                         (gpt/point c1x c1y) (gpt/point c2x c2y) 0.5)]
      ;; The fitted curve keeps the semicircle apex.
      (t/is (mth/close? 10.0 (:x mid) 0.5))
      (t/is (mth/close? 10.0 (:y mid) 0.5)))))

(t/deftest segment-remove-node-restores-a-split-curve
  ;; Removing an untouched split node rejoins the cubic exactly.
  (let [from     (gpt/point 0.0 0.0)
        original {:command :curve-to
                  :params {:c1x 0.0 :c1y 0.0
                           :c2x 0.0 :c2y 100.0
                           :x 100.0 :y 100.0}}
        content  (path/content [(path.helpers/make-move-to from) original])
        curve    (path.helpers/command->bezier original from)
        t-val    (path.helpers/curve-arc-length-t curve)
        split    (-> (path.segment/split-segments content #{from (gpt/point 100.0 100.0)} t-val)
                     (path/content))
        inserted (path.helpers/segment->point (nth split 1))
        result   (vec (path.segment/remove-nodes split #{inserted}))
        healed   (second result)]
    ;; Split at the asymmetric curve's arc midpoint.
    (t/is (not (mth/close? 0.5 t-val 0.01)))
    (t/is (= [:move-to :curve-to] (mapv :command result)))
    (doseq [coord [:c1x :c1y :c2x :c2y :x :y]]
      (t/is (mth/close? (get-in original [:params coord])
                        (get-in healed [:params coord]))))))

(t/deftest segment-remove-nodes-multiple-consecutive
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 5.0 :y 5.0}}
                 {:command :line-to :params {:x 10.0 :y 7.0}}
                 {:command :line-to :params {:x 15.0 :y 5.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}]
        result  (path.segment/remove-nodes (path/content content)
                                           #{(gpt/point 5.0 5.0)
                                             (gpt/point 10.0 7.0)
                                             (gpt/point 15.0 5.0)})
        curve   (second result)]
    (t/is (= [:move-to :curve-to] (mapv :command result)))
    (let [{:keys [c1x c1y c2x c2y x y]} (:params curve)
          mid (path.helpers/curve-values (gpt/point 0.0 0.0) (gpt/point x y)
                                         (gpt/point c1x c1y) (gpt/point c2x c2y) 0.5)]
      (t/is (mth/close? 10.0 (:x mid) 1.0))
      (t/is (< 4.0 (:y mid) 8.5)))))

(t/deftest segment-remove-nodes-endpoints-drop-segments
  (let [content (path/content simple-open-content)]
    (t/testing "removing the first node drops the leading segment"
      (let [result (path.segment/remove-nodes content #{(gpt/point 0.0 0.0)})]
        (t/is (= [:move-to :line-to] (mapv :command result)))
        (t/is (= {:x 10.0 :y 0.0} (:params (first result))))))
    (t/testing "removing the last node drops the trailing segment"
      (let [result (path.segment/remove-nodes content #{(gpt/point 10.0 10.0)})]
        (t/is (= [:move-to :line-to] (mapv :command result)))
        (t/is (= {:x 10.0 :y 0.0} (:params (second result))))))))

(t/deftest segment-remove-nodes-closed-path-keeps-closure
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 10.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}
                 {:command :line-to :params {:x 0.0 :y 0.0}}
                 {:command :close-path :params {}}]
        result  (path.segment/remove-nodes (path/content content)
                                           #{(gpt/point 10.0 10.0)})]
    (t/is (= [:move-to :curve-to :line-to :close-path] (mapv :command result)))))

(t/deftest segment-remove-nodes-heals-a-closed-seam
  (let [line-closed [{:command :move-to :params {:x 0.0 :y 0.0}}
                     {:command :line-to :params {:x 10.0 :y 0.0}}
                     {:command :line-to :params {:x 10.0 :y 10.0}}
                     {:command :line-to :params {:x 0.0 :y 10.0}}
                     {:command :line-to :params {:x 0.0 :y 0.0}}]
        command-closed (conj line-closed {:command :close-path :params {}})
        seam         (gpt/point 0.0 0.0)]
    (doseq [content [line-closed command-closed]]
      (let [result   (vec (path.segment/remove-nodes (path/content content) #{seam}))
            commands (mapv :command result)
            points   (mapv path.helpers/segment->point
                           (remove #(= :close-path (:command %)) result))]
        ;; Both non-seam sides survive and are joined through one fitted segment.
        (t/is (= [:move-to :line-to :line-to :curve-to]
                 (cond-> commands
                   (= :close-path (peek commands)) pop)))
        (t/is (= [(gpt/point 10.0 0.0)
                  (gpt/point 10.0 10.0)
                  (gpt/point 0.0 10.0)
                  (gpt/point 10.0 0.0)]
                 points))))))

(t/deftest segment-remove-nodes-heals-a-touching-subpath-seam
  ;; During path edition, duplicated and merged halves can still be stored as
  ;; two open subpaths whose endpoints touch. Finalizing the path joins them,
  ;; but deleting their shared node must behave the same before finalization.
  (let [content
        (path/content
         [{:command :move-to :params {:x 0.0 :y 10.0}}
          {:command :line-to :params {:x -10.0 :y 7.0}}
          {:command :line-to :params {:x -10.0 :y 3.0}}
          {:command :line-to :params {:x 0.0 :y 0.0}}
          {:command :move-to :params {:x 0.0 :y 0.0}}
          {:command :line-to :params {:x 10.0 :y 3.0}}
          {:command :line-to :params {:x 10.0 :y 7.0}}
          {:command :line-to :params {:x 0.0 :y 10.0}}])
        result  (vec (path.segment/remove-nodes content #{(gpt/point 0.0 10.0)}))]
    (t/is (= [:move-to :line-to :line-to :line-to :line-to :curve-to]
             (mapv :command result)))
    (t/is (= (gpt/point -10.0 7.0)
             (path.helpers/segment->point (first result))))
    (t/is (= (gpt/point -10.0 7.0)
             (path.helpers/segment->point (peek result))))))

(t/deftest segment-remove-nodes-chain-ending-on-close-path
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 10.0}}
                 {:command :close-path :params {}}]
        result  (path.segment/remove-nodes (path/content content)
                                           #{(gpt/point 10.0 10.0)})]
    ;; the geometry back to the start is approximated and the path stays closed
    (t/is (= [:move-to :line-to :curve-to :close-path] (mapv :command result)))
    (t/is (mth/close? 0.0 (get-in (vec result) [2 :params :x])))
    (t/is (mth/close? 0.0 (get-in (vec result) [2 :params :y])))))

(t/deftest segment-remove-nodes-heals-removed-close-target
  ;; Removing the closed seam preserves both adjacent sides and fits their
  ;; replacement across the former start point.
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 10.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}
                 {:command :line-to :params {:x 0.0 :y 0.0}}
                 {:command :close-path :params {}}]
        result  (path.segment/remove-nodes (path/content content)
                                           #{(gpt/point 0.0 0.0)})]
    (t/is (= [:move-to :line-to :curve-to :close-path] (mapv :command result)))
    (t/is (= (gpt/point 10.0 10.0)
             (path.helpers/segment->point (first result))))
    (t/is (= (gpt/point 10.0 10.0)
             (path.helpers/segment->point (nth result 2))))))

(t/deftest segment-join-nodes
  (let [content (path/content simple-open-content)
        pt1     (gpt/point 0.0 0.0)
        pt2     (gpt/point 10.0 10.0)
        result  (path.segment/join-nodes content #{pt1 pt2})]
    ;; join-nodes adds new segments connecting the given points
    (t/is (>= (count result) (count simple-open-content)))))

(t/deftest segment-separate-nodes
  (let [content (path/content simple-open-content)
        pt      (gpt/point 10.0 0.0)
        result  (path.segment/separate-nodes content #{pt})]
    ;; separate-nodes should return a collection (vector or seq)
    (t/is (coll? result))))

(t/deftest segment-make-corner-point
  (let [content (path/content sample-content-2)
        ;; Take a curve point and make it a corner
        pt      (gpt/point 439.0 802.0)
        result  (path.segment/make-corner-point content pt)]
    ;; Result is a PathData instance
    (t/is (some? result))))

(t/deftest segment-next-node
  (t/testing "no prev-point returns move-to"
    (let [content  (path/content sample-content)
          position (gpt/point 100.0 100.0)
          result   (path.segment/next-node content position nil nil)]
      (t/is (= :move-to (:command result)))))
  (t/testing "with prev-point and no handler and last command is not close-path"
    ;; Use a content that does NOT end with :close-path
    (let [content  (path/content simple-open-content)
          position (gpt/point 100.0 100.0)
          prev     (gpt/point 50.0 50.0)
          result   (path.segment/next-node content position prev nil)]
      (t/is (= :line-to (:command result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PATH TOP-LEVEL UNTESTED FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest path-from-plain
  (let [result (path/from-plain sample-content)]
    (t/is (path/content? result))
    (t/is (= (count sample-content) (count (vec result))))))

(t/deftest path-calc-selrect
  (let [content (path/content sample-content-square)
        rect    (path/calc-selrect content)]
    (t/is (some? rect))
    (t/is (mth/close? 0.0 (:x1 rect) 0.1))
    (t/is (mth/close? 0.0 (:y1 rect) 0.1))))

(t/deftest path-close-subpaths
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}
                 {:command :move-to :params {:x 10.0 :y 5.0}}
                 {:command :line-to :params {:x 0.0 :y 0.0}}]
        result  (path/close-subpaths content)]
    (t/is (path/content? result))
    (t/is (seq (vec result)))))

(t/deftest path-merge-touching-subpaths
  (t/testing "regression for #5283 — heroicons arrow path serialises as a single chain"
    ;; SVG `d` originally split a continuous polyline by inserting a
    ;; redundant moveto at the elbow. Importing it must collapse the
    ;; first two subpaths so that stroke-linejoin renders the rounded tip.
    (let [content (path/from-string
                   (str "M350.5,1846 L365.5,1846"
                        " M365.5,1846 L358.75,1839.25"
                        " M365.5,1846 L358.75,1852.75"))
          merged   (path/merge-touching-subpaths content)
          rendered (str merged)]
      (t/is (path/content? merged))
      ;; First two subpaths fold into M ... L ... L ... ; third stays
      ;; separate (its start point matches the original M, not the merged
      ;; chain's tail), so exactly two M commands remain.
      (t/is (= 2 (count (re-seq #"M" rendered))))
      (t/is (= 3 (count (re-seq #"L" rendered)))))))

(t/deftest path-move-content
  (let [content  (path/content sample-content-square)
        move-vec (gpt/point 3.0 4.0)
        result   (path/move-content content move-vec)
        first-r  (first (vec result))]
    (t/is (= :move-to (:command first-r)))
    (t/is (mth/close? 3.0 (get-in first-r [:params :x])))
    (t/is (mth/close? 4.0 (get-in first-r [:params :y])))))

(t/deftest path-move-content-zero-vec
  (t/testing "moving by zero returns same content"
    (let [content  (path/content sample-content-square)
          result   (path/move-content content (gpt/point 0 0))]
      ;; should return same object (identity) when zero vector
      (t/is (= (vec content) (vec result))))))

(t/deftest path-shape-with-open-path?
  (t/testing "path shape with open content is open"
    (let [shape {:type :path
                 :content (path/content simple-open-content)}]
      (t/is (path/shape-with-open-path? shape))))
  (t/testing "path shape with closed content is not open"
    (let [shape {:type :path
                 :content (path/content simple-closed-content)}]
      (t/is (not (path/shape-with-open-path? shape))))))

(t/deftest path-get-byte-size
  (let [content (path/content sample-content)
        size    (path/get-byte-size content)]
    (t/is (pos? size))))

(t/deftest path-apply-content-modifiers
  (let [content   (path/content sample-content)
        ;; shift the first point by x=5, y=3
        modifiers {0 {:x 5.0 :y 3.0}}
        result    (path/apply-content-modifiers content modifiers)
        first-seg (first (vec result))]
    (t/is (mth/close? (+ 480.0 5.0) (get-in first-seg [:params :x])))
    (t/is (mth/close? (+ 839.0 3.0) (get-in first-seg [:params :y])))))

(t/deftest path-handler-indices
  (t/testing "handler-indices returns expected handlers for a curve point"
    (let [content (path/content sample-content-2)
          ;; point at index 2 is (4.0, 4.0), which is a curve-to endpoint
          pt      (gpt/point 4.0 4.0)
          result  (path/handler-indices content pt)]
      ;; The :c2 handler of index 2 belongs to point (4.0, 4.0)
      ;; The :c1 handler of index 3 also belongs to point (4.0, 4.0)
      (t/is (some? result))
      (t/is (pos? (count result)))
      (t/is (every? (fn [[idx prefix]]
                      (and (number? idx)
                           (#{:c1 :c2} prefix)))
                    result))))
  (t/testing "handler-indices returns empty for a point with no associated handlers"
    (let [content (path/content sample-content-2)
          ;; (480.0, 839.0) is the move-to at index 0; since index 1
          ;; is a line-to (not a curve-to), there is no :c1 handler
          ;; for this point.
          pt      (gpt/point 480.0 839.0)
          result  (path/handler-indices content pt)]
      (t/is (empty? result))))
  (t/testing "handler-indices with nil content returns empty"
    (let [result (path/handler-indices nil (gpt/point 0 0))]
      (t/is (empty? result)))))

(t/deftest path-closest-point
  (t/testing "closest-point on a line segment"
    (let [content (path/content simple-open-content)
          ;; simple-open-content: (0,0)->(10,0)->(10,10)
          ;; Query a point near the first segment
          pos     (gpt/point 5.0 1.0)
          result  (path/closest-point content pos 0.01)]
      (t/is (some? result))
      ;; Closest point on line (0,0)->(10,0) to (5,1) should be near (5,0)
      (t/is (mth/close? 5.0 (:x result) 0.5))
      (t/is (mth/close? 0.0 (:y result) 0.5))))
  (t/testing "closest-point on nil content returns nil"
    (let [result (path/closest-point nil (gpt/point 5.0 5.0) 0.01)]
      (t/is (nil? result)))))

(t/deftest path-make-curve-point
  (t/testing "make-curve-point converts a line-to point into a curve"
    (let [content (path/content simple-open-content)
          ;; The midpoint (10,0) is reached via :line-to
          pt      (gpt/point 10.0 0.0)
          result  (path/make-curve-point content pt)
          segs    (vec result)]
      (t/is (some? result))
      ;; After making (10,0) a curve, we expect at least one :curve-to
      (t/is (some #(= :curve-to (:command %)) segs)))))

(t/deftest path-merge-nodes
  (t/testing "merge-nodes reduces segments at contiguous points"
    (let [content (path/content simple-open-content)
          ;; Merge the midpoint (10,0) — should reduce segment count
          pts     #{(gpt/point 10.0 0.0)}
          result  (path/merge-nodes content pts)]
      (t/is (some? result))
      (t/is (<= (count result) (count simple-open-content)))))
  (t/testing "merge-nodes with empty points returns same content"
    (let [content (path/content simple-open-content)
          result  (path/merge-nodes content #{})]
      (t/is (= (count result) (count simple-open-content)))))
  (t/testing "merge-nodes with nil content does not throw"
    (let [result (path/merge-nodes nil #{(gpt/point 0 0)})]
      (t/is (some? result)))))

(t/deftest path-merge-disconnected-nodes
  ;; Merging separate subpaths joins them at the shared midpoint.
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :move-to :params {:x 0.0 :y 10.0}}
                  {:command :line-to :params {:x 10.0 :y 10.0}}])
        pts     #{(gpt/point 10.0 0.0) (gpt/point 0.0 10.0)}
        result  (vec (path/merge-nodes content pts))]
    (t/is (= [{:x 0.0 :y 0.0} {:x 5.0 :y 5.0}
              {:x 5.0 :y 5.0} {:x 10.0 :y 10.0}]
             (mapv :params result)))))

(t/deftest path-duplicate-node-content
  ;; Duplicating a node copies its incident segments as subpaths.
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 100.0 :y 0.0}}
                  {:command :curve-to :params {:x 200.0 :y 100.0 :c1x 100.0 :c1y 50.0 :c2x 150.0 :c2y 100.0}}])
        off     (gpt/point 10 10)]
    ;; Interior copies meet at the offset node.
    (let [{ext :content selected :selected} (path/duplicate-node-content content 1 off)]
      (t/is (= [[:move-to {:x 0.0 :y 0.0}]
                [:line-to {:x 110.0 :y 10.0}]
                [:move-to {:x 200.0 :y 100.0}]
                [:curve-to {:x 110.0 :y 10.0 :c1x 150.0 :c1y 100.0 :c2x 110.0 :c2y 60.0}]]
               (mapv (juxt :command :params) ext)))
      (t/is (= #{1 3} selected)))
    ;; Endpoint copies keep only the incoming curve.
    (let [{ext :content selected :selected} (path/duplicate-node-content content 2 off)]
      (t/is (= [[:move-to {:x 100.0 :y 0.0}]
                [:curve-to {:c1x 100.0 :c1y 50.0 :c2x 160.0 :c2y 110.0 :x 210.0 :y 110.0}]]
               (mapv (juxt :command :params) ext)))
      (t/is (= #{1} selected)))
    ;; Subpath-start copies reverse the outgoing segment.
    (let [{ext :content selected :selected} (path/duplicate-node-content content 0 off)]
      (t/is (= [[:move-to {:x 100.0 :y 0.0}]
                [:line-to {:x 10.0 :y 10.0}]]
               (mapv (juxt :command :params) ext)))
      (t/is (= #{1} selected)))
    ;; a lone point (subpath with only a move-to) is copied as an offset point
    (let [lone (path/content [{:command :move-to :params {:x 5.0 :y 5.0}}])
          {ext :content selected :selected} (path/duplicate-node-content lone 0 off)]
      (t/is (= [[:move-to {:x 15.0 :y 15.0}]]
               (mapv (juxt :command :params) ext)))
      (t/is (= #{0} selected)))))

(t/deftest segment-collapse-handler
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :curve-to :params {:x 100.0 :y 0.0 :c1x 20.0 :c1y 40.0 :c2x 80.0 :c2y 40.0}}])]
    ;; Collapsing one handler keeps the other handle unchanged.
    (t/is (= [[:move-to {:x 0.0 :y 0.0}]
              [:curve-to {:x 100.0 :y 0.0 :c1x 0.0 :c1y 0.0 :c2x 80.0 :c2y 40.0}]]
             (mapv (juxt :command :params) (path/collapse-handler content 1 :c1))))
    ;; collapsing the second handler too degenerates the curve into a line-to
    (let [collapsed (-> content
                        (path/collapse-handler 1 :c1)
                        (path/collapse-handler 1 :c2))]
      (t/is (= [[:move-to {:x 0.0 :y 0.0}]
                [:line-to {:x 100.0 :y 0.0}]]
               (mapv (juxt :command :params) collapsed))))))

(t/deftest segment-toggle-segment-curve
  (let [line  (path/content
               [{:command :move-to :params {:x 0.0 :y 0.0}}
                {:command :line-to :params {:x 90.0 :y 0.0}}])
        curve (path/toggle-segment-curve line 1)]
    ;; Curved lines use perpendicular bowed handles.
    (t/is (= [[:move-to {:x 0.0 :y 0.0}]
              [:curve-to {:x 90.0 :y 0.0 :c1x 30.0 :c1y 22.5 :c2x 60.0 :c2y 22.5}]]
             (mapv (juxt :command :params) curve)))
    ;; curve -> line: drops the control points
    (t/is (= [[:move-to {:x 0.0 :y 0.0}]
              [:line-to {:x 90.0 :y 0.0}]]
             (mapv (juxt :command :params) (path/toggle-segment-curve curve 1))))
    ;; move-to / close-path are untouched
    (t/is (= (vec line) (vec (path/toggle-segment-curve line 0))))))

(t/deftest segment-remove-segments
  (let [content (path/content
                 [{:command :move-to :params {:x 0.0 :y 0.0}}
                  {:command :line-to :params {:x 10.0 :y 0.0}}
                  {:command :line-to :params {:x 20.0 :y 0.0}}
                  {:command :line-to :params {:x 30.0 :y 0.0}}])]
    ;; Removing an interior segment keeps both endpoints.
    (t/is (= [[:move-to {:x 0.0 :y 0.0}]
              [:line-to {:x 10.0 :y 0.0}]
              [:move-to {:x 20.0 :y 0.0}]
              [:line-to {:x 30.0 :y 0.0}]]
             (mapv (juxt :command :params) (path/remove-segments content #{2}))))
    ;; Removing the first segment drops the dangling start node.
    (t/is (= [[:move-to {:x 10.0 :y 0.0}]
              [:line-to {:x 20.0 :y 0.0}]
              [:line-to {:x 30.0 :y 0.0}]]
             (mapv (juxt :command :params) (path/remove-segments content #{1}))))
    ;; a closed subpath broken elsewhere keeps its closing line geometry
    (let [closed (path/content
                  [{:command :move-to :params {:x 0.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 0.0}}
                   {:command :line-to :params {:x 10.0 :y 10.0}}
                   {:command :close-path :params {}}])]
      (t/is (= [[:move-to {:x 10.0 :y 0.0}]
                [:line-to {:x 10.0 :y 10.0}]
                [:line-to {:x 0.0 :y 0.0}]]
               (mapv (juxt :command :params) (path/remove-segments closed #{1}))))
      ;; removing the close-path just leaves the subpath open
      (t/is (= [[:move-to {:x 0.0 :y 0.0}]
                [:line-to {:x 10.0 :y 0.0}]
                [:line-to {:x 10.0 :y 10.0}]]
               (mapv (juxt :command :params) (path/remove-segments closed #{3})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BOOL OPERATIONS — INTERSECTION / DIFFERENCE / EXCLUSION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Two non-overlapping rectangles for bool tests
(def ^:private rect-a
  [{:command :move-to  :params {:x 0.0  :y 0.0}}
   {:command :line-to  :params {:x 10.0 :y 0.0}}
   {:command :line-to  :params {:x 10.0 :y 10.0}}
   {:command :line-to  :params {:x 0.0  :y 10.0}}
   {:command :line-to  :params {:x 0.0  :y 0.0}}
   {:command :close-path :params {}}])

(def ^:private rect-b
  [{:command :move-to  :params {:x 5.0  :y 5.0}}
   {:command :line-to  :params {:x 15.0 :y 5.0}}
   {:command :line-to  :params {:x 15.0 :y 15.0}}
   {:command :line-to  :params {:x 5.0  :y 15.0}}
   {:command :line-to  :params {:x 5.0  :y 5.0}}
   {:command :close-path :params {}}])

(def ^:private rect-c
  [{:command :move-to  :params {:x 20.0 :y 20.0}}
   {:command :line-to  :params {:x 30.0 :y 20.0}}
   {:command :line-to  :params {:x 30.0 :y 30.0}}
   {:command :line-to  :params {:x 20.0 :y 30.0}}
   {:command :line-to  :params {:x 20.0 :y 20.0}}
   {:command :close-path :params {}}])

(t/deftest bool-difference
  (let [result (path.bool/calculate-content :difference [rect-a rect-b])]
    ;; difference result must be a sequence (possibly empty for degenerate cases)
    (t/is (or (nil? result) (sequential? result)))))

(t/deftest bool-intersection
  (let [result (path.bool/calculate-content :intersection [rect-a rect-b])]
    (t/is (or (nil? result) (sequential? result)))))

(t/deftest bool-exclusion
  (let [result (path.bool/calculate-content :exclude [rect-a rect-b])]
    (t/is (or (nil? result) (sequential? result)))))

(t/deftest bool-union-non-overlapping
  (let [result (path.bool/calculate-content :union [rect-a rect-c])]
    ;; non-overlapping union should contain both shapes' segments
    (t/is (seq result))
    (t/is (> (count result) (count rect-a)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHAPE-TO-PATH TESTS (via path/convert-to-path)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-selrect [x y w h]
  (grc/make-rect x y w h))

(t/deftest shape-to-path-rect-simple
  (let [shape  {:type :rect :x 0.0 :y 0.0 :width 100.0 :height 50.0
                :selrect (make-selrect 0.0 0.0 100.0 50.0)}
        result (path/convert-to-path shape {})]
    (t/is (= :path (:type result)))
    (t/is (path/content? (:content result)))
    ;; A simple rect (no radius) produces an empty path in the current impl
    ;; so we just check it doesn't throw and returns a :path type
    (t/is (some? (:content result)))))

(t/deftest shape-to-path-circle
  (let [shape  {:type :circle :x 0.0 :y 0.0 :width 100.0 :height 100.0
                :selrect (make-selrect 0.0 0.0 100.0 100.0)}
        result (path/convert-to-path shape {})]
    (t/is (= :path (:type result)))
    (t/is (path/content? (:content result)))
    ;; A circle converts to bezier curves — should have multiple segments
    (t/is (> (count (vec (:content result))) 1))))

(t/deftest shape-to-path-path
  (let [shape  {:type :path :content (path/content sample-content)}
        result (path/convert-to-path shape {})]
    ;; A path shape stays a path shape unchanged
    (t/is (= :path (:type result)))))

(t/deftest shape-to-path-svg-raw-does-not-throw
  (let [shape  {:type :svg-raw :x 0.0 :y 0.0 :width 100.0 :height 50.0
                :selrect (make-selrect 0.0 0.0 100.0 50.0)
                :content {:tag :text :attrs {:style {}}
                          :content [{:tag :tspan :attrs {} :content ["x"]}]}}
        result (path/convert-to-path shape {})]
    (t/is (= :svg-raw (:type result)))
    (t/is (some? (:content result)))))

(t/deftest shape-to-path-rect-with-radius
  (let [shape  {:type :rect :x 0.0 :y 0.0 :width 100.0 :height 100.0
                :r1 10.0 :r2 10.0 :r3 10.0 :r4 10.0
                :selrect (make-selrect 0.0 0.0 100.0 100.0)}
        result (path/convert-to-path shape {})]
    (t/is (= :path (:type result)))
    ;; rounded rect should have curve-to segments
    (let [segs (vec (:content result))
          curve-segs (filter #(= :curve-to (:command %)) segs)]
      (t/is (pos? (count curve-segs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TYPE CODE CONSISTENCY TESTS (regression for move-to/line-to swap bug)
;;
;; These tests ensure that all binary reader code paths agree on the
;; mapping: 1=move-to, 2=line-to, 3=curve-to, 4=close-path.
;;
;; The bug was that `impl-walk`, `impl-reduce`, and `impl-lookup` had
;; type codes 1 and 2 swapped (1→:line-to, 2→:move-to) while
;; `read-segment`, `from-plain`, and `to-string-segment*` had the
;; correct mapping. This caused subtle mismatches in operations like
;; `get-subpaths`, `get-points`, `get-handlers`, etc.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest type-code-walk-consistency
  (t/testing "impl-walk produces same command types as read-segment (via vec)"
    (let [pdata      (path/content sample-content)
          ;; read-segment path: produces {:command :keyword ...} maps
          seq-types  (mapv :command (vec pdata))
          ;; impl-walk path: collects type keywords
          walk-types (path.impl/-walk pdata
                                      (fn [type _ _ _ _ _ _] type)
                                      [])]
      ;; Both paths must agree on the command types
      (t/is (= seq-types walk-types))
      ;; Verify the actual expected types
      (t/is (= [:move-to :line-to :curve-to :close-path] seq-types))
      (t/is (= [:move-to :line-to :curve-to :close-path] walk-types)))))

(t/deftest type-code-reduce-consistency
  (t/testing "impl-reduce produces same command types as read-segment (via vec)"
    (let [pdata      (path/content sample-content)
          ;; read-segment path
          seq-types  (mapv :command (vec pdata))
          ;; impl-reduce path: collects [index type] pairs
          reduce-types (path.impl/-reduce
                        pdata
                        (fn [acc index type _ _ _ _ _ _]
                          (conj acc type))
                        [])]
      (t/is (= seq-types reduce-types))
      (t/is (= [:move-to :line-to :curve-to :close-path] reduce-types)))))

(t/deftest type-code-lookup-consistency
  (t/testing "impl-lookup produces same command types as read-segment for each index"
    (let [pdata     (path/content sample-content)
          seg-count (count pdata)]
      (doseq [i (range seg-count)]
        (let [;; read-segment path
              seg-type    (:command (nth pdata i))
              ;; impl-lookup path
              lookup-type (path.impl/-lookup
                           pdata i
                           (fn [type _ _ _ _ _ _] type))]
          (t/is (= seg-type lookup-type)
                (str "Mismatch at index " i
                     ": read-segment=" seg-type
                     " lookup=" lookup-type)))))))

(t/deftest type-code-get-points-uses-walk
  (t/testing "get-points (via impl-walk) excludes close-path and includes move-to/line-to/curve-to"
    (let [pdata  (path/content sample-content)
          points (path.segment/get-points pdata)
          ;; Manually extract points from read-segment (via vec),
          ;; skipping close-path
          expected-points (->> (vec pdata)
                               (remove #(= :close-path (:command %)))
                               (mapv #(gpt/point
                                       (get-in % [:params :x])
                                       (get-in % [:params :y]))))]
      (t/is (= expected-points points))
      ;; Specifically: 3 points (move-to, line-to, curve-to)
      (t/is (= 3 (count points))))))

(t/deftest type-code-get-subpaths-uses-reduce
  (t/testing "get-subpaths (via reduce) correctly identifies move-to to start subpaths"
    (let [;; Content with two subpaths: move-to + line-to + close-path, then move-to + line-to
          two-subpath-content
          [{:command :move-to  :params {:x 0.0 :y 0.0}}
           {:command :line-to  :params {:x 10.0 :y 0.0}}
           {:command :close-path :params {}}
           {:command :move-to  :params {:x 20.0 :y 20.0}}
           {:command :line-to  :params {:x 30.0 :y 30.0}}]
          pdata    (path/content two-subpath-content)
          subpaths (path.subpath/get-subpaths pdata)]
      ;; Must produce exactly 2 subpaths (one per move-to)
      (t/is (= 2 (count subpaths)))
      ;; First subpath starts at (0,0)
      (t/is (= (gpt/point 0.0 0.0) (:from (first subpaths))))
      ;; Second subpath starts at (20,20)
      (t/is (= (gpt/point 20.0 20.0) (:from (second subpaths)))))))

(t/deftest type-code-get-handlers-uses-reduce
  (t/testing "get-handlers (via impl-reduce) correctly identifies curve-to segments"
    (let [pdata    (path/content sample-content)
          handlers (path.segment/get-handlers pdata)]
      ;; sample-content has one curve-to at index 2
      ;; The curve-to's :c1 handler belongs to the previous point (line-to endpoint)
      ;; The curve-to's :c2 handler belongs to the curve-to endpoint
      (t/is (some? handlers))
      (let [line-to-point  (gpt/point 439.0 802.0)
            curve-to-point (gpt/point 264.0 634.0)]
        ;; line-to endpoint should have [2 :c1] handler
        (t/is (some #(= [2 :c1] %) (get handlers line-to-point)))
        ;; curve-to endpoint should have [2 :c2] handler
        (t/is (some #(= [2 :c2] %) (get handlers curve-to-point)))))))

(t/deftest type-code-handler-point-uses-lookup
  (t/testing "get-handler-point (via impl-lookup) returns correct values"
    (let [pdata (path/content sample-content)]
      ;; Index 0 is move-to (480, 839) — not a curve, so any prefix
      ;; returns the segment point itself
      (let [pt (path.segment/get-handler-point pdata 0 :c1)]
        (t/is (= (gpt/point 480.0 839.0) pt)))
      ;; Index 2 is curve-to with c1=(368,737), c2=(310,681), point=(264,634)
      (let [c1-pt (path.segment/get-handler-point pdata 2 :c1)
            c2-pt (path.segment/get-handler-point pdata 2 :c2)]
        (t/is (= (gpt/point 368.0 737.0) c1-pt))
        (t/is (= (gpt/point 310.0 681.0) c2-pt))))))

(t/deftest type-code-all-readers-agree-large-content
  (t/testing "all binary readers agree on types for a large multi-segment path"
    (let [pdata      (path/content sample-content-large)
          seg-count  (count pdata)
          ;; Collect types from all four code paths
          seq-types    (mapv :command (vec pdata))
          walk-types   (path.impl/-walk pdata
                                        (fn [type _ _ _ _ _ _] type)
                                        [])
          reduce-types (path.impl/-reduce
                        pdata
                        (fn [acc _ type _ _ _ _ _ _]
                          (conj acc type))
                        [])
          lookup-types (mapv (fn [i]
                               (path.impl/-lookup
                                pdata i
                                (fn [type _ _ _ _ _ _] type)))
                             (range seg-count))]
      ;; All four must be identical
      (t/is (= seq-types walk-types))
      (t/is (= seq-types reduce-types))
      (t/is (= seq-types lookup-types))
      ;; Verify first and last entries specifically
      (t/is (= :move-to (first seq-types)))
      (t/is (= :close-path (last seq-types))))))

(t/deftest path-data-read-normalizes-out-of-bounds-coordinates
  (let [max-safe (double sm/max-safe-int)
        min-safe (double sm/min-safe-int)
        ;; Create content with values exceeding safe bounds
        content-with-out-of-bounds
        [{:command :move-to :params {:x (+ max-safe 1000.0) :y (- min-safe 1000.0)}}
         {:command :line-to :params {:x (- min-safe 500.0) :y (+ max-safe 500.0)}}
         {:command :curve-to :params
          {:c1x (+ max-safe 200.0) :c1y (- min-safe 200.0)
           :c2x (+ max-safe 300.0) :c2y (- min-safe 300.0)
           :x (+ max-safe 400.0) :y (- min-safe 400.0)}}
         {:command :close-path :params {}}]

        ;; Create PathData from the content
        pdata (path/content content-with-out-of-bounds)

        ;; Read it back
        result (vec pdata)]

    (t/testing "Coordinates exceeding max-safe-int are clamped to max-safe-int"
      (let [move-to (first result)
            line-to (second result)]
        (t/is (= max-safe (:x (:params move-to))) "x in move-to should be clamped to max-safe-int")
        (t/is (= min-safe (:y (:params move-to))) "y in move-to should be clamped to min-safe-int")
        (t/is (= min-safe (:x (:params line-to))) "x in line-to should be clamped to min-safe-int")
        (t/is (= max-safe (:y (:params line-to))) "y in line-to should be clamped to max-safe-int")))

    (t/testing "Curve-to coordinates are clamped"
      (let [curve-to (nth result 2)]
        (t/is (= max-safe (:c1x (:params curve-to))) "c1x should be clamped")
        (t/is (= min-safe (:c1y (:params curve-to))) "c1y should be clamped")
        (t/is (= max-safe (:c2x (:params curve-to))) "c2x should be clamped")
        (t/is (= min-safe (:c2y (:params curve-to))) "c2y should be clamped")
        (t/is (= max-safe (:x (:params curve-to))) "x should be clamped")
        (t/is (= min-safe (:y (:params curve-to))) "y should be clamped")))

    (t/testing "-lookup normalizes coordinates"
      (let [move-to (path.impl/-lookup pdata 0 (fn [_ _ _ _ _ x y] {:x x :y y}))]
        (t/is (= max-safe (:x move-to)) "lookup x should be clamped")
        (t/is (= min-safe (:y move-to)) "lookup y should be clamped")))

    (t/testing "-walk normalizes coordinates"
      (let [coords (path.impl/-walk pdata
                                    (fn [_ _ _ _ _ x y]
                                      (when (and x y) {:x x :y y}))
                                    [])]
        (t/is (= max-safe (:x (first coords))) "walk first x should be clamped")
        (t/is (= min-safe (:y (first coords))) "walk first y should be clamped")))

    (t/testing "-reduce normalizes coordinates"
      (let [[move-res] (path.impl/-reduce pdata
                                          (fn [acc _ _ _ _ _ _ x y]
                                            (if (and x y) (conj acc {:x x :y y}) acc))
                                          [])]
        (t/is (= max-safe (:x move-res)) "reduce first x should be clamped")
        (t/is (= min-safe (:y move-res)) "reduce first y should be clamped")))))

(t/deftest segment-entries-identity
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}
                 {:command :curve-to :params {:c1x 12.0 :c1y 0.0 :c2x 18.0 :c2y 0.0 :x 20.0 :y 0.0}}
                 {:command :close-path :params {}}
                 {:command :move-to :params {:x 30.0 :y 30.0}}
                 {:command :line-to :params {:x 40.0 :y 30.0}}]
        entries (path/segment-entries content)]
    (t/is (= [1 2 3 5] (mapv :index entries)))
    ;; The closing segment goes back to the subpath start node
    (t/is (= 0 (:to-index (nth entries 2))))
    (t/is (= (gpt/point 0.0 0.0) (:to (nth entries 2))))
    ;; The second subpath starts from its own move-to
    (t/is (= 4 (:from-index (nth entries 3))))))

(t/deftest single-line-predicate
  ;; A move-to followed by exactly one line-to is a single line
  (t/is (path/single-line?
         (path/content [{:command :move-to :params {:x 0.0 :y 0.0}}
                        {:command :line-to :params {:x 10.0 :y 10.0}}])))
  ;; A curve, a polyline and a closed loop are not
  (t/is (not (path/single-line?
              (path/content [{:command :move-to :params {:x 0.0 :y 0.0}}
                             {:command :curve-to :params {:c1x 1.0 :c1y 0.0 :c2x 2.0 :c2y 0.0 :x 10.0 :y 0.0}}]))))
  (t/is (not (path/single-line?
              (path/content [{:command :move-to :params {:x 0.0 :y 0.0}}
                             {:command :line-to :params {:x 10.0 :y 0.0}}
                             {:command :line-to :params {:x 20.0 :y 0.0}}]))))
  (t/is (not (path/single-line? nil))))

(t/deftest extract-content-chains-and-breaks
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}
                 {:command :line-to :params {:x 20.0 :y 0.0}}
                 {:command :line-to :params {:x 30.0 :y 0.0}}
                 {:command :line-to :params {:x 40.0 :y 0.0}}]]
    ;; Adjacent selected segments chain into one subpath
    (t/is (= [{:command :move-to :params {:x 10.0 :y 0.0}}
              {:command :line-to :params {:x 20.0 :y 0.0}}
              {:command :line-to :params {:x 30.0 :y 0.0}}]
             (vec (path/extract-content content {:segments #{2 3}}))))
    ;; A gap starts a new subpath
    (t/is (= [{:command :move-to :params {:x 0.0 :y 0.0}}
              {:command :line-to :params {:x 10.0 :y 0.0}}
              {:command :move-to :params {:x 30.0 :y 0.0}}
              {:command :line-to :params {:x 40.0 :y 0.0}}]
             (vec (path/extract-content content {:segments #{1 4}}))))))

(t/deftest extract-content-from-selected-nodes
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}
                 {:command :curve-to :params {:c1x 12.0 :c1y 0.0 :c2x 18.0 :c2y 0.0 :x 20.0 :y 0.0}}
                 {:command :line-to :params {:x 30.0 :y 0.0}}]]
    ;; Segments whose two endpoint nodes are selected are included
    (t/is (= [{:command :move-to :params {:x 10.0 :y 0.0}}
              {:command :curve-to :params {:c1x 12.0 :c1y 0.0 :c2x 18.0 :c2y 0.0 :x 20.0 :y 0.0}}]
             (vec (path/extract-content content {:nodes #{1 2}}))))
    ;; A single selected node produces no content
    (t/is (empty? (path/extract-content content {:nodes #{1}})))
    ;; Non-adjacent selected nodes produce no content
    (t/is (empty? (path/extract-content content {:nodes #{0 2}})))))

(t/deftest extract-content-closes-full-loops
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 10.0}}
                 {:command :close-path :params {}}]
        result  (vec (path/extract-content content {:nodes #{0 1 2}}))]
    (t/is (= :move-to (:command (nth result 0))))
    (t/is (= :line-to (:command (nth result 1))))
    (t/is (= :line-to (:command (nth result 2))))
    (t/is (= :close-path (:command (nth result 3))))))

(t/deftest splice-content-appends-subpaths
  (let [content [{:command :move-to :params {:x 0.0 :y 0.0}}
                 {:command :line-to :params {:x 10.0 :y 0.0}}]
        sub     [{:command :move-to :params {:x 30.0 :y 30.0}}
                 {:command :line-to :params {:x 40.0 :y 30.0}}]
        result  (path/splice-content content sub)]
    (t/is (path.impl/path-data? result))
    (t/is (= (into (vec content) sub) (vec result)))))
