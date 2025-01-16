;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns frontend-tests.logic.groups-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.groups :as dwgr]
   [app.main.data.workspace.selection :as dws]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})


(t/deftest test-create-group
  (t/async
    done
    (let [;; ==== Setup
          file       (-> (cthf/sample-file :file1)
                         (cths/add-sample-shape :test-shape))
          store      (ths/setup-store file)
          test-shape (cths/get-shape file :test-shape)

          ;; ==== Action
          events
          [(dws/select-shapes (d/ordered-set (:id test-shape)))
           (dwgr/group-selected)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               group-id      (->> (:objects page')
                                  vals
                                  (filter #(= :group (:type %)))
                                  first
                                  :id)]
           ;; ==== Check
           ;; Group has been created and is selected
           (t/is (= (get-in new-state [:workspace-local :selected]) #{group-id}))))))))
