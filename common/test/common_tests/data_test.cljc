;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.data-test
  (:require
   [app.common.data :as d]
   [clojure.test :as t]))

(t/deftest concat-vec
  (t/is (= []    (d/concat-vec)))
  (t/is (= [1]   (d/concat-vec [1])))
  (t/is (= [1]   (d/concat-vec #{1})))
  (t/is (= [1 2] (d/concat-vec [1] #{2})))
  (t/is (= [1 2] (d/concat-vec '(1) [2]))))

(t/deftest concat-set
  (t/is (= #{} (d/concat-set)))
  (t/is (= #{1 2}
           (d/concat-set [1] [2]))))

(t/deftest remove-at-index
  (t/is (= [1 2 3 4]
           (d/remove-at-index [1 2 3 4 5] 4)))


  (t/is (= [1 2 3 4]
           (d/remove-at-index [5 1 2 3 4] 0)))

  (t/is (= [1 2 3 4]
           (d/remove-at-index [1 5 2 3 4] 1))))

(t/deftest with-next
  (t/is (= [[0 1] [1 2] [2 3] [3 4] [4 nil]]
           (d/with-next (range 5)))))

(t/deftest with-prev
  (t/is (= [[0 nil] [1 0] [2 1] [3 2] [4 3]]
           (d/with-prev (range 5)))))

(t/deftest with-prev-next
  (t/is (= [[0 nil 1] [1 0 2] [2 1 3] [3 2 4] [4 3 nil]]
           (d/with-prev-next (range 5)))))

(t/deftest join
  (t/is (= [[1 :a] [1 :b] [2 :a] [2 :b] [3 :a] [3 :b]]
           (d/join [1 2 3] [:a :b])))
  (t/is (= [1 10 100 2 20 200 3 30 300]
           (d/join [1 2 3] [1 10 100] *))))

(t/deftest num-predicate
  (t/is (not (d/num? ##NaN)))
  (t/is (not (d/num? nil)))
  (t/is (d/num? 1))
  (t/is (d/num? -0.3))
  (t/is (not (d/num? {}))))

(t/deftest check-num-helper
  (t/is (= 1 (d/check-num 1 0)))
  (t/is (= 0 (d/check-num ##NaN 0)))
  (t/is (= 0 (d/check-num {} 0)))
  (t/is (= 0 (d/check-num [] 0)))
  (t/is (= 0 (d/check-num :foo 0)))
  (t/is (= 0 (d/check-num nil 0))))

(t/deftest insert-at-index
  ;; insert different object
  (t/is (= (d/insert-at-index [:a :b] 1 [:c :d])
           [:a :c :d :b]))

  ;; insert on the start
  (t/is (= (d/insert-at-index [:a :b] 0 [:c])
           [:c :a :b]))

  ;; insert on the end 1
  (t/is (= (d/insert-at-index [:a :b] 2 [:c])
           [:a :b :c]))

  ;; insert on the end with not existing index
  (t/is (= (d/insert-at-index [:a :b] 10 [:c])
           [:a :b :c]))

  ;; insert existing in a contiguous index
  (t/is (= (d/insert-at-index [:a :b] 1 [:a])
           [:a :b]))

  ;; insert existing in the same index
  (t/is (= (d/insert-at-index [:a :b] 0 [:a])
           [:a :b]))

  ;; insert existing in other index case 1
  (t/is (= (d/insert-at-index [:a :b :c] 2 [:a])
           [:b :a :c]))

  ;; insert existing in other index case 2
  (t/is (= (d/insert-at-index [:a :b :c :d] 0 [:d])
           [:d :a :b :c]))

  ;; insert existing in other index case 3
  (t/is (= (d/insert-at-index [:a :b :c :d] 1 [:a])
           [:a :b :c :d])))

(t/deftest reorder
  (let [v ["a" "b" "c" "d"]]
    (t/is (= (d/reorder v 0 2) ["b" "a" "c" "d"]))
    (t/is (= (d/reorder v 0 3) ["b" "c" "a" "d"]))
    (t/is (= (d/reorder v 0 4) ["b" "c" "d" "a"]))
    (t/is (= (d/reorder v 3 0) ["d" "a" "b" "c"]))
    (t/is (= (d/reorder v 3 2) ["a" "b" "d" "c"]))
    (t/is (= (d/reorder v 0 5) ["b" "c" "d" "a"]))
    (t/is (= (d/reorder v 3 -1) ["d" "a" "b" "c"]))
    (t/is (= (d/reorder v 5 -1) ["d" "a" "b" "c"]))
    (t/is (= (d/reorder v -1 5) ["b" "c" "d" "a"]))))
