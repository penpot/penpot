;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.variant-test
  (:require
   [app.common.files.variant :as fv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.variants :as thv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ============================================================
;; find-variant-components
;; ============================================================

(t/deftest find-variant-components-empty
  (let [file    (thf/sample-file :file1)
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (fv/find-variant-components data (uuid/next))
             []))
    (t/is (= (fv/find-variant-components data objects (uuid/next))
             []))))

(t/deftest find-variant-components-non-variant
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component :c01 :m01 :s01))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (fv/find-variant-components data (thi/id :m01))
             []))
    (t/is (= (fv/find-variant-components data objects (thi/id :m01))
             []))))

(t/deftest find-variant-components-normal
  (let [file  (-> (thf/sample-file :file1)
                  (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data  (:data file)
        page  (thf/current-page file)
        objects (:objects page)
        result (fv/find-variant-components data objects (thi/id :v01))]
    (t/is (= (count result) 2))
    (t/is (every? #(contains? % :id) result))
    (t/is (every? #(contains? % :variant-id) result))))

(t/deftest find-variant-components-single-variant
  (let [file  (-> (thf/sample-file :file1)
                  (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data  (:data file)
        page  (thf/current-page file)
        objects (:objects page)
        result (fv/find-variant-components data objects (thi/id :v01))]
    ;; Verify the order is maintained (reversed from shapes order)
    (t/is (= (:variant-id (first result)) (thi/id :v01)))
    (t/is (= (:variant-id (second result)) (thi/id :v01)))))

;; ============================================================
;; extract-properties-values
;; ============================================================

(t/deftest extract-properties-values-empty
  (let [file    (thf/sample-file :file1)
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (fv/extract-properties-values data objects (uuid/next))
             []))))

(t/deftest extract-properties-values-non-variant
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component :c01 :m01 :s01))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (fv/extract-properties-values data objects (thi/id :m01))
             []))))

(t/deftest extract-properties-values-normal
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)
        result  (fv/extract-properties-values data objects (thi/id :v01))]
    (t/is (seq result))
    (t/is (every? #(contains? % :name) result))
    (t/is (every? #(contains? % :value) result))
    (t/is (= (:name (first result)) "Property 1"))
    (t/is (= (set (:value (first result))) #{"Value1" "Value2"}))))

(t/deftest extract-properties-values-two-properties
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)
        result  (fv/extract-properties-values data objects (thi/id :v01))]
    (t/is (= (count result) 2))
    (t/is (= (set (map :name result)) #{"Property 1" "Property 2"}))))

;; ============================================================
;; is-secondary-variant?
;; ============================================================

(t/deftest is-secondary-variant-primary
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data      (:data file)
        component (thc/get-component file :c01)]
    (t/is (not (fv/is-secondary-variant? data component)))))

(t/deftest is-secondary-variant-secondary
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data      (:data file)
        component (thc/get-component file :c02)]
    (t/is (fv/is-secondary-variant? data component))))

(t/deftest is-secondary-variant-not-variant
  (let [file      (-> (thf/sample-file :file1)
                      (tho/add-simple-component :c01 :m01 :s01))
        data      (:data file)
        component (thc/get-component file :c01)]
    (t/is (not (fv/is-secondary-variant? data component)))))

(t/deftest is-secondary-variant-no-shapes
  (let [file     (thf/sample-file :file1)
        data     (:data file)
        component {:id :comp :variant-id (thi/id :file1) :main-instance-page (thi/id :file1)}]
    (t/is (not (fv/is-secondary-variant? data component)))))

;; ============================================================
;; get-primary-variant
;; ============================================================

(t/deftest get-primary-variant-nil
  (let [file (thf/sample-file :file1)
        data (:data file)]
    (t/is (nil? (fv/get-primary-variant data nil)))))

(t/deftest get-primary-variant-empty
  (let [file     (thf/sample-file :file1)
        data     (:data file)
        component {:id :comp :variant-id (thi/id :file1) :main-instance-page (thi/id :file1)}]
    (t/is (nil? (fv/get-primary-variant data component)))))

(t/deftest get-primary-variant-normal
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data      (:data file)
        component (thc/get-component file :c01)
        result    (fv/get-primary-variant data component)]
    (t/is (some? result))
    (t/is (contains? result :id))
    (t/is (contains? result :component-id))))
