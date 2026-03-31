;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.helpers-test
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
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

(t/deftest test-get-prev-sibling
  (let [parent-id (uuid/custom 1 1)
        child-a   (uuid/custom 1 2)
        child-b   (uuid/custom 1 3)
        orphan-id (uuid/custom 1 4)
        objects   {parent-id {:id parent-id :shapes [child-a child-b]}
                   child-a   {:id child-a :parent-id parent-id}
                   child-b   {:id child-b :parent-id parent-id}
                   orphan-id {:id orphan-id :parent-id parent-id}}]
    (t/testing "Returns previous sibling when present in parent ordering"
      (t/is (= child-a
               (cfh/get-prev-sibling objects child-b))))

    (t/testing "Returns nil when the shape is missing from parent ordering"
      (t/is (nil? (cfh/get-prev-sibling objects orphan-id))))))
