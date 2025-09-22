;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.path-names-test
  (:require
   [app.common.path-names :as cpn]
   [clojure.test :as t]))

(t/deftest split-group-name
  (t/is (= ["foo" "bar"] (cpn/split-group-name "foo/bar")))
  (t/is (= ["" "foo"] (cpn/split-group-name "foo")))
  (t/is (= ["" "foo"] (cpn/split-group-name "/foo")))
  (t/is (= ["" ""] (cpn/split-group-name "")))
  (t/is (= ["" ""] (cpn/split-group-name nil))))

(t/deftest split-and-join-path
  (let [name "group/subgroup/name"
        path (cpn/split-path name :separator "/")
        name' (cpn/join-path path :separator "/" :with-spaces? false)]
    (t/is (= (first path) "group"))
    (t/is (= (second path) "subgroup"))
    (t/is (= (nth path 2) "name"))
    (t/is (= name' name))))

(t/deftest split-and-join-path-with-spaces
  (let [name "group / subgroup / name"
        path (cpn/split-path name :separator "/")]
    (t/is (= (first path) "group"))
    (t/is (= (second path) "subgroup"))
    (t/is (= (nth path 2) "name"))))
