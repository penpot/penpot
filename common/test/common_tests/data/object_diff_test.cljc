;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.data.object-diff-test
  (:require
   [app.common.data :as d]
   [app.common.data.object-diff :as od]
   [app.common.schema.test :as smt]
   [clojure.test :as t]
   [common-tests.data.object-diff-generators :as gen]))

(defrecord SampleRecord [a b])
(defrecord OtherRecord [a b])

(defn- round-trip
  [old new]
  (od/apply-diff old (od/diff old new)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; app.common.data/map-diff: pinned behavior and limitations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest map-diff-basic-test
  (t/testing "identical maps produce empty diff"
    (t/is (= {} (d/map-diff {:a 1} {:a 1}))))
  (t/testing "changed value"
    (t/is (= {:a [1 2]} (d/map-diff {:a 1} {:a 2}))))
  (t/testing "removed key"
    (t/is (= {:b [2 nil]} (d/map-diff {:a 1 :b 2} {:a 1}))))
  (t/testing "added key"
    (t/is (= {:c [nil 3]} (d/map-diff {:a 1} {:a 1 :c 3}))))
  (t/testing "nested diff"
    (t/is (= {:b {:c [1 2]}} (d/map-diff {:b {:c 1}} {:b {:c 2}})))))

(t/deftest map-diff-vector-values-test
  (t/testing "the whole previous and new vectors are saved"
    (t/is (= {:a [[1 2 3] [1 2 4]]}
             (d/map-diff {:a [1 2 3]} {:a [1 2 4]}))))

  (t/testing "equal vectors produce no diff, order matters"
    (t/is (= {} (d/map-diff {:a [1 2]} {:a [1 2]})))
    (t/is (= {:a [[1 2] [2 1]]}
             (d/map-diff {:a [1 2]} {:a [2 1]}))))

  (t/testing "empty vectors"
    (t/is (= {} (d/map-diff {:a []} {:a []})))
    (t/is (= {:a [[] [1]]}
             (d/map-diff {:a []} {:a [1]}))))

  (t/testing "vectors of maps are not diffed element by element"
    (t/is (= {:a [[{:x 1}] [{:x 2}]]}
             (d/map-diff {:a [{:x 1}]} {:a [{:x 2}]}))))

  (t/testing "vector against map, scalar or set is a whole replacement"
    (t/is (= {:a [[1] {:x 1}]}
             (d/map-diff {:a [1]} {:a {:x 1}})))
    (t/is (= {:a [[1] 2]}
             (d/map-diff {:a [1]} {:a 2})))
    (t/is (= {:a [[1 2] #{1 2}]}
             (d/map-diff {:a [1 2]} {:a #{1 2}}))))

  (t/testing "vectors nested inside maps"
    (t/is (= {:b {:v [[1] [2]]}}
             (d/map-diff {:b {:v [1]}} {:b {:v [2]}}))))

  (t/testing "other sequential values are also compared whole"
    (t/is (= {:a ['(1 2) '(1 3)]}
             (d/map-diff {:a '(1 2)} {:a '(1 3)})))))

(t/deftest map-diff-set-values-test
  (t/testing "the whole previous and new sets are saved"
    (t/is (= {:a [#{1 2} #{1 3}]}
             (d/map-diff {:a #{1 2}} {:a #{1 3}}))))

  (t/testing "set equality ignores order"
    (t/is (= {} (d/map-diff {:a #{1 2}} {:a #{2 1}}))))

  (t/testing "set against vector or scalar is a whole replacement"
    (t/is (= {:a [#{1} [1]]}
             (d/map-diff {:a #{1}} {:a [1]})))
    (t/is (= {:a [#{1} 2]}
             (d/map-diff {:a #{1}} {:a 2})))))

(t/deftest map-diff-key-types-test
  (t/testing "string, integer, boolean, symbol and nil keys"
    (t/is (= {"a" [1 2]} (d/map-diff {"a" 1} {"a" 2})))
    (t/is (= {1 [1 2]} (d/map-diff {1 1} {1 2})))
    (t/is (= {true [1 2]} (d/map-diff {true 1} {true 2})))
    (t/is (= {'a [1 2]} (d/map-diff {'a 1} {'a 2})))
    (t/is (= {nil [1 2]} (d/map-diff {nil 1} {nil 2}))))
  (t/testing "uuid keys, as in objects maps"
    (let [id (d/parse-uuid "00000000-0000-4000-8000-000000000001")]
      (t/is (= {id {:x [1 2]}}
               (d/map-diff {id {:x 1}} {id {:x 2}}))))))

(t/deftest map-diff-falsy-values-test
  (t/testing "false, 0 and empty string are values, not absence"
    (t/is (= {:a [false true]}
             (d/map-diff {:a false} {:a true})))
    (t/is (= {:a [0 1]}
             (d/map-diff {:a 0} {:a 1})))
    (t/is (= {:a ["" "x"]}
             (d/map-diff {:a ""} {:a "x"})))
    (t/is (= {} (d/map-diff {:a false :b 0 :c ""}
                            {:a false :b 0 :c ""}))))
  (t/testing "falsy value against a missing key"
    (t/is (= {:a [false nil]}
             (d/map-diff {:a false} {})))
    (t/is (= {:a [nil false]}
             (d/map-diff {} {:a false})))))

(t/deftest map-diff-empty-maps-test
  (t/testing "empty maps"
    (t/is (= {} (d/map-diff {} {})))
    (t/is (= {} (d/map-diff nil nil)))
    (t/is (= {:a [nil 1]} (d/map-diff nil {:a 1})))
    (t/is (= {:a [1 nil]} (d/map-diff {:a 1} nil))))
  (t/testing "empty nested maps"
    (t/is (= {} (d/map-diff {:a {}} {:a {}})))
    (t/is (= {:a {:b [nil 1]}}
             (d/map-diff {:a {}} {:a {:b 1}})))
    (t/is (= {:a {:b [1 nil]}}
             (d/map-diff {:a {:b 1}} {:a {}})))
    (t/is (= {:a [{} nil]}
             (d/map-diff {:a {}} {})))
    (t/is (= {:a [nil {}]}
             (d/map-diff {} {:a {}})))))

(t/deftest map-diff-nil-limitation-test
  ;; map-diff compares with get, which reads both a missing key and a
  ;; key holding nil as nil. These tests pin that limitation of
  ;; map-diff; app.common.data.object-diff/diff does tell them apart.
  (t/testing "missing key and nil value are indistinguishable"
    (t/is (= {} (d/map-diff {:a nil} {})))
    (t/is (= {} (d/map-diff {} {:a nil}))))
  (t/testing "changing a value to nil looks like removing the key"
    (t/is (= {:a [1 nil]}
             (d/map-diff {:a 1} {:a nil}))))
  (t/testing "a nested nil-only change produces an empty nested diff"
    (t/is (= {:b {}}
             (d/map-diff {:b {:a nil}} {:b {}})))))

(t/deftest map-diff-nan-test
  ;; NaN is not equal to itself, so map-diff always reports it as
  ;; changed. The same holds for object-diff, which uses = as well.
  (t/testing "NaN always diffs, even against itself"
    (let [diff (d/map-diff {:a ##NaN} {:a ##NaN})]
      (t/is (contains? diff :a))
      (t/is (vector? (:a diff))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; diff: output shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest diff-equal-values-test
  (t/testing "identical values produce an empty diff"
    (t/is (= [] (od/diff nil nil)))
    (t/is (= [] (od/diff 1 1)))
    (t/is (= [] (od/diff :a :a)))
    (t/is (= [] (od/diff {} {})))
    (t/is (= [] (od/diff [] [])))
    (t/is (= [] (od/diff {:a 1 :b {:c 2}} {:a 1 :b {:c 2}})))
    (t/is (= [] (od/diff {:a [1 2]} {:a [1 2]})))
    (t/is (= [] (od/diff {:a #{1 2}} {:a #{2 1}})))
    (t/is (= [] (od/diff (->SampleRecord 1 2)
                         (->SampleRecord 1 2))))))

(t/deftest diff-map-test
  (t/testing "changed, added and removed keys share one group"
    (t/is (= [{:path [] :set [[:a 2]] :add [[:c 3]] :del [:b]}]
             (od/diff {:a 1 :b 2} {:a 2 :c 3}))))

  (t/testing "equal values under a key produce no operation"
    (t/is (= [[:set [:b] 3]]
             (od/diff {:a 1 :b 2} {:a 1 :b 3}))))

  (t/testing "nested maps produce leaf operations"
    (t/is (= [[:set [:b :c] 2]]
             (od/diff {:b {:c 1}} {:b {:c 2}}))))

  (t/testing "a nested map against a scalar is a whole replacement"
    (t/is (= [[:set [:b] 5]]
             (od/diff {:b {:c 1}} {:b 5})))))

(t/deftest diff-map-nil-test
  (t/testing "an added key holding nil is an :add"
    (t/is (= [[:add [:a] nil]]
             (od/diff {} {:a nil}))))

  (t/testing "a removed key holding nil is a :del"
    (t/is (= [[:del [:a]]]
             (od/diff {:a nil} {}))))

  (t/testing "a present nil key is not an absent key"
    (t/is (= [] (od/diff {:a nil} {:a nil})))
    (t/is (= [[:set [:a] nil]]
             (od/diff {:a 1} {:a nil})))
    (t/is (= [[:set [:a] 1]]
             (od/diff {:a nil} {:a 1}))))

  (t/testing "nested nil changes"
    (t/is (= [[:del [:b :a]]]
             (od/diff {:b {:a nil}} {:b {}})))
    (t/is (= [[:add [:b :a] nil]]
             (od/diff {:b {}} {:b {:a nil}}))))

  (t/testing "nil presence round-trips"
    (t/is (= {:a nil} (round-trip {:a nil} {:a nil})))
    (t/is (= {} (round-trip {:a nil} {})))
    (t/is (= {:a nil} (round-trip {} {:a nil})))
    (t/is (nil? (round-trip {:a nil} nil)))))

(t/deftest diff-map-unchanged-keys-test
  (t/testing "a large map with one change emits one operation"
    (let [old (into {} (map (fn [idx] [(keyword (str "k" idx)) idx])) (range 1000))]
      (t/is (= [[:set [:k500] :changed]]
               (od/diff old (assoc old :k500 :changed))))))

  (t/testing "a nil value under an unchanged key is not re-emitted"
    (t/is (= [[:set [:b] 2]]
             (od/diff {:a nil :b 1} {:a nil :b 2}))))

  (t/testing "new keys holding nil join the group as :add"
    (t/is (= [{:path [] :set [[:b 2]] :add [[:a nil]]}]
             (od/diff {:b 1} {:a nil :b 2}))))

  (t/testing "a removed nil key joins the group as :del"
    (t/is (= [{:path [] :set [[:b 2]] :del [:a]}]
             (od/diff {:a nil :b 1} {:b 2})))))

(t/deftest diff-vector-elements-test
  (t/testing "one changed element yields one operation"
    (t/is (= [[:set [:a 1] 5]]
             (od/diff {:a [1 2 3]} {:a [1 5 3]}))))

  (t/testing "a vector of maps is diffed by index and by key"
    (t/is (= [[:set [:a 0 :x] 2]]
             (od/diff {:a [{:x 1}]} {:a [{:x 2}]}))))

  (t/testing "a vector inside a vector"
    (t/is (= [[:set [0 1] 5]]
             (od/diff [[1 2] [3]] [[1 5] [3]]))))

  (t/testing "append at the end"
    (t/is (= [[:add [:a 1] 2]]
             (od/diff {:a [1]} {:a [1 2]}))))

  (t/testing "prepend at the start"
    (t/is (= [[:add [0] 0]]
             (od/diff [1 2] [0 1 2]))))

  (t/testing "insert in the middle is a single :add"
    (t/is (= [[:add [1] 9]]
             (od/diff [1 2 3] [1 9 2 3]))))

  (t/testing "remove in the middle is a single :del"
    (t/is (= [[:del [1]]]
             (od/diff [1 2 3] [1 3]))))

  (t/testing "truncate removes from the highest index down"
    (t/is (= [{:path [] :del [3 2]}]
             (od/diff [1 2 3 4] [1 2]))))

  (t/testing "selecting every other element"
    (t/is (= [[:set [1] 3]
              {:path [] :del [3 2]}]
             (od/diff [1 2 3 4] [1 3]))))

  (t/testing "empty to non-empty and back"
    (t/is (= [{:path [] :add [[0 1] [1 2]]}]
             (od/diff [] [1 2])))
    (t/is (= [{:path [] :del [1 0]}]
             (od/diff [1 2] []))))

  (t/testing "a single change in a long vector is a single operation"
    (let [old (vec (range 1000))]
      (t/is (= [[:set [500] :changed]]
               (od/diff old (assoc old 500 :changed)))))))

(t/deftest diff-path-navigation-test
  (t/testing "integer keys are keys in a map and indices in a vector"
    (t/is (= [[:set [0] :b]]
             (od/diff {0 :a} {0 :b})))
    (t/is (= [[:set [0] :b]]
             (od/diff [:a] [:b]))))

  (t/testing "an integer key of a map leads into a vector"
    (t/is (= [[:set [0 1] :b]]
             (od/diff {0 [1 :a]} {0 [1 :b]}))))

  (t/testing "vector keys of a map"
    (t/is (= [[:set [[:k]] 2]]
             (od/diff {[:k] 1} {[:k] 2}))))

  (t/testing "nil keys"
    (t/is (= [[:set [nil] 2]]
             (od/diff {nil 1} {nil 2}))))

  (t/testing "deep mixed paths"
    (t/is (= [[:set [:a 0 :b 1 :c] 3]]
             (od/diff {:a [{:b [1 {:c 2}]}]}
                      {:a [{:b [1 {:c 3}]}]})))))

(t/deftest diff-whole-value-test
  (t/testing "a change of container type is a whole replacement"
    (t/is (= [[:set [:a] {:x 1}]]
             (od/diff {:a [1]} {:a {:x 1}})))
    (t/is (= [[:set [:a] #{1}]]
             (od/diff {:a [1]} {:a #{1}}))))

  (t/testing "lists are not diffed element by element"
    (t/is (= [[:set [:a] '(1 3)]]
             (od/diff {:a '(1 2)} {:a '(1 3)}))))

  (t/testing "sets are not diffed element by element"
    (t/is (= [[:set [:a] #{1 3}]]
             (od/diff {:a #{1 2}} {:a #{1 3}}))))

  (t/testing "records of different types are replaced whole"
    (t/is (= [[:set [] (->OtherRecord 1 2)]]
             (od/diff (->SampleRecord 1 2)
                      (->OtherRecord 1 2))))
    (t/is (= [[:set [] {:a 1 :b 2}]]
             (od/diff (->SampleRecord 1 2)
                      {:a 1 :b 2}))))

  (t/testing "records of the same type are diffed by field"
    (t/is (= [[:set [:b] 3]]
             (od/diff (->SampleRecord 1 2)
                      (->SampleRecord 1 3)))))

  (t/testing "root scalars are replaced with an empty path"
    (t/is (= [[:set [] 2]]
             (od/diff 1 2)))
    (t/is (= 2 (round-trip 1 2)))
    (t/is (= {:a 1} (round-trip nil {:a 1})))
    (t/is (nil? (round-trip {:a 1} nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; diff: operations versus groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest diff-hybrid-shape-test
  (t/testing "a container with one change is an operation"
    (t/is (= [[:set [:x] 2]] (od/diff {:x 1} {:x 2})))
    (t/is (= [[:del [:x]]] (od/diff {:x 1} {})))
    (t/is (= [[:add [:x] 1]] (od/diff {} {:x 1}))))

  (t/testing "a container with two changes is a group"
    (t/is (= [{:path [] :set [[:x 2] [:y 3]]}]
             (od/diff {:x 1 :y 1} {:x 2 :y 3})))
    (t/is (= [{:path [:b] :set [[:x 2] [:y 3]]}]
             (od/diff {:b {:x 1 :y 1}} {:b {:x 2 :y 3}}))))

  (t/testing "operations and groups mix, keeping the apply order"
    (t/is (= [[:set [:b :c] 2] [:set [:a] 2]]
             (od/diff {:a 1 :b {:c 1}} {:a 2 :b {:c 2}})))
    (t/is (= {:a 2 :b {:c 2}}
             (round-trip {:a 1 :b {:c 1}} {:a 2 :b {:c 2}}))))

  (t/testing "every element is well formed"
    (t/is (every? gen/valid-element? (od/diff {:a 1 :b 2} {:a 2 :c 3})))
    (t/is (every? gen/valid-element? (od/diff {:a 1} {:a 2})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; apply-diff: application and shape checks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest apply-diff-literal-test
  (t/testing "changed, added and removed keys"
    (t/is (= {:a 2 :c 3}
             (od/apply-diff {:a 1 :b 2}
                            [[:set [:a] 2] [:del [:b]] [:add [:c] 3]]))))

  (t/testing "nil presence is preserved"
    (t/is (= {:a nil}
             (od/apply-diff {} [[:add [:a] nil]])))
    (t/is (= {}
             (od/apply-diff {:a nil} [[:del [:a]]])))
    (t/is (contains? (od/apply-diff {:a 1} [[:set [:a] nil]])
                     :a)))

  (t/testing "vector additions and removals"
    (t/is (= [1 9 2 3]
             (od/apply-diff [1 2 3] [[:add [1] 9]])))
    (t/is (= [1 3]
             (od/apply-diff [1 2 3] [[:del [1]]])))
    (t/is (= [1 2 3 4]
             (od/apply-diff [1 2]
                            [[:add [2] 3] [:add [3] 4]])))
    (t/is (= [1 2]
             (od/apply-diff [1 2 3 4]
                            [[:del [3]] [:del [2]]]))))

  (t/testing "operations are applied in order"
    (t/is (= {:a {:x 2}}
             (od/apply-diff {}
                            [[:add [:a] {:x 1}]
                             [:set [:a :x] 2]])))
    (t/is (= [1 2 4]
             (od/apply-diff [1 3]
                            [[:add [1] 2]
                             [:set [2] 4]]))))

  (t/testing "an empty diff is a no-op"
    (t/is (= {:a 1} (od/apply-diff {:a 1} [])))
    (t/is (nil? (od/apply-diff nil []))))

  (t/testing "root replacement"
    (t/is (= 2 (od/apply-diff 1 [[:set [] 2]])))
    (t/is (= {:a 1} (od/apply-diff nil [[:set [] {:a 1}]]))))

  (t/testing "one-argument arity returns a function"
    (let [f (od/apply-diff [[:set [:a] 2]])]
      (t/is (= {:a 2 :b 2} (f {:a 1 :b 2})))))

  (t/testing "records keep their type when a field is set"
    (let [result (od/apply-diff (->SampleRecord 1 2)
                                [[:set [:b] 3]])]
      (t/is (record? result))
      (t/is (= (->SampleRecord 1 3) result)))))

(t/deftest apply-diff-group-test
  (t/testing "a group applies several operations under one path"
    (t/is (= {:a 2 :c 3}
             (od/apply-diff {:a 1 :b 2}
                            [{:path [] :set [[:a 2]] :add [[:c 3]] :del [:b]}])))
    (t/is (= {:a {:x 2 :y 3}}
             (od/apply-diff {:a {:x 1 :y 1}}
                            [{:path [:a] :set [[:x 2] [:y 3]]}]))))

  (t/testing "a vector group removes from the highest index down"
    (t/is (= [1 2]
             (od/apply-diff [1 2 3 4] [{:path [] :del [3 2]}]))))

  (t/testing "a vector group adds from the lowest index up"
    (t/is (= [1 9 2 3]
             (od/apply-diff [1 2 3] [{:path [] :add [[1 9]]}]))))

  (t/testing "operations and groups mix in order"
    (t/is (= [1 3]
             (od/apply-diff [1 2 3 4]
                            [[:set [1] 3] {:path [] :del [3 2]}])))))

(t/deftest apply-diff-group-errors-test
  (t/testing "a set inside a group checks the shape"
    (try
      (od/apply-diff {} [{:path [] :set [[:a 1]]}])
      (t/is false "expected an exception")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (let [data (ex-data e)]
          (t/is (= :diff-mismatch (:type data)))
          (t/is (= [:a] (:path data)))
          (t/is (= :absent (:reason data)))))))

  (t/testing "a group under a scalar reports absent"
    (try
      (od/apply-diff {:a 5} [{:path [:a] :set [[:b 1]]}])
      (t/is false "expected an exception")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (t/is (= :absent (:reason (ex-data e)))))))

  (t/testing "a group needs a vector path"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [{:path :a :set [[:b 1]]}]))))

  (t/testing "a group needs an operation"
    (try
      (od/apply-diff {} [{:path []}])
      (t/is false "expected an exception")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (t/is (= :invalid-diff (:type (ex-data e))))))))

(t/deftest apply-diff-rejects-a-malformed-group-test
  (let [invalid? (fn invalid?
                   ([diff] (invalid? {:a 1 :b 2} diff))
                   ([value diff]
                    (try
                      (od/apply-diff value diff)
                      false
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
                        (= :invalid-diff (:type (ex-data e)))))))]

    (t/testing "a member that is not a [key value] pair is refused"
      ;; A shorter vector would apply a nil the diff never carried, and a longer
      ;; one would drop the rest in silence.
      (doseq [[label diff]
              [["a one-element set member" [{:path [] :set [[:a]]}]]
               ["a three-element set member" [{:path [] :set [[:a 1 :extra]]}]]
               ["a one-element add member" [{:path [] :add [[:b]]}]]
               ["a scalar set member" [{:path [] :set [:a]}]]]]
        (t/is (invalid? diff) label)))

    (t/testing "a malformed member wins over a target that is not a container"
      ;; The element is malformed whatever the value holds, and the group is
      ;; validated before its path is resolved.
      (t/is (invalid? {:a 5} [{:path [:a] :set [[:b]]}])
            "a one-element member aimed at a scalar"))

    (t/testing "a collection that is not sequential is refused"
      (doseq [[label diff]
              [["a map as set" [{:path [] :set {:a 1}}]]
               ["a map as add" [{:path [] :add {:a 1}}]]
               ["a map as del" [{:path [] :del {:a 1}}]]
               ["a number as set" [{:path [] :set 42}]]
               ["a string as add" [{:path [] :add "ab"}]]]]
        (t/is (invalid? diff) label)))

    (t/testing "a collection that is present and nil is refused"
      ;; Absence and a nil value are different everywhere else in the format, so
      ;; the group does not read a present nil as an absent collection either.
      (t/is (invalid? [{:path [] :set nil :add [[:b 1]]}]))
      (t/is (invalid? [{:path [] :del nil}])))

    (t/testing "an explicit nil value is still a member"
      (t/is (= {:a nil} (od/apply-diff {} [{:path [] :add [[:a nil]]}]))
            "absence and a nil value are different, so the pair is valid"))))

(t/deftest apply-diff-does-not-check-previous-values-test
  (t/testing "a set replaces whatever the path holds"
    (t/is (= {:a 2} (od/apply-diff {:a 99} [[:set [:a] 2]])))
    (t/is (= {:a 2 :b 3}
             (od/apply-diff {:a 1 :b 2} [[:set [:a] 2] [:set [:b] 3]]))))

  (t/testing "a set on a long vector replaces the target index"
    (let [value (vec (range 1000))]
      (t/is (= (assoc value 500 :changed)
               (od/apply-diff value [[:set [500] :changed]]))))))

(t/deftest apply-diff-shape-errors-test
  (t/testing "set on a missing path throws and reports absent"
    (try
      (od/apply-diff {} [[:set [:a] 1]])
      (t/is false "expected an exception")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (let [data (ex-data e)]
          (t/is (= :diff-mismatch (:type data)))
          (t/is (= :set (:op data)))
          (t/is (= [:a] (:path data)))
          (t/is (= :absent (:reason data)))))))

  (t/testing "add on an existing key throws, even when its value is nil"
    (try
      (od/apply-diff {:a nil} [[:add [:a] 1]])
      (t/is false "expected an exception")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (let [data (ex-data e)]
          (t/is (= :diff-mismatch (:type data)))
          (t/is (= :present (:reason data)))))))

  (t/testing "del on a missing key throws"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:del [:a]]]))))

  (t/testing "vector indices out of range throw"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff [1] [[:set [1] 2]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff [1] [[:del [1]]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff [1] [[:add [2] 2]])))
    (t/is (= [1 2] (od/apply-diff [1] [[:add [1] 2]]))
          "adding at the vector count appends"))

  (t/testing "a negative or non-integer index throws"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff [1] [[:set [-1] 2]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff [1] [[:set [:a] 2]]))))

  (t/testing "an operation whose parent does not exist throws"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:add [:a :b] 1]]))))

  (t/testing "an operation through a scalar throws"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {:a 5} [[:add [:a :b] 1]]))))

  (t/testing "add and del cannot target the root"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:add [] 1]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:del []]])))))

(t/deftest apply-diff-invalid-test
  (t/testing "the diff must be a vector"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {:a 1} {:a [1 2]})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {:a 1} nil))))

  (t/testing "an element must be an operation or a group"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [42])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [{:op :set :path [:a] :new 1}]))))

  (t/testing "an operation needs a vector path"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:set nil 1]]))))

  (t/testing "every operation type has its exact arity"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:set [:a]]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {:a 1} [[:set [:a] 2 3]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:add [:a]]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {:a 1} [[:del [:a] 1]]))))

  (t/testing "unknown operations"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:nope [:a] 1]])))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (od/apply-diff {} [[:del]]))))

  (t/testing "malformed operations throw :invalid-diff"
    (try
      (od/apply-diff {} [[:nope [:a] 1]])
      (t/is false "expected an exception")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
        (t/is (= :invalid-diff (:type (ex-data e))))))))

(t/deftest apply-diff-record-removal-test
  ;; Removing a declared record field returns a plain map; this is how
  ;; dissoc behaves on records and the reason different record types
  ;; are replaced as a whole by diff.
  (let [result (od/apply-diff (->SampleRecord 1 2)
                              [[:del [:a]]])]
    (t/is (not (record? result)))
    (t/is (= {:b 2} result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generative
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- known-diff-error?
  [e]
  (contains? #{:diff-mismatch :invalid-diff}
             (:type (ex-data e))))

(t/deftest generative-round-trip-test
  (smt/check!
   (smt/for [v1 gen/gen-value
             v2 gen/gen-value]
     (and (= v2 (od/apply-diff v1 (od/diff v1 v2)))
          (= v1 (od/apply-diff v2 (od/diff v2 v1)))))
   {:num 100 :seed 42}))

(t/deftest generative-identical-test
  (smt/check!
   (smt/for [v gen/gen-value]
     (= [] (od/diff v v)))
   {:num 100 :seed 42}))

(t/deftest generative-element-shape-test
  (smt/check!
   (smt/for [v1 gen/gen-value
             v2 gen/gen-value]
     (every? gen/valid-element? (od/diff v1 v2)))
   {:num 100 :seed 42}))

(t/deftest generative-idempotent-test
  (smt/check!
   (smt/for [v1 gen/gen-value
             v2 gen/gen-value]
     (let [result (od/apply-diff v1 (od/diff v1 v2))]
       (= [] (od/diff result v2))))
   {:num 100 :seed 42}))

(t/deftest generative-apply-never-crashes-test
  ;; Applying a diff to the wrong value may throw a shape error, but it
  ;; must never leak an exception from the underlying collections.
  (smt/check!
   (smt/for [base gen/gen-value
             target gen/gen-value]
     (let [diff (od/diff base target)]
       (try
         (od/apply-diff target diff)
         true
         (catch #?(:clj Throwable :cljs :default) e
           (known-diff-error? e)))))
   {:num 100 :seed 42}))
