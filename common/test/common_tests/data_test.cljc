;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.data-test
  (:require
   [app.common.data :as d]
   [clojure.test :as t]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic Predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest boolean-or-nil-predicate
  (t/is (d/boolean-or-nil? nil))
  (t/is (d/boolean-or-nil? true))
  (t/is (d/boolean-or-nil? false))
  (t/is (not (d/boolean-or-nil? 0)))
  (t/is (not (d/boolean-or-nil? "")))
  (t/is (not (d/boolean-or-nil? :kw))))

(t/deftest in-range-predicate
  (t/is (d/in-range? 5 0))
  (t/is (d/in-range? 5 4))
  (t/is (not (d/in-range? 5 5)))
  (t/is (not (d/in-range? 5 -1)))
  (t/is (not (d/in-range? 0 0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Data Structures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest ordered-set-creation
  (let [s (d/ordered-set)]
    (t/is (d/ordered-set? s))
    (t/is (empty? s)))
  (let [s (d/ordered-set :a)]
    (t/is (d/ordered-set? s))
    (t/is (contains? s :a)))
  (let [s (d/ordered-set :a :b :c)]
    (t/is (d/ordered-set? s))
    (t/is (= (seq s) [:a :b :c]))))

(t/deftest ordered-set-preserves-order
  (let [s (d/ordered-set :c :a :b)]
    (t/is (= (seq s) [:c :a :b])))
  ;; Duplicates are ignored; order of first insertion is kept
  (let [s (-> (d/ordered-set) (conj :a) (conj :b) (conj :a))]
    (t/is (= (seq s) [:a :b]))))

(t/deftest ordered-map-creation
  (let [m (d/ordered-map)]
    (t/is (d/ordered-map? m))
    (t/is (empty? m)))
  (let [m (d/ordered-map :a 1)]
    (t/is (d/ordered-map? m))
    (t/is (= (get m :a) 1)))
  (let [m (d/ordered-map :a 1 :b 2)]
    (t/is (d/ordered-map? m))
    (t/is (= (keys m) [:a :b]))))

(t/deftest ordered-map-preserves-insertion-order
  (let [m (-> (d/ordered-map)
              (assoc :c 3)
              (assoc :a 1)
              (assoc :b 2))]
    (t/is (= (keys m) [:c :a :b]))))

(t/deftest oassoc-test
  ;; oassoc on nil creates a new ordered-map
  (let [m (d/oassoc nil :a 1 :b 2)]
    (t/is (d/ordered-map? m))
    (t/is (= (get m :a) 1))
    (t/is (= (get m :b) 2)))
  ;; oassoc on existing ordered-map updates it
  (let [m (d/oassoc (d/ordered-map :x 10) :y 20)]
    (t/is (= (get m :x) 10))
    (t/is (= (get m :y) 20))))

(t/deftest oassoc-in-test
  (let [m (d/oassoc-in nil [:a :b] 42)]
    (t/is (d/ordered-map? m))
    (t/is (= (get-in m [:a :b]) 42)))
  (let [m (-> (d/ordered-map)
              (d/oassoc-in [:x :y] 1)
              (d/oassoc-in [:x :z] 2))]
    (t/is (= (get-in m [:x :y]) 1))
    (t/is (= (get-in m [:x :z]) 2))))

(t/deftest oupdate-in-test
  (let [m (-> (d/ordered-map)
              (d/oassoc-in [:a :b] 10)
              (d/oupdate-in [:a :b] + 5))]
    (t/is (= (get-in m [:a :b]) 15))))

(t/deftest oassoc-before-test
  (let [m (-> (d/ordered-map)
              (assoc :a 1)
              (assoc :b 2)
              (assoc :c 3))
        m2 (d/oassoc-before m :b :x 99)]
    ;; :x should be inserted just before :b
    (t/is (= (keys m2) [:a :x :b :c]))
    (t/is (= (get m2 :x) 99)))
  ;; When before-k does not exist, assoc at the end
  (let [m  (-> (d/ordered-map) (assoc :a 1))
        m2 (d/oassoc-before m :z :x 99)]
    (t/is (= (get m2 :x) 99))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Set / Map Index Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest adds-at-index-test
  (let [s (d/ordered-set :a :b :c)
        s2 (d/adds-at-index s 1 :x)]
    (t/is (= (seq s2) [:a :x :b :c])))
  (let [s  (d/ordered-set :a :b :c)
        s2 (d/adds-at-index s 0 :x)]
    (t/is (= (seq s2) [:x :a :b :c])))
  (let [s  (d/ordered-set :a :b :c)
        s2 (d/adds-at-index s 3 :x)]
    (t/is (= (seq s2) [:a :b :c :x]))))

(t/deftest inserts-at-index-test
  (let [s  (d/ordered-set :a :b :c)
        s2 (d/inserts-at-index s 1 [:x :y])]
    (t/is (= (seq s2) [:a :x :y :b :c])))
  (let [s  (d/ordered-set :a :b :c)
        s2 (d/inserts-at-index s 0 [:x])]
    (t/is (= (seq s2) [:x :a :b :c]))))

(t/deftest addm-at-index-test
  (let [m  (-> (d/ordered-map) (assoc :a 1) (assoc :b 2) (assoc :c 3))
        m2 (d/addm-at-index m 1 :x 99)]
    (t/is (= (keys m2) [:a :x :b :c]))
    (t/is (= (get m2 :x) 99))))

(t/deftest insertm-at-index-test
  (let [m  (-> (d/ordered-map) (assoc :a 1) (assoc :b 2) (assoc :c 3))
        m2 (d/insertm-at-index m 1 (d/ordered-map :x 10 :y 20))]
    (t/is (= (keys m2) [:a :x :y :b :c]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Concat / remove helpers (pre-existing tests preserved)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
