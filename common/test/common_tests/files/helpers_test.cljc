;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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

(t/deftest test-get-base-shape-with-missing-data
  (let [root-id  uuid/zero
        shape-a  {:id (uuid/custom 1 1) :parent-id root-id :frame-id root-id}
        shape-b  {:id (uuid/custom 1 2) :parent-id root-id :frame-id root-id}
        selected #{(:id shape-a) (:id shape-b)}]
    (t/testing "Returns nil instead of throwing with nil objects"
      (t/is (nil? (cfh/get-base-shape nil selected))))
    (t/testing "Returns nil instead of throwing with empty objects"
      (t/is (nil? (cfh/get-base-shape {} selected))))
    (t/testing "Returns nil instead of throwing when the root has no shapes"
      (t/is (nil? (cfh/get-base-shape {root-id {:id root-id}} selected))))
    (t/testing "Returns nil when the selection is not present in the objects"
      (let [objects {root-id {:id root-id :shapes [(:id shape-a)]}
                     (:id shape-a) shape-a}]
        (t/is (nil? (cfh/get-base-shape objects #{(:id shape-b)})))))
    (t/testing "Selecting the root itself never yields a base shape (callers handle it)"
      (let [objects {root-id {:id root-id :shapes [(:id shape-a)]}
                     (:id shape-a) shape-a}]
        (t/is (nil? (cfh/get-base-shape objects #{root-id})))))))

(t/deftest test-order-by-indexed-shapes
  (let [root-id  uuid/zero
        shape-a  {:id (uuid/custom 1 1) :parent-id root-id :frame-id root-id}
        shape-b  {:id (uuid/custom 1 2) :parent-id root-id :frame-id root-id}
        shape-c  {:id (uuid/custom 1 3) :parent-id root-id :frame-id root-id}
        objects  {root-id {:id root-id :shapes [(:id shape-a) (:id shape-b) (:id shape-c)]}
                  (:id shape-a) shape-a
                  (:id shape-b) shape-b
                  (:id shape-c) shape-c}]
    (t/testing "Orders selection top-most first on healthy inputs"
      (t/is (= [(:id shape-c) (:id shape-a)]
               (cfh/order-by-indexed-shapes objects #{(:id shape-a) (:id shape-c)})))
      (t/is (= shape-c
               (cfh/get-base-shape objects #{(:id shape-a) (:id shape-c)}))))
    (t/testing "Returns empty instead of throwing with nil objects"
      (t/is (= [] (cfh/order-by-indexed-shapes nil #{(:id shape-a)})))
      (t/is (nil? (cfh/get-base-shape nil #{(:id shape-a)}))))))

(t/deftest test-get-position-on-parent-with-missing-data
  (t/testing "Returns nil instead of throwing with missing data"
    (t/is (nil? (cfh/get-position-on-parent nil nil)))
    (t/is (nil? (cfh/get-position-on-parent {} (uuid/custom 1 9))))))
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
