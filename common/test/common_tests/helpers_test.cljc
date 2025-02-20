;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.helpers-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest create-file
  (let [f1 (thf/sample-file :file1)
        f2 (thf/sample-file :file2 :page-label :page1)
        f3 (thf/sample-file :file3 :name "testing file")
        f4 (-> (thf/sample-file :file4 :page-label :page2)
               (thf/add-sample-page :page3 :name "testing page")
               (ths/add-sample-shape :shape1))
        f5 (-> f4
               (ths/add-sample-shape :shape2)
               (thf/switch-to-page :page2)
               (ths/add-sample-shape :shape3 :name "testing shape" :width 100))
        s1 (ths/get-shape f4 :shape1)
        s2 (ths/get-shape f5 :shape2 :page-label :page3)
        s3 (ths/get-shape f5 :shape3)]

    ;; (thf/pprint-file f4)

    (t/is (= (:name f1) "Test file"))
    (t/is (= (:name f3) "testing file"))
    (t/is (= (:id f2) (thi/id :file2)))
    (t/is (= (:id f4) (thi/id :file4)))
    (t/is (= (-> f4 :data :pages-index vals first :id) (thi/id :page2)))
    (t/is (= (-> f4 :data :pages-index vals first :name) "Page 1"))
    (t/is (= (-> f4 :data :pages-index vals second :id) (thi/id :page3)))
    (t/is (= (-> f4 :data :pages-index vals second :name) "testing page"))

    (t/is (= (:id (thf/current-page f2)) (thi/id :page1)))
    (t/is (= (:id (thf/current-page f4)) (thi/id :page3)))
    (t/is (= (:id (thf/current-page f5)) (thi/id :page2)))

    (t/is (= (:id s1) (thi/id :shape1)))
    (t/is (= (:name s1) "Rectangle"))
    (t/is (= (:id s2) (thi/id :shape2)))
    (t/is (= (:name s2) "Rectangle"))
    (t/is (= (:id s3) (thi/id :shape3)))
    (t/is (= (:name s3) "testing shape"))
    (t/is (= (:width s3) 100))
    (t/is (= (:width (:selrect s3)) 100))))

(t/deftest create-components
  (let [f1 (-> (thf/sample-file :file1)
               (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root))]

    #_(thf/dump-file f1)
    #_(thf/pprint-file f4)

    (t/is (= (:name f1) "Test file"))))
