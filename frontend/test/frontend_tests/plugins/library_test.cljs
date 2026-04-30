;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.library-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.workspace.libraries :as dwl]
   [cljs.test :as t :include-macros true]))

(t/deftest delete-typography-expects-raw-id
  (let [id (uuid/next)]
    (t/testing "raw uuid produces the delete event payload"
      (t/is (= {:id id}
               (ev/-data (dwl/delete-typography id)))))

    (t/testing "map-wrapped id is rejected before producing an event"
      (t/is (thrown? js/Error
                     (dwl/delete-typography {:id id}))))))
