;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.variant-test
  (:require
   [app.common.files.variant :as cfv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.uuid :as uuid]
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

(defn- variant-file
  []
  (-> (thf/sample-file :file1)
      (thv/add-variant :v01 :c01 :m01 :c02 :m02)))

(t/deftest test-update-variant-properties-writes-props-and-label
  (let [file  (variant-file)
        props [{:name "Property 1" :value "New"}]
        data' (cfv/update-variant-properties (:data file) (thc/get-component file :c01) (constantly props))
        file' (assoc file :data data')]

    (t/is (= props (:variant-properties (thc/get-component file' :c01))))
    (t/is (= "New" (:variant-name (ths/get-shape file' :m01))))))

(t/deftest test-update-variant-properties-leaves-clean-data-unchanged
  (let [file (variant-file)
        data (:data file)]

    (t/is (= data (cfv/update-variant-properties data (thc/get-component file :c01) identity)))))

(t/deftest test-update-variant-properties-writes-the-label-of-a-deleted-component
  (let [file       (variant-file)
        deleted-id (uuid/next)
        main-id    (uuid/next)
        component  {:id deleted-id
                    :name "Gone"
                    :path ""
                    :deleted true
                    :variant-id (thi/id :v01)
                    :main-instance-id main-id
                    :main-instance-page (:id (thf/current-page file))
                    :variant-properties [{:name "Size" :value "big"}]
                    :objects {main-id {:id main-id
                                       :type :frame
                                       :name "Gone"
                                       :variant-id (thi/id :v01)
                                       :variant-name "stale"}}}
        data       (assoc-in (:data file) [:components deleted-id] component)
        data'      (cfv/update-variant-properties data component identity)]

    (t/is (= "big" (get-in data' [:components deleted-id :objects main-id :variant-name])))))

(t/deftest test-sanitize-variant-container-cleans-members-in-layers-panel-order
  ;; :shapes of the board is [m02 m01], so m01 is the top variant in the
  ;; layers panel and c01 keeps its value.
  (let [props     [{:name "Property 1" :value "a=b"}]
        file      (-> (variant-file)
                      (thc/update-component :c01 {:variant-properties props})
                      (thc/update-component :c02 {:variant-properties props}))
        page      (thf/current-page file)
        container (ths/get-shape file :v01)
        data'     (cfv/sanitize-variant-container (:data file) (:objects page) container)
        file'     (assoc file :data data')]

    (t/is (= [{:name "Property 1" :value "ab"}]
             (:variant-properties (thc/get-component file' :c01))))
    (t/is (= [{:name "Property 1" :value "ab (1)"}]
             (:variant-properties (thc/get-component file' :c02))))
    (t/is (= "ab (1)" (:variant-name (ths/get-shape file' :m02))))))

(t/deftest test-sanitize-variant-component
  (let [file      (-> (variant-file)
                      (thc/update-component :c01 {:variant-properties [{:name "Size=Big" :value "a,b"}]}))
        data'     (cfv/sanitize-variant-component (:data file) (thc/get-component file :c01))
        file'     (assoc file :data data')]

    (t/is (= [{:name "SizeBig" :value "ab"}]
             (:variant-properties (thc/get-component file' :c01))))
    (t/is (= "ab" (:variant-name (ths/get-shape file' :m01))))))

(t/deftest test-sanitize-variant-container-skips-a-child-of-another-set
  ;; m03 belongs to v02 but is listed in the :shapes of v01.
  (let [dirty     [{:name "Property 1" :value "a=b"}]
        file      (-> (variant-file)
                      (thv/add-variant :v02 :c03 :m03 :c04 :m04)
                      (thc/update-component :c03 {:variant-properties dirty}))
        shapes    (conj (:shapes (ths/get-shape file :v01)) (thi/id :m03))
        file      (ths/update-shape file :v01 :shapes shapes)
        page      (thf/current-page file)
        container (ths/get-shape file :v01)
        data'     (cfv/sanitize-variant-container (:data file) (:objects page) container)
        file'     (assoc file :data data')]

    (t/is (= dirty (:variant-properties (thc/get-component file' :c03))))
    (t/is (= (:variant-name (ths/get-shape file :m03))
             (:variant-name (ths/get-shape file' :m03))))))

;; ============================================================
;; find-variant-components
;; ============================================================

(t/deftest find-variant-components-empty
  (let [file    (thf/sample-file :file1)
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (cfv/find-variant-components data (uuid/next))
             []))
    (t/is (= (cfv/find-variant-components data objects (uuid/next))
             []))))

(t/deftest find-variant-components-non-variant
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component :c01 :m01 :s01))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (cfv/find-variant-components data (thi/id :m01))
             []))
    (t/is (= (cfv/find-variant-components data objects (thi/id :m01))
             []))))

(t/deftest find-variant-components-normal
  (let [file  (-> (thf/sample-file :file1)
                  (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data  (:data file)
        page  (thf/current-page file)
        objects (:objects page)
        result (cfv/find-variant-components data objects (thi/id :v01))]
    (t/is (= (count result) 2))
    (t/is (every? #(contains? % :id) result))
    (t/is (every? #(contains? % :variant-id) result))))

(t/deftest find-variant-components-single-variant
  (let [file  (-> (thf/sample-file :file1)
                  (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data  (:data file)
        page  (thf/current-page file)
        objects (:objects page)
        result (cfv/find-variant-components data objects (thi/id :v01))]
    ;; Verify the order is maintained (reversed from shapes order)
    (t/is (= (:variant-id (first result)) (thi/id :v01)))
    (t/is (= (:variant-id (second result)) (thi/id :v01)))))

;; ============================================================
;; extract-properties-names
;; ============================================================

(t/deftest extract-properties-names-non-component
  (let [file    (-> (thf/sample-file :file1)
                    (ths/add-sample-shape :s01))
        data    (:data file)
        shape   (ths/get-shape file :s01)]
    (t/is (= (cfv/extract-properties-names data shape)
             []))))

(t/deftest extract-properties-names-non-variant
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component :c01 :m01 :s01))
        data    (:data file)
        main    (ths/get-shape file :m01)]
    (t/is (= (cfv/extract-properties-names data main)
             []))))

(t/deftest extract-properties-names-normal
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data    (:data file)
        main1   (ths/get-shape file :m01)]
    (t/is (= (cfv/extract-properties-names data main1)
             ["Property 1"]))))

(t/deftest extract-properties-names-two-properties
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        data    (:data file)
        main1   (ths/get-shape file :m01)]
    (t/is (= (cfv/extract-properties-names data main1)
             ["Property 1" "Property 2"]))))

;; ============================================================
;; extract-properties-values
;; ============================================================

(t/deftest extract-properties-values-empty
  (let [file    (thf/sample-file :file1)
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (cfv/extract-properties-values data objects (uuid/next))
             []))))

(t/deftest extract-properties-values-non-variant
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component :c01 :m01 :s01))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)]
    (t/is (= (cfv/extract-properties-values data objects (thi/id :m01))
             []))))

(t/deftest extract-properties-values-normal
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)
        result  (cfv/extract-properties-values data objects (thi/id :v01))]
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
        result  (cfv/extract-properties-values data objects (thi/id :v01))]
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
    (t/is (not (cfv/is-secondary-variant? data component)))))

(t/deftest is-secondary-variant-secondary
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data      (:data file)
        component (thc/get-component file :c02)]
    (t/is (cfv/is-secondary-variant? data component))))

(t/deftest is-secondary-variant-not-variant
  (let [file      (-> (thf/sample-file :file1)
                      (tho/add-simple-component :c01 :m01 :s01))
        data      (:data file)
        component (thc/get-component file :c01)]
    (t/is (not (cfv/is-secondary-variant? data component)))))

(t/deftest is-secondary-variant-no-shapes
  (let [file     (thf/sample-file :file1)
        data     (:data file)
        component {:id :comp :variant-id (thi/id :file1) :main-instance-page (thi/id :file1)}]
    (t/is (not (cfv/is-secondary-variant? data component)))))

;; ============================================================
;; get-primary-variant
;; ============================================================

(t/deftest get-primary-variant-nil
  (let [file (thf/sample-file :file1)
        data (:data file)]
    (t/is (nil? (cfv/get-primary-variant data nil)))))

(t/deftest get-primary-variant-empty
  (let [file     (thf/sample-file :file1)
        data     (:data file)
        component {:id :comp :variant-id (thi/id :file1) :main-instance-page (thi/id :file1)}]
    (t/is (nil? (cfv/get-primary-variant data component)))))

(t/deftest get-primary-variant-normal
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data      (:data file)
        component (thc/get-component file :c01)
        result    (cfv/get-primary-variant data component)]
    (t/is (some? result))
    (t/is (contains? result :id))
    (t/is (contains? result :component-id))))

;; ============================================================
;; get-primary-component
;; ============================================================

(t/deftest get-primary-component-empty
  (let [file (thf/sample-file :file1)
        data (:data file)]
    (t/is (nil? (cfv/get-primary-component data nil)))
    (t/is (nil? (cfv/get-primary-component data (uuid/next))))))

(t/deftest get-primary-component-not-variant
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component :c01 :m01 :s01))
        data (:data file)
        result (cfv/get-primary-component data (thi/id :c01))]
    (t/is (= (:id result) (thi/id :c01)))
    (t/is (= result (thc/get-component file :c01)))))

(t/deftest get-primary-component-already-primary
  (let [file (-> (thf/sample-file :file1)
                 (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data (:data file)
        result (cfv/get-primary-component data (thi/id :c01))]
    (t/is (= (:id result) (thi/id :c01)))
    (t/is (= result (thc/get-component file :c01)))))

(t/deftest get-primary-component-secondary
  (let [file (-> (thf/sample-file :file1)
                 (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data (:data file)
        result (cfv/get-primary-component data (thi/id :c02))]
    (t/is (= (:id result) (thi/id :c01)))
    (t/is (= result (thc/get-component file :c01)))))
