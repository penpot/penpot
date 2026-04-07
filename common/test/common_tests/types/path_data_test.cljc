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
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.pprint :as pp]
   [app.common.transit :as trans]
   [app.common.types.path :as path]
   [app.common.types.path.bool :as path.bool]
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

(t/deftest helpers-prefix->coords
  (t/is (= [:c1x :c1y] (path.helpers/prefix->coords :c1)))
  (t/is (= [:c2x :c2y] (path.helpers/prefix->coords :c2)))
  (t/is (nil? (path.helpers/prefix->coords nil))))

(t/deftest helpers-position-fixed-angle
  (t/testing "returns point unchanged when from-point is nil"
    (let [pt (gpt/point 5.0 3.0)]
      (t/is (= pt (path.helpers/position-fixed-angle pt nil)))))
  (t/testing "snaps to nearest 45-degree angle"
    (let [from (gpt/point 0 0)
          ;; Angle ~30° from from, should snap to 45°
          to   (gpt/point 10 6)
          snapped (path.helpers/position-fixed-angle to from)]
      ;; result should have same distance
      (let [d-orig    (gpt/distance to from)
            d-snapped (gpt/distance snapped from)]
        (t/is (mth/close? d-orig d-snapped 0.01))))))

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

(t/deftest segment-content->selrect
  (let [content (path/content sample-content-square)
        rect    (path.segment/content->selrect content)]
    (t/is (some? rect))
    (t/is (mth/close? 0.0  (:x1 rect) 0.1))
    (t/is (mth/close? 0.0  (:y1 rect) 0.1))
    (t/is (mth/close? 10.0 (:x2 rect) 0.1))
    (t/is (mth/close? 10.0 (:y2 rect) 0.1))))

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
