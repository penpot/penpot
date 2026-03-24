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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lazy / sequence helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest concat-all-test
  (t/is (= [1 2 3 4 5 6]
           (d/concat-all [[1 2] [3 4] [5 6]])))
  (t/is (= [] (d/concat-all [])))
  (t/is (= [1 2 3]
           (d/concat-all [[1] [2] [3]])))
  ;; It's lazy — works with infinite-ish inner seqs truncated by outer limit
  (t/is (= [0 1 2]
           (take 3 (d/concat-all (map list (range)))))))

(t/deftest mapcat-test
  (t/is (= [0 1 1 2 2 3]
           (d/mapcat (fn [x] [x (inc x)]) [0 1 2])))
  ;; fully lazy — can operate on infinite sequences
  (t/is (= [0 0 1 1 2 2]
           (take 6 (d/mapcat (fn [x] [x x]) (range))))))

(t/deftest zip-test
  (t/is (= [[1 :a] [2 :b] [3 :c]]
           (d/zip [1 2 3] [:a :b :c])))
  (t/is (= [] (d/zip [] []))))

(t/deftest zip-all-test
  ;; same length
  (t/is (= [[1 :a] [2 :b]]
           (d/zip-all [1 2] [:a :b])))
  ;; col1 longer — col2 padded with nils
  (t/is (= [[1 :a] [2 nil] [3 nil]]
           (d/zip-all [1 2 3] [:a])))
  ;; col2 longer — col1 padded with nils
  (t/is (= [[1 :a] [nil :b] [nil :c]]
           (d/zip-all [1] [:a :b :c]))))

(t/deftest enumerate-test
  (t/is (= [[0 :a] [1 :b] [2 :c]]
           (d/enumerate [:a :b :c])))
  (t/is (= [[5 :a] [6 :b]]
           (d/enumerate [:a :b] 5)))
  (t/is (= [] (d/enumerate []))))

(t/deftest interleave-all-test
  (t/is (= [] (d/interleave-all)))
  (t/is (= [1 2 3] (d/interleave-all [1 2 3])))
  (t/is (= [1 :a 2 :b 3 :c]
           (d/interleave-all [1 2 3] [:a :b :c])))
  ;; unequal lengths — longer seq is not truncated
  (t/is (= [1 :a 2 :b 3]
           (d/interleave-all [1 2 3] [:a :b])))
  (t/is (= [1 :a 2 :b :c]
           (d/interleave-all [1 2] [:a :b :c]))))

(t/deftest add-at-index-test
  (t/is (= [:a :x :b :c] (d/add-at-index [:a :b :c] 1 :x)))
  (t/is (= [:x :a :b :c] (d/add-at-index [:a :b :c] 0 :x)))
  (t/is (= [:a :b :c :x] (d/add-at-index [:a :b :c] 3 :x))))

(t/deftest take-until-test
  ;; stops (inclusive) when predicate is true
  (t/is (= [1 2 3] (d/take-until #(= % 3) [1 2 3 4 5])))
  ;; if predicate never true, returns whole collection
  (t/is (= [1 2 3] (d/take-until #(= % 9) [1 2 3])))
  ;; first element matches
  (t/is (= [1] (d/take-until #(= % 1) [1 2 3]))))

(t/deftest safe-subvec-test
  ;; normal range
  (t/is (= [2 3] (d/safe-subvec [1 2 3 4] 1 3)))
  ;; single arg — from index to end
  (t/is (= [2 3 4] (d/safe-subvec [1 2 3 4] 1)))
  ;; out-of-range returns nil
  (t/is (nil? (d/safe-subvec [1 2 3] 5)))
  (t/is (nil? (d/safe-subvec [1 2 3] 0 5)))
  ;; nil v returns nil
  (t/is (nil? (d/safe-subvec nil 0 1))))

(t/deftest domap-test
  (let [side-effects (atom [])
        result       (d/domap #(swap! side-effects conj %) [1 2 3])]
    (t/is (= [1 2 3] result))
    (t/is (= [1 2 3] @side-effects)))
  ;; transducer arity
  (let [side-effects (atom [])]
    (into [] (d/domap #(swap! side-effects conj %)) [4 5])
    (t/is (= [4 5] @side-effects))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collection lookup helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest group-by-test
  (t/is (= {:odd [1 3] :even [2 4]}
           (d/group-by #(if (odd? %) :odd :even) [1 2 3 4])))
  ;; two-arity with value function
  (t/is (= {:odd [10 30] :even [20 40]}
           (d/group-by #(if (odd? %) :odd :even) #(* % 10) [1 2 3 4])))
  ;; three-arity with initial value
  (t/is (= {:a #{1} :b #{2}}
           (d/group-by :k :v #{} [{:k :a :v 1} {:k :b :v 2}]))))

(t/deftest seek-test
  (t/is (= 3 (d/seek odd? [2 4 3 5])))
  (t/is (nil? (d/seek odd? [2 4 6])))
  (t/is (= :default (d/seek odd? [2 4 6] :default)))
  (t/is (= 1 (d/seek some? [nil nil 1 2]))))

(t/deftest index-by-test
  (t/is (= {1 {:id 1 :name "a"} 2 {:id 2 :name "b"}}
           (d/index-by :id [{:id 1 :name "a"} {:id 2 :name "b"}])))
  ;; two-arity with value fn
  (t/is (= {1 "a" 2 "b"}
           (d/index-by :id :name [{:id 1 :name "a"} {:id 2 :name "b"}]))))

(t/deftest index-of-pred-test
  (t/is (= 0 (d/index-of-pred [1 2 3] odd?)))
  (t/is (= 1 (d/index-of-pred [2 3 4] odd?)))
  (t/is (nil? (d/index-of-pred [2 4 6] odd?)))
  (t/is (nil? (d/index-of-pred [] odd?))))

(t/deftest index-of-test
  (t/is (= 0 (d/index-of [:a :b :c] :a)))
  (t/is (= 2 (d/index-of [:a :b :c] :c)))
  (t/is (nil? (d/index-of [:a :b :c] :z))))

(t/deftest replace-by-id-test
  (let [items [{:id 1 :v "a"} {:id 2 :v "b"} {:id 3 :v "c"}]
        new-v {:id 2 :v "x"}]
    (t/is (= [{:id 1 :v "a"} {:id 2 :v "x"} {:id 3 :v "c"}]
             (d/replace-by-id items new-v)))
    ;; transducer arity
    (t/is (= [{:id 1 :v "a"} {:id 2 :v "x"} {:id 3 :v "c"}]
             (sequence (d/replace-by-id new-v) items)))))

(t/deftest getf-test
  (let [m {:a 1 :b 2}
        get-from-m (d/getf m)]
    (t/is (= 1 (get-from-m :a)))
    (t/is (= 2 (get-from-m :b)))
    (t/is (nil? (get-from-m :z)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Map manipulation helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest vec-without-nils-test
  (t/is (= [1 2 3] (d/vec-without-nils [1 nil 2 nil 3])))
  (t/is (= [] (d/vec-without-nils [nil nil])))
  (t/is (= [1] (d/vec-without-nils [1]))))

(t/deftest without-nils-test
  (t/is (= {:a 1 :d 2} (d/without-nils {:a 1 :b nil :c nil :d 2 :e nil}))
        "removes all nil values")
  ;; transducer arity — works on map entries
  (t/is (= {:a 1} (into {} (d/without-nils) {:a 1 :b nil}))))

(t/deftest without-qualified-test
  (t/is (= {:a 1} (d/without-qualified {:a 1 :ns/b 2 :ns/c 3})))
  ;; transducer arity — works on map entries
  (t/is (= {:a 1} (into {} (d/without-qualified) {:a 1 :ns/b 2}))))

(t/deftest without-keys-test
  (t/is (= {:c 3} (d/without-keys {:a 1 :b 2 :c 3} [:a :b])))
  (t/is (= {:a 1 :b 2 :c 3} (d/without-keys {:a 1 :b 2 :c 3} []))))

(t/deftest deep-merge-test
  (t/is (= {:a 1 :b {:c 3 :d 4}}
           (d/deep-merge {:a 1 :b {:c 2 :d 4}} {:b {:c 3}})))
  ;; non-map values get replaced
  (t/is (= {:a 2}
           (d/deep-merge {:a 1} {:a 2})))
  ;; three-way merge
  (t/is (= {:a 1 :b 2 :c 3}
           (d/deep-merge {:a 1} {:b 2} {:c 3}))))

(t/deftest dissoc-in-test
  (t/is (= {:a {:b 1}} (d/dissoc-in {:a {:b 1 :c 2}} [:a :c])))
  ;; removes parent when child map becomes empty
  (t/is (= {} (d/dissoc-in {:a {:b 1}} [:a :b])))
  ;; no-op when path does not exist
  (t/is (= {:a 1} (d/dissoc-in {:a 1} [:b :c]))))

(t/deftest patch-object-test
  ;; normal update
  (t/is (= {:a 2 :b 2} (d/patch-object {:a 1 :b 2} {:a 2})))
  ;; nil value removes key
  (t/is (= {:b 2} (d/patch-object {:a 1 :b 2} {:a nil})))
  ;; nested map is merged recursively
  (t/is (= {:a {:x 10 :y 2}} (d/patch-object {:a {:x 1 :y 2}} {:a {:x 10}})))
  ;; nested nil removes nested key
  (t/is (= {:a {:y 2}} (d/patch-object {:a {:x 1 :y 2}} {:a {:x nil}})))
  ;; transducer arity (1-arg returns a fn)
  (let [f (d/patch-object {:a 99})]
    (t/is (= {:a 99 :b 2} (f {:a 1 :b 2})))))

(t/deftest without-obj-test
  (t/is (= [1 3] (d/without-obj [1 2 3] 2)))
  (t/is (= [1 2 3] (d/without-obj [1 2 3] 9)))
  (t/is (= [] (d/without-obj [1] 1))))

(t/deftest update-vals-test
  (t/is (= {:a 2 :b 4} (d/update-vals {:a 1 :b 2} #(* % 2))))
  (t/is (= {} (d/update-vals {} identity))))

(t/deftest update-in-when-test
  ;; key exists — applies function
  (t/is (= {:a {:b 2}} (d/update-in-when {:a {:b 1}} [:a :b] inc)))
  ;; key absent — returns unchanged
  (t/is (= {:a 1} (d/update-in-when {:a 1} [:b :c] inc))))

(t/deftest update-when-test
  ;; key exists — applies function
  (t/is (= {:a 2} (d/update-when {:a 1} :a inc)))
  ;; key absent — returns unchanged
  (t/is (= {:a 1} (d/update-when {:a 1} :b inc))))

(t/deftest assoc-in-when-test
  ;; key exists — updates value
  (t/is (= {:a {:b 99}} (d/assoc-in-when {:a {:b 1}} [:a :b] 99)))
  ;; key absent — returns unchanged
  (t/is (= {:a 1} (d/assoc-in-when {:a 1} [:b :c] 99))))

(t/deftest assoc-when-test
  ;; key exists — updates value
  (t/is (= {:a 99} (d/assoc-when {:a 1} :a 99)))
  ;; key absent — returns unchanged
  (t/is (= {:a 1} (d/assoc-when {:a 1} :b 99))))

(t/deftest merge-test
  (t/is (= {:a 1 :b 2 :c 3}
           (d/merge {:a 1} {:b 2} {:c 3})))
  (t/is (= {:a 2}
           (d/merge {:a 1} {:a 2})))
  (t/is (= {} (d/merge))))

(t/deftest txt-merge-test
  ;; sets value when not nil
  (t/is (= {:a 2 :b 2} (d/txt-merge {:a 1 :b 2} {:a 2})))
  ;; removes key when value is nil
  (t/is (= {:b 2} (d/txt-merge {:a 1 :b 2} {:a nil})))
  ;; adds new key
  (t/is (= {:a 1 :b 2 :c 3} (d/txt-merge {:a 1 :b 2} {:c 3}))))

(t/deftest mapm-test
  ;; two-arity: transform map in place
  (t/is (= {:a 2 :b 4} (d/mapm (fn [k v] (* v 2)) {:a 1 :b 2})))
  ;; one-arity: transducer
  (t/is (= {:a 10 :b 20}
           (into {} (d/mapm (fn [k v] (* v 10))) {:a 1 :b 2}))))

(t/deftest removev-test
  (t/is (= [2 4] (d/removev odd? [1 2 3 4])))
  (t/is (= [nil nil] (d/removev some? [nil nil])))
  (t/is (= [1 2 3] (d/removev nil? [1 nil 2 nil 3]))))

(t/deftest filterm-test
  (t/is (= {:a 1 :c 3} (d/filterm (fn [[_ v]] (odd? v)) {:a 1 :b 2 :c 3 :d 4}))
        "keeps entries where value is odd")
  (t/is (= {} (d/filterm (fn [[_ v]] (> v 10)) {:a 1 :b 2}))))

(t/deftest removem-test
  (t/is (= {:b 2 :d 4} (d/removem (fn [[_ v]] (odd? v)) {:a 1 :b 2 :c 3 :d 4})))
  (t/is (= {:a 1 :b 2} (d/removem (fn [[_ v]] (> v 10)) {:a 1 :b 2}))))

(t/deftest map-perm-test
  ;; default: all pairs
  (t/is (= [[1 2] [1 3] [1 4] [2 3] [2 4] [3 4]]
           (d/map-perm vector [1 2 3 4])))
  ;; with predicate
  (t/is (= [[1 3]]
           (d/map-perm vector (fn [a b] (and (odd? a) (odd? b))) [1 2 3])))
  ;; empty collection
  (t/is (= [] (d/map-perm vector []))))

(t/deftest distinct-xf-test
  (t/is (= [1 2 3]
           (into [] (d/distinct-xf identity) [1 2 1 3 2])))
  ;; keeps the first occurrence for each key
  (t/is (= [{:id 1 :v "a"} {:id 2 :v "x"}]
           (into [] (d/distinct-xf :id) [{:id 1 :v "a"} {:id 2 :v "x"} {:id 2 :v "b"}]))))

(t/deftest deep-mapm-test
  ;; Note: mfn is called twice on leaf entries (once initially, once again
  ;; after checking if the value is a map/vector), so a doubling fn applied
  ;; to value 1 gives 1*2*2=4.
  (t/is (= {:a 4 :b {:c 8}}
           (d/deep-mapm (fn [[k v]] [k (if (number? v) (* v 2) v)])
                        {:a 1 :b {:c 2}})))
  ;; Keyword renaming: keys are also transformed — and applied twice.
  ;; Use an idempotent key transformation (uppercase once = uppercase twice).
  (let [result (d/deep-mapm (fn [[k v]] [(keyword (str (name k) "!")) v])
                            {:a 1})]
    (t/is (contains? result (keyword "a!!")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Numeric helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest nan-test
  ;; Note: nan? behaves differently per platform:
  ;; - CLJS: uses js/isNaN, returns true for ##NaN
  ;; - CLJ: uses (not= v v); Clojure's = uses .equals on doubles,
  ;;   so (= ##NaN ##NaN) is true and nan? returns false for ##NaN.
  ;; Either way, nan? returns false for regular numbers and nil.
  (t/is (not (d/nan? 0)))
  (t/is (not (d/nan? 1)))
  (t/is (not (d/nan? nil)))
  ;; Platform-specific: JS nan? correctly detects NaN
  #?(:cljs (t/is (d/nan? ##NaN))))

(t/deftest safe-plus-test
  (t/is (= 5 (d/safe+ 3 2)))
  ;; when first arg is not finite, return it unchanged
  (t/is (= ##Inf (d/safe+ ##Inf 10))))

(t/deftest max-test
  (t/is (= 3 (d/max 3)))
  (t/is (= 5 (d/max 3 5)))
  (t/is (= 9 (d/max 1 9 4)))
  (t/is (= 10 (d/max 1 2 3 4 5 6 7 8 9 10))))

(t/deftest min-test
  (t/is (= 3 (d/min 3)))
  (t/is (= 3 (d/min 3 5)))
  (t/is (= 1 (d/min 1 9 4)))
  (t/is (= 1 (d/min 10 9 8 7 6 5 4 3 2 1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest parse-integer-test
  (t/is (= 42 (d/parse-integer "42")))
  (t/is (= -1 (d/parse-integer "-1")))
  (t/is (nil? (d/parse-integer "abc")))
  (t/is (= 0 (d/parse-integer "abc" 0)))
  (t/is (nil? (d/parse-integer nil))))

(t/deftest parse-double-test
  (t/is (= 3.14 (d/parse-double "3.14")))
  (t/is (= -1.0 (d/parse-double "-1.0")))
  (t/is (nil? (d/parse-double "abc")))
  (t/is (= 0.0 (d/parse-double "abc" 0.0)))
  (t/is (nil? (d/parse-double nil))))

(t/deftest parse-uuid-test
  (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"]
    (t/is (some? (d/parse-uuid uuid-str))))
  (t/is (nil? (d/parse-uuid "not-a-uuid")))
  (t/is (nil? (d/parse-uuid nil))))

(t/deftest coalesce-str-test
  ;; On JVM: nan? uses (not= v v), which is false for all normal values.
  ;; On CLJS: nan? uses js/isNaN, which is true for non-numeric strings.
  ;; coalesce-str returns default when value is nil or nan?.
  (t/is (= "default" (d/coalesce-str nil "default")))
  ;; Numbers always stringify on both platforms
  (t/is (= "42" (d/coalesce-str 42 "default")))
  ;; ##NaN: nan? is true in CLJS, returns default;
  ;;        nan? is false in CLJ, so str(##NaN)="NaN" is returned.
  #?(:cljs (t/is (= "default" (d/coalesce-str ##NaN "default"))))
  #?(:clj  (t/is (= "NaN" (d/coalesce-str ##NaN "default"))))
  ;; Strings: in CLJS js/isNaN("hello")=true so "default" is returned;
  ;;          in CLJ nan? is false so (str "hello")="hello" is returned.
  #?(:cljs (t/is (= "default" (d/coalesce-str "hello" "default"))))
  #?(:clj  (t/is (= "hello" (d/coalesce-str "hello" "default")))))

(t/deftest coalesce-test
  (t/is (= "hello" (d/coalesce "hello" "default")))
  (t/is (= "default" (d/coalesce nil "default")))
  ;; coalesce uses `or`, so false is falsy and returns the default
  (t/is (= "default" (d/coalesce false "default")))
  (t/is (= 42 (d/coalesce 42 0))))

(t/deftest read-string-test
  (t/is (= {:a 1} (d/read-string "{:a 1}")))
  (t/is (= [1 2 3] (d/read-string "[1 2 3]")))
  (t/is (= :keyword (d/read-string ":keyword"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String / keyword helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest name-test
  (t/is (= "foo" (d/name :foo)))
  (t/is (= "foo" (d/name "foo")))
  (t/is (nil? (d/name nil)))
  (t/is (= "42" (d/name 42))))

(t/deftest prefix-keyword-test
  (t/is (= :prefix-test (d/prefix-keyword "prefix-" :test)))
  (t/is (= :ns-id (d/prefix-keyword :ns- :id)))
  (t/is (= :ab (d/prefix-keyword "a" "b"))))

(t/deftest kebab-keys-test
  (t/is (= {:foo-bar 1 :baz-qux 2}
           (d/kebab-keys {"fooBar" 1 "bazQux" 2})))
  (t/is (= {:my-key {:nested-key 1}}
           (d/kebab-keys {:myKey {:nestedKey 1}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest regexp-test
  (t/is (d/regexp? #"foo"))
  (t/is (not (d/regexp? "foo")))
  (t/is (not (d/regexp? nil))))

(t/deftest nilf-test
  (let [safe-inc (d/nilf inc)]
    (t/is (nil? (safe-inc nil)))
    (t/is (= 2 (safe-inc 1))))
  (let [safe-add (d/nilf +)]
    (t/is (nil? (safe-add 1 nil)))
    (t/is (= 3 (safe-add 1 2)))))

(t/deftest nilv-test
  (t/is (= "default" (d/nilv nil "default")))
  (t/is (= "value" (d/nilv "value" "default")))
  (t/is (= false (d/nilv false "default")))
  ;; transducer arity
  (t/is (= ["a" "default" "b"]
           (into [] (d/nilv "default") ["a" nil "b"]))))

(t/deftest any-key-test
  (t/is (d/any-key? {:a 1 :b 2} :a))
  (t/is (d/any-key? {:a 1 :b 2} :z :b))
  (t/is (not (d/any-key? {:a 1} :z :x))))

(t/deftest tap-test
  (let [received (atom nil)]
    (t/is (= [1 2 3] (d/tap #(reset! received %) [1 2 3])))
    (t/is (= [1 2 3] @received))))

(t/deftest tap-r-test
  (let [received (atom nil)]
    (t/is (= [1 2 3] (d/tap-r [1 2 3] #(reset! received %))))
    (t/is (= [1 2 3] @received))))

(t/deftest map-diff-test
  ;; identical maps produce empty diff
  (t/is (= {} (d/map-diff {:a 1} {:a 1})))
  ;; changed value
  (t/is (= {:a [1 2]} (d/map-diff {:a 1} {:a 2})))
  ;; removed key
  (t/is (= {:b [2 nil]} (d/map-diff {:a 1 :b 2} {:a 1})))
  ;; added key
  (t/is (= {:c [nil 3]} (d/map-diff {:a 1} {:a 1 :c 3})))
  ;; nested diff
  (t/is (= {:b {:c [1 2]}} (d/map-diff {:b {:c 1}} {:b {:c 2}}))))

(t/deftest unique-name-test
  ;; name not in used set — returned as-is
  (t/is (= "foo" (d/unique-name "foo" #{})))
  ;; name already used — append counter
  (t/is (= "foo-1" (d/unique-name "foo" #{"foo"})))
  (t/is (= "foo-2" (d/unique-name "foo" #{"foo" "foo-1"})))
  ;; name already has numeric suffix
  (t/is (= "foo-2" (d/unique-name "foo-1" #{"foo-1"})))
  ;; prefix-first? mode — skips foo-1 (counter=1 returns bare prefix)
  ;; so with #{} not used, still returns "foo"
  (t/is (= "foo" (d/unique-name "foo" #{} true)))
  ;; with prefix-first? and "foo" used, counter=1 produces "foo" again (used),
  ;; so jumps to counter=2 → "foo-2"
  (t/is (= "foo-2" (d/unique-name "foo" #{"foo"} true))))

(t/deftest toggle-selection-test
  ;; without toggle, always returns set with just the value
  (let [s (d/ordered-set :a :b)]
    (t/is (= (d/ordered-set :c) (d/toggle-selection s :c))))
  ;; with toggle=true, adds if not present
  (let [s (d/ordered-set :a)]
    (t/is (contains? (d/toggle-selection s :b true) :b)))
  ;; with toggle=true, removes if already present
  (let [s (d/ordered-set :a :b)]
    (t/is (not (contains? (d/toggle-selection s :a true) :a)))))

(t/deftest invert-map-test
  (t/is (= {1 :a 2 :b} (d/invert-map {:a 1 :b 2})))
  (t/is (= {} (d/invert-map {}))))

(t/deftest obfuscate-string-test
  ;; short string (< 10) — all stars
  (t/is (= "****" (d/obfuscate-string "abcd")))
  ;; long string — first 5 chars kept
  (t/is (= "hello*****" (d/obfuscate-string "helloworld")))
  ;; full? mode
  (t/is (= "***" (d/obfuscate-string "abc" true)))
  ;; empty string
  (t/is (= "" (d/obfuscate-string ""))))

(t/deftest unstable-sort-test
  (t/is (= [1 2 3 4] (d/unstable-sort [3 1 4 2])))
  ;; In CLJS, garray/sort requires a comparator returning -1/0/1 (not boolean).
  ;; Use compare with reversed args for descending sort on both platforms.
  (t/is (= [4 3 2 1] (d/unstable-sort #(compare %2 %1) [3 1 4 2])))
  ;; Empty collection: CLJ returns '(), CLJS returns nil (from seq on [])
  (t/is (empty? (d/unstable-sort []))))

(t/deftest opacity-to-hex-test
  ;; opacity-to-hex uses JavaScript number methods (.toString 16 / .padStart)
  ;; so it only produces output in CLJS environments.
  #?(:cljs (t/is (= "ff" (d/opacity-to-hex 1))))
  #?(:cljs (t/is (= "00" (d/opacity-to-hex 0))))
  #?(:cljs (t/is (= "80" (d/opacity-to-hex (/ 128 255)))))
  #?(:clj (t/is true "opacity-to-hex is CLJS-only")))

(t/deftest format-precision-test
  (t/is (= "12" (d/format-precision 12.0123 0)))
  (t/is (= "12" (d/format-precision 12.0123 1)))
  (t/is (= "12.01" (d/format-precision 12.0123 2)))
  (t/is (= "12.012" (d/format-precision 12.0123 3)))
  (t/is (= "0.1" (d/format-precision 0.1 2))))

(t/deftest format-number-test
  (t/is (= "3.14" (d/format-number 3.14159)))
  (t/is (= "3" (d/format-number 3.0)))
  (t/is (= "3.14" (d/format-number "3.14159")))
  (t/is (nil? (d/format-number nil)))
  (t/is (= "3.1416" (d/format-number 3.14159 {:precision 4}))))

(t/deftest append-class-test
  (t/is (= "foo bar" (d/append-class "foo" "bar")))
  (t/is (= "bar" (d/append-class nil "bar")))
  (t/is (= " bar" (d/append-class "" "bar"))))
