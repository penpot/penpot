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

(t/deftest test-sanitize-variant-container-cleans-members-in-board-order
  ;; :shapes of the board is [m02 m01], so c02 keeps its value.
  (let [props     [{:name "Property 1" :value "a=b"}]
        file      (-> (variant-file)
                      (thc/update-component :c01 {:variant-properties props})
                      (thc/update-component :c02 {:variant-properties props}))
        page      (thf/current-page file)
        container (ths/get-shape file :v01)
        data'     (cfv/sanitize-variant-container (:data file) (:objects page) container)
        file'     (assoc file :data data')]

    (t/is (= [{:name "Property 1" :value "ab"}]
             (:variant-properties (thc/get-component file' :c02))))
    (t/is (= [{:name "Property 1" :value "ab (1)"}]
             (:variant-properties (thc/get-component file' :c01))))
    (t/is (= "ab (1)" (:variant-name (ths/get-shape file' :m01))))))

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
