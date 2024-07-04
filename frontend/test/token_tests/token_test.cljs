;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns token-tests.token-test
  (:require
   [app.main.ui.workspace.tokens.token :as wtt]
   [cljs.test :as t :include-macros true]))

(t/deftest remove-attributes-for-token-id
  (t/testing "removes attributes matching the `token-id`, keeps other attributes"
    (t/is (= {:ry :b}
             (wtt/remove-attributes-for-token-id
              #{:rx :ry} :a {:rx :a :ry :b})))))

(t/deftest token-applied-test
  (t/testing "matches passed token with `:token-attributes`"
    (t/is (true? (wtt/token-applied? {:id :a} {:applied-tokens {:x :a}} #{:x}))))
  (t/testing "doesn't match empty token"
    (t/is (nil? (wtt/token-applied? {} {:applied-tokens {:x :a}} #{:x}))))
  (t/testing "does't match passed token `:id`"
    (t/is (nil? (wtt/token-applied? {:id :b} {:applied-tokens {:x :a}} #{:x}))))
  (t/testing "doesn't match passed `:token-attributes`"
    (t/is (nil? (wtt/token-applied? {:id :a} {:applied-tokens {:x :a}} #{:y})))))

(t/deftest tokens-applied-test
  (t/testing "is true when single shape matches the token and attributes"
    (t/is (true? (wtt/shapes-token-applied? {:id :a} [{:applied-tokens {:x :a}}
                                                      {:applied-tokens {:x :b}}]
                                      #{:x}))))
  (t/testing "is false when no shape matches the token or attributes"
    (t/is (nil? (wtt/shapes-token-applied? {:id :a} [{:applied-tokens {:x :b}}
                                                     {:applied-tokens {:x :b}}]
                                     #{:x})))
    (t/is (nil? (wtt/shapes-token-applied? {:id :a} [{:applied-tokens {:x :a}}
                                                     {:applied-tokens {:x :a}}]
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
