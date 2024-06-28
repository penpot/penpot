;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns token-tests.token-core-test
  (:require
   [app.main.ui.workspace.tokens.core :as wtc]
   [cljs.test :as t :include-macros true]))

(t/deftest name->path-test
  (t/is (= ["foo" "bar" "baz"] (wtc/name->path "foo.bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (wtc/name->path "foo..bar.baz")))
  (t/is (= ["foo" "bar" "baz"] (wtc/name->path "foo..bar.baz...."))))

(t/deftest tokens-name-tree
  (t/is (= {"foo"
            {"bar"
             {"baz" {:name "foo.bar.baz", :value "a"},
              "bam" {:name "foo.bar.bam", :value "b"}}},
            "baz" {"bar" {"foo" {:name "baz.bar.foo", :value "{foo.bar.baz}"}}}}
           (wtc/tokens-name-tree {:a {:name "foo.bar.baz"
                                      :value "a"}
                                  :b {:name "foo.bar.bam"
                                      :value "b"}
                                  :c {:name "baz.bar.foo"
                                      :value "{foo.bar.baz}"}}))))
