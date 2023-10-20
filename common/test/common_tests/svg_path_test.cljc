;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.svg-path-test
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.svg.path :as svg.path]
   [clojure.test :as t]
   #?(:cljs [common-tests.arc-to-bezier :as impl])))

(t/deftest arc-to-bezier-1
  (let [expected1 [-1.6697754290362354e-13
                   -5.258016244624741e-13
                   182.99396814652343
                   578.9410968299095
                   338.05561855139365
                   1059.4584670906731
                   346.33988979885567
                   1073.265585836443]
        expected2 [346.33988979885567
                   1073.265585836443
                   354.6241610463177
                   1087.0727045822134
                   212.99396814652377
                   628.9410968299106
                   30.00000000000016
                   50.000000000000504]]

    (let [[result1 result2 :as total] (svg.path/arc->beziers* 0 0 30 50 0 0 1 162.55 162.45)]
      (t/is (= (count total) 2))
      (dotimes [i (count result1)]
        (t/is (mth/close? (nth result1 i)
                          (nth expected1 i)
                          0.000000000001)))

      (dotimes [i (count result2)]
        (t/is (mth/close? (nth result2 i)
                          (nth expected2 i)
                          0.000000000001))))

    ))

;; "m -994.563 4564.1423 149.3086 -52.8821 30.1828 -1.9265 5.2446 -117.5157 98.6828 -43.7312 219.9492 9.5361 9.0977 121.0797 115.0586 12.7148 -1.1774 75.7109 134.7524 3.1787 -6.1008 85.0544 -137.3211 59.9137 -301.293 -1.0595 -51.375 25.7186 -261.0492 -7.706 " [[:x :number] [:y :number]]

(t/deftest extract-params-1
  (let [expected [{:x -994.563, :y 4564.1423}
                  {:x 149.3086, :y -52.8821}
                  {:x 30.1828, :y -1.9265}
                  {:x 5.2446, :y -117.5157}
                  {:x 98.6828, :y -43.7312}
                  {:x 219.9492, :y 9.5361}
                  {:x 9.0977, :y 121.0797}
                  {:x 115.0586, :y 12.7148}
                  {:x -1.1774, :y 75.7109}
                  {:x 134.7524, :y 3.1787}
                  {:x -6.1008, :y 85.0544}
                  {:x -137.3211, :y 59.9137}
                  {:x -301.293, :y -1.0595}
                  {:x -51.375, :y 25.7186}
                  {:x -261.0492, :y -7.706}]
        cmdstr (str "m -994.563 4564.1423 149.3086 -52.8821 30.1828 "
                    "-1.9265 5.2446 -117.5157 98.6828 -43.7312 219.9492 "
                    "9.5361 9.0977 121.0797 115.0586 12.7148 -1.1774 "
                    "75.7109 134.7524 3.1787 -6.1008 85.0544 -137.3211 "
                    "59.9137 -301.293 -1.0595 -51.375 25.7186 -261.0492 -7.706 ")
        pattern [[:x :number] [:y :number]]]

    (t/is (= expected (svg.path/extract-params cmdstr pattern)))))

(t/deftest extract-params-2
  (let [expected [{:x -994.563, :y 4564.1423 :r 0}]
        cmdstr (str "m -994.563 4564.1423 0")
        pattern [[:x :number] [:y :number] [:r :flag]]]

    (t/is (= expected (svg.path/extract-params cmdstr pattern)))))

