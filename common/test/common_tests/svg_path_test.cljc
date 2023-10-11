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
