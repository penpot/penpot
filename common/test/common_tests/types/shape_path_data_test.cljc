;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.shape-path-data-test
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.pprint :as pp]
   [app.common.types.shape.path :as path]
   [clojure.test :as t]))

(def sample-content
  [{:command :move-to, :params {:x 480.0, :y 839.0}}
   {:command :line-to, :params {:x 439.0, :y 802.0}}
   {:command :curve-to, :params {:c1x 368.0, :c1y 737.0, :c2x 310.0, :c2y 681.0, :x 264.0, :y 634.0}}
   {:command :close-path :params {}}])

(def sample-bytes
  [0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 67 -16 0 0 68 81 -64 0
   0 2 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 67 -37 -128 0 68 72 -128 0
   0 3 0 0 67 -72 0 0 68 56 64 0 67 -101 0 0 68 42 64 0 67 -124 0 0 68 30 -128 0
   0 4 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0])

;; This means it implements IReduceInit/IReduce protocols
(t/deftest path-data-to-vector
  (let [pdata  (path/path-data sample-content)
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
  (let [pdata (path/path-data sample-content)]
    (t/is (= sample-bytes
             (vec
              #?(:cljs (js/Int8Array. (.-buffer pdata))
                 :clj  (.array (.-buffer pdata))))))
    (t/is (= (->> sample-content
                  (mapv path/map->PathSegment))
             (vec pdata)))))

