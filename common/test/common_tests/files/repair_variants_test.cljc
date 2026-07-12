;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.repair-variants-test
  (:require
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- sample-variant-file
  []
  (-> (thf/sample-file :file1)
      (thv/add-variant :v01 :c01 :m01 :c02 :m02)))

(defn- error-codes
  [file]
  (into #{} (map :code) (cfv/validate-file file {})))

(defn- repair
  [file]
  (let [errors  (cfv/validate-file file {})
        changes (cfr/repair-file file {} errors)]
    (thf/apply-changes file {:redo-changes changes} :validate? false)))

(t/deftest sample-variant-file-is-valid
  (let [file (sample-variant-file)]
    (t/is (empty? (cfv/validate-file file {})))))

(t/deftest repair-variant-bad-name
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :m01 :name "Wrong name"))
        _     (t/is (= #{:variant-bad-name} (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= (:name (ths/get-shape file' :v01))
             (:name (ths/get-shape file' :m01))))))

(t/deftest repair-variant-bad-variant-name
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :m01 :variant-name "Wrong variant name"))
        _     (t/is (= #{:variant-bad-variant-name} (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= "Value1" (:variant-name (ths/get-shape file' :m01))))))

(t/deftest repair-variant-component-bad-name
  (let [file  (-> (sample-variant-file)
                  (thc/update-component :c01 {:name "Wrong name" :path "Wrong path"}))
        _     (t/is (= #{:variant-component-bad-name} (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= (:name (ths/get-shape file' :v01))
             (:name (thc/get-component file' :c01))))
    (t/is (= "" (:path (thc/get-component file' :c01))))))

(t/deftest repair-variant-component-bad-name-with-path
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :v01 :name "Group / Subgroup / Button")
                  (ths/update-shape :m01 :name "Group / Subgroup / Button")
                  (ths/update-shape :m02 :name "Group / Subgroup / Button"))
        _     (t/is (= #{:variant-component-bad-name} (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= "Button" (:name (thc/get-component file' :c01))))
    (t/is (= "Group / Subgroup" (:path (thc/get-component file' :c01))))))

(t/deftest repair-variant-component-bad-id
  (let [file  (-> (sample-variant-file)
                  (thc/update-component :c01 {:variant-id (uuid/next)}))
        _     (t/is (= #{:variant-component-bad-id} (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= (:id (ths/get-shape file' :v01))
             (:variant-id (thc/get-component file' :c01))))))

(t/deftest repair-invalid-variant-id
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :m01 :variant-id (uuid/next)))
        _     (t/is (= #{:invalid-variant-id :variant-component-bad-id}
                       (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= (:id (ths/get-shape file' :v01))
             (:variant-id (ths/get-shape file' :m01))))
    (t/is (= (:id (ths/get-shape file' :v01))
             (:variant-id (thc/get-component file' :c01))))))

(t/deftest repair-variant-bad-name-with-unicode-path
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :v01 :name "Íconos / 按钮 / Botón ✨")
                  (ths/update-shape :m01 :name "Íconos / 按钮 / Botón ✨"))
        _     (t/is (= #{:variant-bad-name :variant-component-bad-name}
                       (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= "Botón ✨" (:name (thc/get-component file' :c01))))
    (t/is (= "Íconos / 按钮" (:path (thc/get-component file' :c01))))))

(t/deftest repair-variant-without-container-is-left-untouched
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :v01 :is-variant-container false)
                  (ths/update-shape :m01 :name "Wrong name"))
        _     (t/is (= #{:parent-not-variant :variant-bad-name}
                       (error-codes file)))

        file' (repair file)]

    ;; The container cannot be determined, so nothing is guessed
    (t/is (= #{:parent-not-variant :variant-bad-name} (error-codes file')))
    (t/is (= "Wrong name" (:name (ths/get-shape file' :m01))))))

(t/deftest repair-variant-component-with-unnormalized-container-name
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :v01 :name "Group/Subgroup/Button")
                  (ths/update-shape :m01 :name "Group/Subgroup/Button")
                  (ths/update-shape :m02 :name "Group/Subgroup/Button"))
        _     (t/is (= #{:variant-component-bad-name} (error-codes file)))

        file' (repair file)]

    ;; The container name is not renamed, and the component is named after it
    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= "Group/Subgroup/Button" (:name (ths/get-shape file' :v01))))
    (t/is (= "Group/Subgroup/Button" (:name (thc/get-component file' :c01))))
    (t/is (= "" (:path (thc/get-component file' :c01))))))

(t/deftest repair-variant-component-with-padded-container-name
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :v01 :name "Button ")
                  (ths/update-shape :m01 :name "Button ")
                  (ths/update-shape :m02 :name "Button "))
        _     (t/is (= #{:variant-component-bad-name} (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= "Button " (:name (ths/get-shape file' :v01))))
    (t/is (= "Button " (:name (thc/get-component file' :c01))))
    (t/is (= "" (:path (thc/get-component file' :c01))))))

(t/deftest repair-is-a-fixed-point
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :v01 :name "Group/Subgroup/Button")
                  (ths/update-shape :m01 :name "Wrong name")
                  (ths/update-shape :m02 :name "Wrong name")
                  (thc/update-component :c01 {:variant-id (uuid/next)}))
        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= file' (repair file')))))

(t/deftest repair-variant-of-a-deleted-component
  (let [file  (-> (sample-variant-file)
                  (thc/update-component :c01 {:deleted true
                                              :name "Wrong name"}))
        _     (t/is (= #{:variant-component-bad-name} (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))
    (t/is (= (:name (ths/get-shape file' :v01))
             (:name (thc/get-component file' :c01 :include-deleted? true))))))

(t/deftest repair-several-variant-errors-at-once
  (let [file  (-> (sample-variant-file)
                  (ths/update-shape :m01 :name "Wrong name")
                  (ths/update-shape :m01 :variant-name "Wrong variant name")
                  (ths/update-shape :m02 :variant-id (uuid/next))
                  (thc/update-component :c02 {:name "Wrong name"}))
        _     (t/is (= #{:variant-bad-name
                         :variant-bad-variant-name
                         :variant-component-bad-name
                         :invalid-variant-id
                         :variant-component-bad-id}
                       (error-codes file)))

        file' (repair file)]

    (t/is (empty? (cfv/validate-file file' {})))))
