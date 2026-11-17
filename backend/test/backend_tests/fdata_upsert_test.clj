;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.fdata-upsert-test
  "Covers the write contract of `fdata/upsert!` after the `legacy-db` backend
  became read-only."

  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- upsert-cause
  [params]
  (try
    (fdata/upsert! th/*system* params)
    nil
    (catch Exception cause
      cause)))

(t/deftest legacy-db-param-is-rejected
  ;; `legacy-db` is not a write backend anymore: the schema refuses it before
  ;; touching the database.
  (let [cause (upsert-cause {:id (uuid/next)
                             :file-id (uuid/next)
                             :type "snapshot"
                             :backend "legacy-db"
                             :storage-object-id nil
                             :data (byte-array [1 2 3])})]
    (t/is (some? cause))
    (t/is (= :assertion (:type (ex-data cause))))
    (t/is (= :data-validation (:code (ex-data cause))))))

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

(t/deftest legacy-db-config-is-rejected-as-read-only
  ;; The configuration value is kept for reading old data, but a write with
  ;; it as the default backend fails with a clear message.
  (with-redefs [cf/config (assoc cf/config :file-data-backend "legacy-db")]
    (let [cause (upsert-cause {:id (uuid/next)
                               :file-id (uuid/next)
                               :type "snapshot"
                               :storage-object-id nil
                               :data (byte-array [1 2 3])})]
      (t/is (some? cause))
      (t/is (= :validation (:type (ex-data cause))))
      (t/is (= :legacy-backend-read-only (:code (ex-data cause)))))))

(t/deftest file-data-backend-defaults-to-db
  (t/is (= "db" (cf/get :file-data-backend))))
