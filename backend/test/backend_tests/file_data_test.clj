;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.file-data-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; The storage pointer of a `file_data` row is now a column with a foreign
;; key. These tests pin the write/read pair and the transitional dual write
;; (column plus `metadata.storage-ref-id`) that keeps a rolling deploy safe.

(defn- find-object
  [id]
  (th/db-get :storage-object {:id id} {::db/remove-deleted false}))

(defn- put-object!
  ([bucket metadata content]
   (sto/put-object! (::sto/storage th/*system*)
                    (merge {::sto/content content
                            :content-type "application/octet-stream"
                            :bucket bucket}
                           metadata)))
  ([bucket metadata]
   (put-object! bucket metadata (sto/content "sobject"))))

(defn- create-file!
  []
  (let [profile (th/create-profile* 1)]
    (th/create-file* 1 {:profile-id (:id profile)
                        :project-id (:default-project-id profile)})))

(defn- upsert!
  [params]
  (db/tx-run! th/*system*
              (fn [cfg]
                (fdata/upsert! cfg params))))

(defn- resolve!
  [row]
  (db/tx-run! th/*system*
              (fn [cfg]
                (fdata/resolve-file-data cfg row))))

(t/deftest upsert-to-storage-writes-the-column-and-the-metadata
  (let [file   (create-file!)
        id     (uuid/next)
        data   (blob/encode {:pages [] :pages-index {}})]

    (upsert! {:id id
              :file-id (:id file)
              :type "main"
              :backend "storage"
              :data data
              :metadata {:generated-by "test"}})

    (let [row      (th/db-get :file-data {:file-id (:id file) :id id})
          metadata (fdata/decode-metadata (:metadata row))
          sobject  (:storage-object-id row)]

      (t/testing "the row points at the object with the column"
        (t/is (= "storage" (:backend row)))
        (t/is (nil? (:data row)))
        (t/is (uuid? sobject)))

      (t/testing "the transitional dual write keeps the metadata pointer"
        (t/is (= sobject (:storage-ref-id metadata)))
        (t/is (= "test" (:generated-by metadata))))

      (t/testing "the object is stored in the file-data bucket"
        (t/is (= "file-data" (:bucket (meta (sto/get-object (::sto/storage th/*system*)
                                                            sobject))))))

      (t/testing "the row resolves to the stored bytes"
        (t/is (= (vec data) (vec (:data (resolve! row)))))))))

(t/deftest upsert-back-to-db-clears-the-column-and-touches-the-object
  (let [file (create-file!)
        id   (uuid/next)]

    (upsert! {:id id
              :file-id (:id file)
              :type "main"
              :backend "storage"
              :data (blob/encode {:pages [] :pages-index {}})})

    (let [sobject (:storage-object-id (th/db-get :file-data
                                                 {:file-id (:id file) :id id}))]

      (upsert! {:id id
                :file-id (:id file)
                :type "main"
                :backend "db"
                :data (blob/encode {:pages [] :pages-index {}})
                :metadata {:generated-by "test"}})

      (let [row      (th/db-get :file-data {:file-id (:id file) :id id})
            metadata (fdata/decode-metadata (:metadata row))]

        (t/testing "the hot row no longer references the object"
          (t/is (= "db" (:backend row)))
          (t/is (nil? (:storage-object-id row)))
          (t/is (nil? (:storage-ref-id metadata))))

        (t/testing "the replaced object is touched for the storage GC"
          (t/is (some? (:touched-at (find-object sobject)))))))))

(t/deftest resolve-file-data-falls-back-to-the-metadata
  (let [file    (create-file!)
        id      (uuid/next)
        data    (blob/encode {:pages [] :pages-index {}})
        sobject (put-object! "file-data"
                             {:file-id (:id file) :id id}
                             (sto/content data))]

    (th/db-insert! :file-data
                   {:file-id (:id file)
                    :id id
                    :type "main"
                    :backend "storage"
                    :metadata (db/json {"storage-ref-id" (str (:id sobject))})})

    (t/testing "a row with a NULL column still resolves through the metadata"
      (let [row (-> (th/db-get :file-data {:file-id (:id file) :id id})
                    (update :metadata fdata/decode-metadata))]
        (t/is (nil? (:storage-object-id row)))
        (t/is (= (vec data) (vec (:data (resolve! row)))))))))

(t/deftest gc-touched-freezes-an-object-referenced-only-by-the-column
  (let [file    (create-file!)
        ;; The object metadata points at other ids: the freeze must come from
        ;; the column, not from the old metadata lookup.
        sobject (put-object! "file-data"
                             {:file-id (uuid/next) :id (uuid/next)})]

    (th/db-update! :storage-object
                   {:touched-at (ct/in-past {:hours 3})}
                   {:id (:id sobject)})

    (th/db-insert! :file-data
                   {:file-id (:id file)
                    :id (uuid/next)
                    :type "main"
                    :backend "storage"
                    :storage-object-id (:id sobject)})

    (let [result (th/run-task! :storage-gc-touched {})]
      (t/is (= 1 (:freeze result)))
      (t/is (= 0 (:delete result)))
      (t/is (nil? (:touched-at (find-object (:id sobject))))))))
