;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.tokens-test
  (:require
   [app.common.files.tokens :as cft]
   [clojure.test :as t]))

(t/deftest test-parse-token-value
  (t/testing "parses double from a token value"
    (t/is (= {:value 100.1 :unit nil} (cft/parse-token-value "100.1")))
    (t/is (= {:value -9.0 :unit nil} (cft/parse-token-value "-9"))))
  (t/testing "trims white-space"
    (t/is (= {:value -1.3 :unit nil} (cft/parse-token-value "     -1.3   "))))
  (t/testing "parses unit: px"
    (t/is (= {:value 70.3 :unit "px"} (cft/parse-token-value "     70.3px   "))))
  (t/testing "parses unit: %"
    (t/is (= {:value -10.0 :unit "%"} (cft/parse-token-value "-10%"))))
  (t/testing "parses unit: px")
  (t/testing "returns nil for any invalid characters"
    (t/is (nil? (cft/parse-token-value "     -1.3a   "))))
  (t/testing "doesnt accept invalid double"
    (t/is (nil? (cft/parse-token-value ".3")))))

(t/deftest token-applied-test
  (t/testing "matches passed token with `:token-attributes`"
    (t/is (true? (cft/token-applied? {:name "a"} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "doesn't match empty token"
    (t/is (nil? (cft/token-applied? {} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "does't match passed token `:id`"
    (t/is (nil? (cft/token-applied? {:name "b"} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "doesn't match passed `:token-attributes`"
    (t/is (nil? (cft/token-applied? {:name "a"} {:applied-tokens {:x "a"}} #{:y})))))

(t/deftest shapes-ids-by-applied-attributes
  (t/testing "Returns set of matched attributes that fit the applied token"
    (let [attributes #{:x :y :z}
          shape-applied-x {:id "shape-applied-x"
                           :applied-tokens {:x "1"}}
          shape-applied-y {:id "shape-applied-y"
                           :applied-tokens {:y "1"}}
          shape-applied-x-y {:id "shape-applied-x-y"
                             :applied-tokens {:x "1" :y "1"}}
          shape-applied-none {:id "shape-applied-none"
                              :applied-tokens {}}
          shape-applied-all {:id "shape-applied-all"
                             :applied-tokens {:x "1" :y "1" :z "1"}}
          shape-ids (fn [& xs] (into #{} (map :id xs)))
          shapes [shape-applied-x
                  shape-applied-y
                  shape-applied-x-y
                  shape-applied-all
                  shape-applied-none]
          expected (cft/shapes-ids-by-applied-attributes {:name "1"} shapes attributes)]
      (t/is (= (:x expected) (shape-ids shape-applied-x
                                        shape-applied-x-y
                                        shape-applied-all)))
      (t/is (= (:y expected) (shape-ids shape-applied-y
                                        shape-applied-x-y
                                        shape-applied-all)))
      (t/is (= (:z expected) (shape-ids shape-applied-all)))
      (t/is (true? (cft/shapes-applied-all? expected (shape-ids shape-applied-all) attributes)))
      (t/is (false? (cft/shapes-applied-all? expected (apply shape-ids shapes) attributes)))
      (shape-ids shape-applied-x
                 shape-applied-x-y
                 shape-applied-all))))

(t/deftest tokens-applied-test
  (t/testing "is true when single shape matches the token and attributes"
    (t/is (true? (cft/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "a"}}
                                                         {:applied-tokens {:x "b"}}]
                                            #{:x}))))
  (t/testing "is false when no shape matches the token or attributes"
    (t/is (nil? (cft/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "b"}}
                                                        {:applied-tokens {:x "b"}}]
                                           #{:x})))
    (t/is (nil? (cft/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "a"}}
                                                        {:applied-tokens {:x "a"}}]
                                           #{:y})))))

(t/deftest name->path-test
  (t/is (= ["foo" "bar" "baz"] (cft/token-name->path "foo.bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (cft/token-name->path "foo..bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (cft/token-name->path "foo..bar.baz...."))))

(t/deftest token-name-path-exists?-test
  (t/is (true? (cft/token-name-path-exists? "border-radius" {"border-radius" {"sm" {:name "sm"}}})))
  (t/is (true? (cft/token-name-path-exists? "border-radius" {"border-radius" {:name "sm"}})))
  (t/is (true? (cft/token-name-path-exists? "border-radius.sm" {"border-radius" {:name "sm"}})))
  (t/is (true? (cft/token-name-path-exists? "border-radius.sm.x" {"border-radius" {:name "sm"}})))
  (t/is (false? (cft/token-name-path-exists? "other" {"border-radius" {:name "sm"}})))
  (t/is (false? (cft/token-name-path-exists? "dark.border-radius.md" {"dark" {"border-radius" {"sm" {:name "sm"}}}}))))
