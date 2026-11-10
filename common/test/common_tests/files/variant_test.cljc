;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.variant-test
  (:require
   [app.common.files.variant :as cfv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-collect-variant-properties-for-main
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page      (thf/current-page file)
        main      (ths/get-shape file :m01)
        comp      (thc/get-component file :c01)
        collected (cfv/collect-variant-properties (:data file) (:objects page) main)]

    (t/is (= {(:id comp) (:variant-properties comp)} collected))))

(t/deftest test-collect-variant-properties-for-container
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page      (thf/current-page file)
        container (ths/get-shape file :v01)
        c01       (thc/get-component file :c01)
        c02       (thc/get-component file :c02)
        collected (cfv/collect-variant-properties (:data file) (:objects page) container)]

    (t/is (= {(:id c01) (:variant-properties c01)
              (:id c02) (:variant-properties c02)}
             collected))))

(t/deftest test-collect-variant-properties-for-plain-shape
  (let [file      (-> (thf/sample-file :file1)
                      (ths/add-sample-shape :rect1 :type :rect))
        page      (thf/current-page file)
        shape     (ths/get-shape file :rect1)
        collected (cfv/collect-variant-properties (:data file) (:objects page) shape)]

    (t/is (= {} collected))))
