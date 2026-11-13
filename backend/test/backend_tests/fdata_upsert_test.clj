;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.fdata-upsert-test
  "Covers the invariants of `fdata/upsert!` that the partition runbook relies
  on."

  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-changes :as fchanges]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest legacy-db-snapshot-moves-the-updated-at-watermark
  ;; The runbook of the partition picks the rows changed since the bulk copy
  ;; with `updated_at`, so every mutation of `file_change` has to move it. The
  ;; `legacy-db` snapshot path writes the payload into the row, and it used to
  ;; leave the watermark behind.
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        id       (uuid/next)
        past     (ct/in-past "2h")
        payload  (byte-array [1 2 3])]

    (th/db-insert! :file-change
                   {:id         id
                    :file-id    (:id file)
                    :revn       1
                    :label      "v1"
                    :kind       fchanges/snapshot-kind
                    :updated-at past})

    (t/is (true? (fdata/upsert! th/*system*
                                {:id      id
                                 :file-id (:id file)
                                 :type    "snapshot"
                                 :backend "legacy-db"
                                 :data    payload})))

    (let [row (th/db-get :file-change {:id id} {::db/remove-deleted false})]
      (t/testing "the payload lands in the legacy column"
        (t/is (= (seq payload) (seq ^bytes (:data row)))))

      (t/testing "the watermark moves to the moment of the mutation"
        (t/is (pos? (compare (inst-ms (:updated-at row)) (inst-ms past))))))))
