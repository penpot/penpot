;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.math-test
  (:require
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest finite?-number-test
  (t/testing "finite? returns true for positive integer"
    (t/is (true? (mth/finite? 16))))

  (t/testing "finite? returns true for zero"
    (t/is (true? (mth/finite? 0))))

  (t/testing "finite? returns true for negative float"
    (t/is (true? (mth/finite? -42.5))))

  (t/testing "finite? returns true for very large number"
    (t/is (true? (mth/finite? 1e308)))))

(t/deftest finite?-string-test
  (t/testing "finite? returns false for numeric string"
    (t/is (false? (mth/finite? "16"))))

  (t/testing "finite? returns false for non-numeric string"
    (t/is (false? (mth/finite? "abc"))))

  (t/testing "finite? returns false for empty string"
    (t/is (false? (mth/finite? ""))))

  (t/testing "finite? returns false for string with spaces"
    (t/is (false? (mth/finite? "  ")))))

(t/deftest finite?-nil-test
  (t/testing "finite? returns false for nil"
    (t/is (false? (mth/finite? nil)))))

(t/deftest finite?-other-types-test
  (t/testing "finite? returns false for keyword"
    (t/is (false? (mth/finite? :foo))))

  (t/testing "finite? returns false for vector"
    (t/is (false? (mth/finite? [1 2 3]))))

  (t/testing "finite? returns false for map"
    (t/is (false? (mth/finite? {:a 1})))))

#_:clj-kondo/ignore
(t/deftest finite?-nan-test
  #?(:cljs
     (t/testing "finite? returns false for js/NaN (CLJS)"
       (t/is (false? (mth/finite? js/NaN))))
     :clj
     (t/testing "finite? returns false for Double/NaN (CLJ)"
       (t/is (false? (mth/finite? Double/NaN))))))
