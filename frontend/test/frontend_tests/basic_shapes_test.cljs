;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns frontend-tests.basic-shapes-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.changes :as dch]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/deftest test-update-shape
  (t/async
    done
    (let [;; ==== Setup
          store
          (ths/setup-store
           (-> (cthf/sample-file :file1 :page-label :page1)
               (cths/add-sample-shape :shape1)))

         ;; ==== Action
          events
          [(dch/update-shapes [(cthi/id :shape1)]
                              #(assoc % :fills
                                      (cths/sample-fills-color :fill-color
                                                               "#fabada")))]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               shape1' (get-in new-state [:workspace-data
                                          :pages-index
                                          (cthi/id :page1)
                                          :objects
                                          (cthi/id :shape1)])
               fills'      (:fills shape1')
               fill'       (first fills')]

           (cthf/dump-shape shape1')

          ;; ==== Check
           (t/is (some? shape1'))
           (t/is (= (count fills') 1))
           (t/is (= (:fill-color fill') "#fabada"))
           (t/is (= (:fill-opacity fill') 1))))))))
