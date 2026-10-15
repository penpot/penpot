;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.override-scan-test
  (:require
   [app.common.logic.override-scan :as clos]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- file-with-copy
  []
  (-> (thf/sample-file :file1)
      (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root
                                          :copy-root-params {:children-labels [:copy-child]})))

(defn- red-fills
  []
  (ths/sample-fills-color :fill-color "#ff0000"))

(t/deftest a-synced-copy-differs-in-nothing
  (t/is (empty? (clos/scan (file-with-copy) {}))))

(t/deftest an-untouched-difference-is-found
  (let [file     (-> (file-with-copy)
                     (ths/update-shape :copy-child :fills (red-fills))
                     (ths/update-shape :copy-child :touched nil))
        findings (clos/scan file {})]
    (t/is (= [{:id     (thi/id :copy-child)
               :groups #{:fill-group}
               :attrs  #{:fills}}]
             (map #(select-keys % [:id :groups :attrs]) findings)))))

(t/deftest an-override-is-not-a-difference
  (let [file (-> (file-with-copy)
                 (ths/update-shape :copy-child :fills (red-fills)))]
    (t/is (= #{:fill-group} (:touched (ths/get-shape file :copy-child))))
    (t/is (empty? (clos/scan file {})))))

(t/deftest a-skipped-component-is-not-scanned
  (let [file (-> (file-with-copy)
                 (ths/update-shape :copy-child :fills (red-fills))
                 (ths/update-shape :copy-child :touched nil))]
    (t/is (empty? (clos/scan file {} :skip-component? (constantly true))))))

(t/deftest a-copy-of-a-library-component-is-scanned
  (let [library (-> (thf/sample-file :library :is-shared true)
                    (tho/add-simple-component :component1 :main-root :main-child))
        file    (-> (thf/sample-file :file1)
                    (thc/instantiate-component :component1 :copy-root
                                               :library library
                                               :children-labels [:copy-child])
                    (ths/update-shape :copy-child :fills (red-fills))
                    (ths/update-shape :copy-child :touched nil))]
    (t/is (= [(thi/id :copy-child)]
             (map :id (clos/scan file {(:id library) library}))))))
