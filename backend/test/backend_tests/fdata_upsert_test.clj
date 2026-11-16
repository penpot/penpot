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

    (t/is (true? (:written (fdata/upsert! th/*system*
                                          {:id                id
                                           :file-id           (:id file)
                                           :type              "snapshot"
                                           :backend           "legacy-db"
                                           :storage-object-id nil
                                           :data              payload}))))

    (let [row (th/db-get :file-change {:id id} {::db/remove-deleted false})]
      (t/testing "the payload lands in the legacy column"
        (t/is (= (seq payload) (seq ^bytes (:data row)))))

      (t/testing "the watermark moves to the moment of the mutation"
        (t/is (pos? (compare (inst-ms (:updated-at row)) (inst-ms past))))))))

(t/deftest upsert-reports-the-pointer-it-created
  ;; The write reports the object it created, so a caller that persists again
  ;; touches the current pointer instead of the replaced one.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        data    (byte-array [1 2 3])]

    (let [out (db/tx-run! th/*system*
                          (fn [cfg]
                            (fdata/upsert! cfg {:id                (:id file)
                                                :file-id           (:id file)
                                                :type              "main"
                                                :backend           "storage"
                                                :storage-object-id nil
                                                :data              data})))]
      (t/testing "a storage write reports the object it created"
        (t/is (true? (:written out)))
        (t/is (uuid? (:storage-object-id out)))
        (t/is (= (:storage-object-id out)
                 (:storage-object-id (th/db-get :file-data
                                                {:file-id (:id file)
                                                 :id (:id file)}))))))

    (let [previous (:storage-object-id (th/db-get :file-data
                                                  {:file-id (:id file)
                                                   :id (:id file)}))
          out      (db/tx-run! th/*system*
                               (fn [cfg]
                                 (fdata/upsert! cfg {:id                (:id file)
                                                     :file-id           (:id file)
                                                     :type              "main"
                                                     :backend           "db"
                                                     :storage-object-id previous
                                                     :data              data})))]
      (t/testing "a db write has no object to report"
        (t/is (true? (:written out)))
        (t/is (nil? (:storage-object-id out)))
        (t/is (nil? (:storage-object-id (th/db-get :file-data
                                                   {:file-id (:id file)
                                                    :id (:id file)}))))))))

(t/deftest upsert-rejects-a-stale-pointer
  ;; The write only replaces the row whose pointer the caller read. A stale
  ;; map must fail instead of touching the wrong object and leaking the blob.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        data    (byte-array [1 2 3])
        before  (th/db-get :file-data {:file-id (:id file) :id (:id file)})
        cause   (try
                  (db/tx-run! th/*system*
                              (fn [cfg]
                                (fdata/upsert! cfg {:id                (:id file)
                                                    :file-id           (:id file)
                                                    :type              "main"
                                                    :backend           "db"
                                                    :storage-object-id (uuid/next)
                                                    :data              data})))
                  nil
                  (catch clojure.lang.ExceptionInfo cause cause))]

    (t/is (= :stale-file-data-pointer (:code (ex-data cause))))

    (let [after (th/db-get :file-data {:file-id (:id file) :id (:id file)})]
      (t/testing "the row is untouched"
        (t/is (= (:backend before) (:backend after)))
        (t/is (= (:storage-object-id before) (:storage-object-id after)))
        (t/is (= (vec (:data before)) (vec (:data after))))))))
