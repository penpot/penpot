;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files.changes-test
  (:require
   [app.common.files.changes :as ch]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- make-file
  [page-id main-id copy-id]
  (let [comp-id (uuid/next)
        child1  (uuid/next)
        child2  (uuid/next)]
    {:pages-index {page-id {:id page-id
                            :objects {main-id {:id main-id
                                               :type :frame
                                               :main-instance true
                                               :component-id comp-id
                                               :shapes [child1 child2]}
                                      child1 {:id child1 :parent-id main-id}
                                      child2 {:id child2 :parent-id main-id}
                                      copy-id {:id copy-id
                                               :type :frame
                                               :component-root true
                                               :component-id comp-id
                                               :shapes [(uuid/next) (uuid/next)]}}}}
     :components {comp-id {:id comp-id
                           :main-instance-id main-id
                           :main-instance-page page-id}}}))

(t/deftest components-changed-reorder-children
  (t/testing "reorder-children in a main instance triggers component sync"
    (let [page-id (uuid/next)
          main-id (uuid/next)
          copy-id (uuid/next)
          file-data (make-file page-id main-id copy-id)
          comp-id (-> file-data :components first key)
          change {:type :reorder-children
                  :page-id page-id
                  :parent-id main-id
                  :shapes [(uuid/next) (uuid/next)]}]
      (t/is (= #{comp-id} (ch/components-changed file-data change)))))

  (t/testing "reorder-children inside a plain copy does not trigger component sync"
    (let [page-id (uuid/next)
          main-id (uuid/next)
          copy-id (uuid/next)
          file-data (make-file page-id main-id copy-id)
          change {:type :reorder-children
                  :page-id page-id
                  :parent-id copy-id
                  :shapes [(uuid/next) (uuid/next)]}]
      (t/is (= #{} (ch/components-changed file-data change))))))
