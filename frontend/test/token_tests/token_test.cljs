;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns token-tests.token-test
  (:require
   [app.main.ui.workspace.tokens.token :as wtt]
   [cljs.test :as t :include-macros true]))

(t/deftest test-parse-token-value
  (t/testing "parses double from a token value"
    (t/is (= 100.1 (wtt/parse-token-value "100.1")))
    (t/is (= -9 (wtt/parse-token-value "-9")))
    (t/testing "trims white-space"
      (t/is (= -1.3 (wtt/parse-token-value "     -1.3   "))))
    (t/testing "returns nil for any invalid characters"
      (t/is (nil? (wtt/parse-token-value "     -1.3a   "))))
    (t/testing "doesnt accept invalid double"
      (t/is (nil? (wtt/parse-token-value ".3"))))))

(t/deftest find-token-references
  (t/testing "finds references inside curly braces in a string"
    (t/is (= #{"foo" "bar"} (wtt/find-token-references "{foo} + {bar}")))
    (t/testing "ignores extra text"
      (t/is (= #{"foo.bar.baz"} (wtt/find-token-references "{foo.bar.baz} + something"))))
    (t/testing "ignores string without references"
      (t/is (nil? (wtt/find-token-references "1 + 2"))))
    (t/testing "handles edge-case for extra curly braces"
      (t/is (= #{"foo" "bar"} (wtt/find-token-references "{foo}} + {bar}"))))))

(t/deftest remove-attributes-for-token-id
  (t/testing "removes attributes matching the `token`, keeps other attributes"
    (t/is (= {:ry "b"}
             (wtt/remove-attributes-for-token #{:rx :ry} {:name "a"} {:rx "a" :ry "b"})))))

(t/deftest token-applied-test
  (t/testing "matches passed token with `:token-attributes`"
    (t/is (true? (wtt/token-applied? {:name "a"} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "doesn't match empty token"
    (t/is (nil? (wtt/token-applied? {} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "does't match passed token `:id`"
    (t/is (nil? (wtt/token-applied? {:name "b"} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "doesn't match passed `:token-attributes`"
    (t/is (nil? (wtt/token-applied? {:name "a"} {:applied-tokens {:x "a"}} #{:y})))))

(t/deftest token-applied-attributes
  (t/is (= #{:x} (wtt/token-applied-attributes {:name "a"}
                                               {:applied-tokens {:x "a" :y "b"}}
                                               #{:x :missing}))))
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
          expected (wtt/shapes-ids-by-applied-attributes {:name "1"} shapes attributes)]
      (t/is (= (:x expected) (shape-ids shape-applied-x
                                        shape-applied-x-y
                                        shape-applied-all)))
      (t/is (= (:y expected) (shape-ids shape-applied-y
                                 shape-applied-x-y
                                 shape-applied-all)))
      (t/is (= (:z expected) (shape-ids shape-applied-all)))
      (t/is (true? (wtt/shapes-applied-all? expected (shape-ids shape-applied-all) attributes)))
      (t/is (false? (wtt/shapes-applied-all? expected (apply shape-ids shapes) attributes)))
      (shape-ids shape-applied-x
                 shape-applied-x-y
                 shape-applied-all))))

(t/deftest tokens-applied-test
  (t/testing "is true when single shape matches the token and attributes"
    (t/is (true? (wtt/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "a"}}
                                                         {:applied-tokens {:x "b"}}]
                                      #{:x}))))
  (t/testing "is false when no shape matches the token or attributes"
    (t/is (nil? (wtt/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "b"}}
                                                        {:applied-tokens {:x "b"}}]
                                     #{:x})))
    (t/is (nil? (wtt/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "a"}}
                                                        {:applied-tokens {:x "a"}}]
                                     #{:y})))))

(t/deftest name->path-test
  (t/is (= ["foo" "bar" "baz"] (wtt/token-name->path "foo.bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (wtt/token-name->path "foo..bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (wtt/token-name->path "foo..bar.baz...."))))

(t/deftest tokens-name-map-test
  (t/testing "creates a a names map from tokens"
    (t/is (= {"border-radius.sm" {:name "border-radius.sm", :value "10"}
              "border-radius.md" {:name "border-radius.md", :value "20"}}
             (wtt/token-names-map [{:name "border-radius.sm" :value "10"}
                                   {:name "border-radius.md" :value "20"}])))))

(t/deftest tokens-name-tree-test
  (t/is (= {"foo"
            {"bar"
             {"baz" {:name "foo.bar.baz", :value "a"},
              "bam" {:name "foo.bar.bam", :value "b"}}},
            "baz" {"bar" {"foo" {:name "baz.bar.foo", :value "{foo.bar.baz}"}}}}
           (wtt/token-names-tree {:a {:name "foo.bar.baz"
                                      :value "a"}
                                  :b {:name "foo.bar.bam"
                                      :value "b"}
                                  :c {:name "baz.bar.foo"
                                      :value "{foo.bar.baz}"}}))))

(t/deftest token-name-path-exists?-test
  (t/is (true? (wtt/token-name-path-exists? "border-radius" {"border-radius" {"sm" {:name "sm"}}})))
  (t/is (true? (wtt/token-name-path-exists? "border-radius" {"border-radius" {:name "sm"}})))
  (t/is (true? (wtt/token-name-path-exists? "border-radius.sm" {"border-radius" {:name "sm"}})))
  (t/is (true? (wtt/token-name-path-exists? "border-radius.sm.x" {"border-radius" {:name "sm"}})))
  (t/is (false? (wtt/token-name-path-exists? "other" {"border-radius" {:name "sm"}})))
  (t/is (false? (wtt/token-name-path-exists? "dark.border-radius.md" {"dark" {"border-radius" {"sm" {:name "sm"}}}}))))
