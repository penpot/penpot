;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns frontend-tests.logic.frame-guides-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.guides :as-alias dwg]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})


(t/deftest test-remove-swap-slot-copy-paste-blue1-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame1))
          store    (ths/setup-store file)
          frame1   (cths/get-shape file :frame1)

          guide {:axis :x
                 :frame-id (:id frame1)
                 :id (uuid/next)
                 :position 0}

         ;; ==== Action
          events
          [(dw/update-guides guide)
           (dw/update-position (:id frame1) {:x 100})]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')

               guide'        (-> page'
                                 :guides
                                 (vals)
                                 (first))]
           ;; ==== Check
           ;; guide has moved
           (t/is (= (:position guide') 100))))))))


