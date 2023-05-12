;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-file-thumbnails-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as cauth]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [backend-tests.helpers :as th]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest upsert-file-object-thumbnail
  (let [storage (::sto/storage th/*system*)
        profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        shid    (uuid/random)
        page-id (first (get-in file [:data :pages]))

        ;; Update file inserting a new frame object
        _       (th/update-file!
                 :file-id (:id file)
                 :profile-id (:id profile)
                 :revn 0
                 :changes
                 [{:type :add-obj
                   :page-id page-id
                   :id shid
                   :parent-id uuid/zero
                   :frame-id uuid/zero
                   :components-v2 true
                   :obj {:id shid
                         :name "Artboard"
                         :frame-id uuid/zero
                         :parent-id uuid/zero
                         :type :frame}}])

        data1   {::th/type :create-file-object-thumbnail
                 ::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :object-id "test-key-1"
                 :media {:filename "sample.jpg"
                         :size 312043
                         :path (th/tempfile "backend_tests/test_files/sample.jpg")
                         :mtype "image/jpeg"}}

        data2   {::th/type :create-file-object-thumbnail
                 ::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :object-id (str page-id shid)
                 :media {:filename "sample.jpg"
                         :size 7923
                         :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                         :mtype "image/jpeg"}}]

    (let [out (th/command! data1)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/command! data2)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [[row1 row2 :as rows] (th/db-query :file-object-thumbnail
                                            {:file-id (:id file)}
                                            {:order-by [[:created-at :asc]]})]

      (t/is (= 2 (count rows)))
      (t/is (= (:file-id data1) (:file-id row1)))
      (t/is (= (:object-id data1) (:object-id row1)))
      (t/is (uuid? (:media-id row1)))
      (t/is (= (:file-id data2) (:file-id row2)))
      (t/is (= (:object-id data2) (:object-id row2)))
      (t/is (uuid? (:media-id row2)))

      (let [sobject (sto/get-object storage (:media-id row1))
            mobject (meta sobject)]
        (t/is (= "blake2b:4fdb63b8f3ffc81256ea79f13e53f366723b188554b5afed91b20897c14a1a8e" (:hash mobject)))
        (t/is (= "file-object-thumbnail" (:bucket mobject)))
        (t/is (= "image/jpeg" (:content-type mobject)))
        (t/is (= 312043 (:size sobject))))

      (let [sobject (sto/get-object storage (:media-id row2))
            mobject (meta sobject)]
        (t/is (= "blake2b:05870e3f8ee885841ee3799924d80805179ab57e6fde84a605d1068fd3138de9" (:hash mobject)))
        (t/is (= "file-object-thumbnail" (:bucket mobject)))
        (t/is (= "image/jpeg" (:content-type mobject)))
        (t/is (= 7923 (:size sobject))))

      ;; Run the File GC task that should remove unused file object
      ;; thumbnails
      (let [result (th/run-task! :file-gc {:min-age (dt/duration 0)})]
        (t/is (= 1 (:processed result))))

      ;; check if row2 related thumbnail row still exists
      (let [[row :as rows] (th/db-query :file-object-thumbnail
                                        {:file-id (:id file)}
                                        {:order-by [[:created-at :asc]]})]
        (t/is (= 1 (count rows)))
        (t/is (= (:file-id data2) (:file-id row)))
        (t/is (= (:object-id data2) (:object-id row)))
        (t/is (uuid? (:media-id row2))))

      ;; Check if storage objects still exists after file-gc
      (t/is (nil? (sto/get-object storage (:media-id row1))))
      (t/is (some? (sto/get-object storage (:media-id row2))))

      ;; check that storage object is still exists but is marked as deleted
      (let [row (th/db-get :storage-object {:id (:media-id row1)} {::db/remove-deleted? false})]
        (t/is (some? (:deleted-at row))))

      ;; Run the storage gc deleted task, it should permanently delete
      ;; all storage objects related to the deleted thumbnails
      (let [result (th/run-task! :storage-gc-deleted {:min-age (dt/duration 0)})]
        (t/is (= 1 (:deleted result))))

      ;; check that storage object is still exists but is marked as deleted
      (let [row (th/db-get :storage-object {:id (:media-id row1)} {::db/remove-deleted? false})]
        (t/is (nil? row)))

      (t/is (some? (sto/get-object storage (:media-id row2))))


      )))


(t/deftest upsert-file-thumbnail
  (let [storage (::sto/storage th/*system*)
        profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false
                                    :revn 3})

        data1   {::th/type :upsert-file-thumbnail
                 ::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :props {}
                 :revn 1
                 :data "data:base64,1234123124"}

        data2   {::th/type :create-file-thumbnail
                 ::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :props {}
                 :revn 2
                 :media {:filename "sample.jpg"
                         :size 7923
                         :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                         :mtype "image/jpeg"}}

        data3   {::th/type :create-file-thumbnail
                 ::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :props {}
                 :revn 3
                 :media {:filename "sample.jpg"
                         :size 312043
                         :path (th/tempfile "backend_tests/test_files/sample.jpg")
                         :mtype "image/jpeg"}}]

    (let [out (th/command! data1)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/command! data2)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/command! data3)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [[row1 row2 row3 :as rows] (th/db-query :file-thumbnail
                                                 {:file-id (:id file)}
                                                 {:order-by [[:created-at :asc]]})]
      (t/is (= 3 (count rows)))

      (t/is (= (:file-id data1) (:file-id row1)))
      (t/is (= (:revn data1) (:revn row1)))
      (t/is (nil? (:media-id row1)))

      (t/is (= (:file-id data2) (:file-id row2)))
      (t/is (= (:revn data2) (:revn row2)))
      (t/is (uuid? (:media-id row2)))

      (t/is (= (:file-id data3) (:file-id row3)))
      (t/is (= (:revn data3) (:revn row3)))
      (t/is (uuid? (:media-id row3)))

      (let [sobject (sto/get-object storage (:media-id row2))
            mobject (meta sobject)]
        (t/is (= "blake2b:05870e3f8ee885841ee3799924d80805179ab57e6fde84a605d1068fd3138de9" (:hash mobject)))
        (t/is (= "file-thumbnail" (:bucket mobject)))
        (t/is (= "image/jpeg" (:content-type mobject)))
        (t/is (= 7923 (:size sobject))))

      (let [sobject (sto/get-object storage (:media-id row3))
            mobject (meta sobject)]
        (t/is (= "blake2b:4fdb63b8f3ffc81256ea79f13e53f366723b188554b5afed91b20897c14a1a8e" (:hash mobject)))
        (t/is (= "file-thumbnail" (:bucket mobject)))
        (t/is (= "image/jpeg" (:content-type mobject)))
        (t/is (= 312043 (:size sobject))))

      ;; Run the File GC task that should remove unused file object
      ;; thumbnails
      (let [result (th/run-task! :file-gc {:min-age (dt/duration 0)})]
        (t/is (= 1 (:processed result))))

      ;; check if row2 related thumbnail row still exists
      (let [[row :as rows] (th/db-query :file-thumbnail
                                        {:file-id (:id file)}
                                        {:order-by [[:created-at :asc]]})]
        (t/is (= 1 (count rows)))
        (t/is (= (:file-id data2) (:file-id row)))
        (t/is (= (:object-id data2) (:object-id row)))
        (t/is (uuid? (:media-id row2))))

      ;; Check if storage objects still exists after file-gc
      (t/is (nil? (sto/get-object storage (:media-id row1))))
      (t/is (nil? (sto/get-object storage (:media-id row2))))
      (t/is (some? (sto/get-object storage (:media-id row3))))

      (let [row (th/db-get :storage-object {:id (:media-id row2)} {::db/remove-deleted? false})]
        (t/is (some? (:deleted-at row))))

      ;; Run the storage gc deleted task, it should permanently delete
      ;; all storage objects related to the deleted thumbnails
      (let [result (th/run-task! :storage-gc-deleted {:min-age (dt/duration 0)})]
        (t/is (= 1 (:deleted result))))

      ;; check that storage object is still exists but is marked as deleted
      (let [row (th/db-get :storage-object {:id (:media-id row1)} {::db/remove-deleted? false})]
        (t/is (nil? row)))

      (t/is (some? (sto/get-object storage (:media-id row3)))))


    ))

(t/deftest get-file-object-thumbnail
  (let [storage (::sto/storage th/*system*)
        profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        data1   {::th/type :upsert-file-object-thumbnail
                 ::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :object-id "test-key-1"
                 :data "data:base64,1234123124"}

        data2   {::th/type :create-file-object-thumbnail
                 ::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :object-id "test-key-2"
                 :media {:filename "sample.jpg"
                         :size 7923
                         :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                         :mtype "image/jpeg"}}]

    (let [out (th/command! data1)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/command! data2)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [[row1 row2 :as rows] (th/db-query :file-object-thumbnail
                                            {:file-id (:id file)}
                                            {:order-by [[:created-at :asc]]})]
      (t/is (= 2 (count rows)))

      (t/is (= (:file-id data1) (:file-id row1)))
      (t/is (= (:object-id data1) (:object-id row1)))
      (t/is (nil? (:media-id row1)))
      (t/is (string? (:data row1)))

      (t/is (= (:file-id data2) (:file-id row2)))
      (t/is (= (:object-id data2) (:object-id row2)))
      (t/is (uuid? (:media-id row2))))


    (let [params {::th/type :get-file-object-thumbnails
                  ::rpc/profile-id (:id profile)
                  :file-id (:id file)}
          out    (th/command! params)]

      (let [result (:result out)]
        (t/is (contains? result "test-key-1"))
        (t/is (contains? result "test-key-2"))))))



