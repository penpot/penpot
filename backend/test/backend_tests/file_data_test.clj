;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.file-data-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; The storage pointer of a `file_data` row is a column with a foreign key and
;; it is the only source of truth: the release is a coordinated restart and
;; the metadata no longer carries `storage-ref-id`.

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

(t/deftest upsert-to-storage-writes-the-column
  (let [file (create-file!)
        data (blob/encode {:pages [] :pages-index {}})]

    ;; `main` always carries `id = file_id`: update the row the file already
    ;; has instead of inventing a second one.
    (upsert! {:id (:id file)
              :file-id (:id file)
              :type "main"
              :backend "storage"
              :storage-object-id nil
              :data data
              :metadata {:generated-by "test"}})

    (let [row      (th/db-get :file-data {:file-id (:id file) :id (:id file)})
          metadata (fdata/decode-metadata (:metadata row))
          sobject  (:storage-object-id row)]

      (t/testing "the row points at the object with the column"
        (t/is (= "storage" (:backend row)))
        (t/is (nil? (:data row)))
        (t/is (uuid? sobject)))

      (t/testing "the metadata no longer carries the pointer"
        (t/is (nil? (:storage-ref-id metadata)))
        (t/is (= "test" (:generated-by metadata))))

      (t/testing "the object is stored in the file-data bucket"
        (t/is (= "file-data" (:bucket (meta (sto/get-object (::sto/storage th/*system*)
                                                            sobject))))))

      (t/testing "the row resolves to the stored bytes"
        (t/is (= (vec data) (vec (:data (resolve! row)))))))))

(t/deftest upsert-back-to-db-clears-the-column-and-touches-the-object
  (let [file (create-file!)]

    (upsert! {:id (:id file)
              :file-id (:id file)
              :type "main"
              :backend "storage"
              :storage-object-id nil
              :data (blob/encode {:pages [] :pages-index {}})})

    (let [sobject (:storage-object-id (th/db-get :file-data
                                                 {:file-id (:id file) :id (:id file)}))]

      ;; The caller passes the object the persisted row points at; `upsert!`
      ;; touches it before replacing the row. The dead metadata pointer must
      ;; not survive the write either.
      (upsert! {:id (:id file)
                :file-id (:id file)
                :type "main"
                :backend "db"
                :storage-object-id sobject
                :data (blob/encode {:pages [] :pages-index {}})
                :metadata {:generated-by "test"
                           :storage-ref-id (uuid/next)}})

      (let [row      (th/db-get :file-data {:file-id (:id file) :id (:id file)})
            metadata (fdata/decode-metadata (:metadata row))]

        (t/testing "the hot row no longer references the object"
          (t/is (= "db" (:backend row)))
          (t/is (nil? (:storage-object-id row)))
          (t/is (nil? (:storage-ref-id metadata)))
          (t/is (= "test" (:generated-by metadata))))

        (t/testing "the replaced object is touched for the storage GC"
          (t/is (some? (:touched-at (find-object sobject)))))))))

(t/deftest resolve-file-data-uses-the-column-and-ignores-the-metadata
  (let [file  (create-file!)
        data  (blob/encode {:pages [] :pages-index {}})
        obj   (put-object! "file-data"
                           {:file-id (:id file) :id (:id file)}
                           (sto/content data))
        ;; A different, well-formed pointer in the metadata: it must be
        ;; ignored now that the column is the only source of truth.
        other (put-object! "file-data"
                           {:file-id (uuid/next) :id (uuid/next)})]

    (th/db-update! :file-data
                   {:backend "storage"
                    :data nil
                    :storage-object-id (:id obj)
                    :metadata (db/json {"storage-ref-id" (str (:id other))})}
                   {:file-id (:id file) :id (:id file)})

    (t/testing "the column wins over the metadata"
      (let [row (th/db-get :file-data {:file-id (:id file) :id (:id file)})]
        (t/is (= (:id obj) (:storage-object-id row)))
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

    (th/db-update! :file-data
                   {:backend "storage"
                    :data nil
                    :storage-object-id (:id sobject)}
                   {:file-id (:id file) :id (:id file)})

    (let [result (th/run-task! :storage-gc-touched {})]
      (t/is (= 1 (:freeze result)))
      (t/is (= 0 (:delete result)))
      (t/is (nil? (:touched-at (find-object (:id sobject))))))))

(t/deftest update-file!-ignores-the-pointer-in-the-params
  ;; `:storage-object-id` is bookkeeping of the persisted row, not an editable
  ;; field: a param must not be able to drop the pointer and skip the touch.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]

    (th/run-task! :offload-file-data {:file-id (:id file)})
    (th/run-task! :storage-gc-touched {:skip-delay true})

    (let [sobject (:storage-object-id (th/db-get :file-data {:file-id (:id file)
                                                             :id (:id file)}))]
      (t/is (uuid? sobject))
      (t/is (nil? (:touched-at (find-object sobject))))

      (db/tx-run! th/*system*
                  (fn [cfg]
                    (let [file (bfc/get-file cfg (:id file)
                                             :migrate? false
                                             :lock-for-update? true)]
                      (bfc/update-file! cfg file
                                        :storage-object-id nil
                                        :modified-at (ct/now)))))

      (t/testing "the previous object is still touched"
        (t/is (some? (:touched-at (find-object sobject))))))))

(t/deftest resolve-file-data-reports-a-missing-storage-object
  (let [file (create-file!)]

    (th/db-update! :file-data
                   {:backend "storage"
                    :data nil
                    :storage-object-id nil}
                   {:file-id (:id file) :id (:id file)})

    (let [cause (try
                  (db/tx-run! th/*system*
                              (fn [cfg]
                                (->> (th/db-get :file-data {:file-id (:id file)
                                                            :id (:id file)})
                                     (fdata/resolve-file-data cfg))))
                  nil
                  (catch clojure.lang.ExceptionInfo cause cause))]
      (t/is (some? cause))
      (t/is (= :missing-storage-object (:code (ex-data cause))))
      (t/is (= (:id file) (:file-id (ex-data cause)))))))

(t/deftest load-pointer-reports-the-file-when-the-object-is-gone
  (let [file     (create-file!)
        fragment (first (th/db-query :file-data {:file-id (:id file)
                                                 :type "fragment"}))
        sobject  (put-object! "file-data"
                              {:file-id (:id file) :id (:id fragment)})]

    (t/is (uuid? (:id fragment)))

    ;; The foreign key accepts the row (the object exists); a soft-deleted
    ;; object is simply unreadable, which is the case the error reports.
    (th/db-update! :storage-object
                   {:deleted-at (ct/in-past "1h")}
                   {:id (:id sobject)})
    (th/db-update! :file-data
                   {:backend "storage"
                    :data nil
                    :storage-object-id (:id sobject)}
                   {:file-id (:id file) :id (:id fragment)})

    (let [cause (try
                  (db/tx-run! th/*system*
                              (fn [cfg]
                                (fdata/load-pointer cfg (:id file) (:id fragment))))
                  nil
                  (catch clojure.lang.ExceptionInfo cause cause))
          data  (ex-data cause)]
      (t/is (some? cause))
      (t/is (= :missing-storage-object (:code data)))
      (t/is (= (:id file) (:file-id data)))
      (t/is (= (:id fragment) (:id data))))))

(t/deftest update-file!-rejects-a-map-without-data
  ;; A map read with `:load-data? false` has no `:data` and no storage pointer;
  ;; persisting it would wipe the content and skip the touch. The guard is an
  ;; explicit raise, not an assert, so it also exists in production.
  (let [file  (create-file!)
        cause (try
                (db/tx-run! th/*system*
                            (fn [cfg]
                              (bfc/update-file! cfg (bfc/get-file cfg (:id file)
                                                                  :load-data? false))))
                nil
                (catch clojure.lang.ExceptionInfo cause cause))]
    (t/is (some? cause))
    (t/is (= :missing-file-data (:code (ex-data cause))))))

(t/deftest update-file!-ignores-the-identity-in-the-params
  ;; The params change mutable fields only: `:id` cannot retarget the UPDATE.
  (let [file  (create-file!)
        other (uuid/next)
        when  (ct/inst "2020-01-01T00:00:00Z")]

    (db/tx-run! th/*system*
                (fn [cfg]
                  (let [file (bfc/get-file cfg (:id file)
                                           :migrate? false
                                           :lock-for-update? true)]
                    (bfc/update-file! cfg file :id other :modified-at when))))

    (t/testing "the read file is updated"
      (t/is (= when (:modified-at (th/db-get :file {:id (:id file)})))))

    (t/testing "the id in the params is ignored"
      (t/is (nil? (th/db-get :file {:id other}))))))

(t/deftest update-file!-touches-the-previous-object-on-a-storage-write
  ;; With `:file-data-backend` = "storage", every ordinary save replaces the
  ;; object of the row: the previous one must be touched so the storage GC can
  ;; reclaim it. The storage -> db and db -> storage directions are covered
  ;; above; this pins the configuration where storage is the default.
  (with-redefs [cf/get (th/config-get-mock {:file-data-backend "storage"})]
    (let [profile  (th/create-profile* 1)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)})
          previous (:storage-object-id (th/db-get :file-data
                                                  {:file-id (:id file) :id (:id file)}))]

      (t/is (uuid? previous))

      ;; A new object is born touched; clear the mark so the assertion below
      ;; proves the save is what touches it.
      (th/db-update! :storage-object {:touched-at nil} {:id previous})
      (t/is (nil? (:touched-at (find-object previous))))

      (db/tx-run! th/*system*
                  (fn [cfg]
                    (let [file (bfc/get-file cfg (:id file)
                                             :migrate? false
                                             :lock-for-update? true)]
                      (bfc/update-file! cfg file :modified-at (ct/now)))))

      (let [row     (th/db-get :file-data {:file-id (:id file) :id (:id file)})
            current (:storage-object-id row)]

        (t/testing "the row points at a new object"
          (t/is (uuid? current))
          (t/is (not= previous current)))

        (t/testing "the previous object is touched for the storage GC"
          (t/is (some? (:touched-at (find-object previous)))))))))
