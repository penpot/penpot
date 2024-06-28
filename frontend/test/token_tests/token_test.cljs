;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns token-tests.token-test
  (:require
   [app.main.ui.workspace.tokens.token :as wtt]
   [cljs.test :as t :include-macros true]))

(t/deftest name->path-test
  (t/is (= ["foo" "bar" "baz"] (wtt/token-name->path "foo.bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (wtt/token-name->path "foo..bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (wtt/token-name->path "foo..bar.baz...."))))

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
  (t/is (true? (wtt/token-name-path-exists? "border-radius" {"border-radius" {:name "sm"}})))
  (t/is (true? (wtt/token-name-path-exists? "border-radius.sm" {"border-radius" {:name "sm"}})))
  (t/is (true? (wtt/token-name-path-exists? "border-radius.sm.x" {"border-radius" {:name "sm"}})))
  (t/is (false? (wtt/token-name-path-exists? "other" {"border-radius" {:name "sm"}})))
  (t/is (false? (wtt/token-name-path-exists? "dark.border-radius.md" {"dark" {"border-radius" {"sm" {:name "sm"}}}}))))
