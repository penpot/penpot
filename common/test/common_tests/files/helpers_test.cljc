;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.helpers-test
  (:require
   [app.common.files.helpers :as cfh]
   [clojure.test :as t]))

(t/deftest test-generate-unique-name
  (t/testing "Test unique name generation"
    (let [suffix-fn #(str "-copy-" %)]
      (t/is (cfh/generate-unique-name "base-name"
                                      #{"base-name" "base-name-copy-1"}
                                      :suffix-fn suffix-fn)
            "base-name-copy-2")
      (t/is (cfh/generate-unique-name "base-name"
                                      #{"base-name-copy-2"}
                                      :suffix-fn suffix-fn)
            "base-name-copy-1")
      (t/is (cfh/generate-unique-name "base-name"
                                      #{"base-namec-copy"}
                                      :suffix-fn suffix-fn)
            "base-name-copy-1")
      (t/is (cfh/generate-unique-name "base-name"
                                      #{"base-name"}
                                      :suffix-fn suffix-fn)
            "base-name-copy-1")))

  (t/testing "Test unique name generation with immidate suffix and default suffix-fn"
    (t/is (cfh/generate-unique-name "base-name" #{} :immediate-suffix? true)
          "base-name 1")
    (t/is (cfh/generate-unique-name "base-name"
                                    #{"base-name 1" "base-name 2"}
                                    :immediate-suffix? true)
          "base-name 3")))


(deftest test-generate-component-name
  (testing "Generates unique component names"
    (is (= "Component-01" (generate-component-name "Component")))
    (is (= "Component-02" (generate-component-name "Component")))))

(deftest test-is-name-unique?
  (testing "Checks if a component name is unique"
    (is (true? (is-name-unique? "Component-01")))
    (is (false? (is-name-unique? "Component-02")))))
