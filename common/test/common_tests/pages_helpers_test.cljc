;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.pages-helpers-test
  (:require
   [clojure.test :as t]
   [clojure.pprint :refer [pprint]]
   [app.common.pages.helpers :as cph]))

(t/deftest parse-path-name
  (t/is (= ["foo" "bar"] (cph/parse-path-name "foo/bar")))
  (t/is (= ["" "foo"] (cph/parse-path-name "foo")))
  (t/is (= ["" "foo"] (cph/parse-path-name "/foo")))
  (t/is (= ["" ""] (cph/parse-path-name "")))
  (t/is (= ["" ""] (cph/parse-path-name nil))))

