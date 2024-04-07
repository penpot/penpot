;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.pages-helpers-test
  (:require
   [app.common.files.helpers :as cfh]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as t]))

(t/deftest parse-path-name
  (t/is (= ["foo" "bar"] (cfh/parse-path-name "foo/bar")))
  (t/is (= ["" "foo"] (cfh/parse-path-name "foo")))
  (t/is (= ["" "foo"] (cfh/parse-path-name "/foo")))
  (t/is (= ["" ""] (cfh/parse-path-name "")))
  (t/is (= ["" ""] (cfh/parse-path-name nil))))

