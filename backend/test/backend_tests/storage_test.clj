;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.storage-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.storage.fs :as-alias sto.fs]
   [app.storage.impl :as impl]
   [app.storage.s3 :as-alias sto.s3]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]]
   [promesa.core :as p])
  (:import
   (software.amazon.awssdk.services.s3
    S3AsyncClient)
   (software.amazon.awssdk.services.s3.model
    NoSuchKeyException)
   (software.amazon.awssdk.services.s3.presigner
    S3Presigner)))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(defn configure-storage-backend
  "Given storage map, returns a storage configured with the appropriate
  backend for assets."
  [storage]
  (assoc storage ::sto/backend :fs))

(t/deftest put-and-retrieve-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"
                                          :other "data"})]

    (t/is (sto/object? object))
    (t/is (fs/path? (sto/get-object-path storage object)))

    (t/is (nil? (:expired-at object)))
    (t/is (= :fs (:backend object)))
    (t/is (= "data" (:other (meta object))))
    (t/is (= "text/plain" (:content-type (meta object))))
    (t/is (= "content" (slurp (sto/get-object-data storage object))))
    (t/is (= "content" (slurp (sto/get-object-path storage object))))))

(t/deftest tempfile-objects-are-not-deduplicated
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (-> (sto/content "content")
                    (sto/wrap-with-hash "same-hash"))
        object1 (sto/put-object! storage {::sto/content content
                                          ::sto/deduplicate? true
                                          ::sto/touched-at (ct/in-future {:minutes 10})
                                          :bucket "tempfile"
                                          :content-type "text/plain"})
        object2 (sto/put-object! storage {::sto/content content
                                          ::sto/deduplicate? true
                                          ::sto/touched-at (ct/in-future {:minutes 10})
                                          :bucket "tempfile"
                                          :content-type "text/plain"})]
    (t/is (not= (:id object1) (:id object2)))))

(t/deftest put-and-retrieve-expired-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          ::sto/expired-at (ct/in-future {:hours 1})
                                          :content-type "text/plain"})]

    (t/is (sto/object? object))
    (t/is (ct/inst? (:expired-at object)))
    (t/is (ct/is-after? (:expired-at object) (ct/now)))
    (t/is (nil? (sto/get-object storage (:id object))))))

(t/deftest put-and-delete-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"
                                          :expired-at (ct/in-future {:seconds 1})})]
    (t/is (sto/object? object))
    (t/is (true? (sto/del-object! storage object)))

    ;; retrieving the same object should be not nil because the
    ;; deletion is not immediate
    (t/is (some? (sto/get-object-data storage object)))
    (t/is (some? (sto/get-object-url storage object)))
    (t/is (some? (sto/get-object-path storage object)))

    ;; But you can't retrieve the object again because in database is
    ;; marked as deleted/expired.
    (t/is (nil? (sto/get-object storage (:id object))))))

(t/deftest deleted-gc-task
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content1 (sto/content "content1")
        content2 (sto/content "content2")
        content3 (sto/content "content3")

        object1  (sto/put-object! storage {::sto/content content1
                                           ::sto/expired-at (ct/now)
                                           :content-type "text/plain"})
        object2  (sto/put-object! storage {::sto/content content2
                                           ::sto/expired-at (ct/in-future {:hours 2})
                                           :content-type "text/plain"})
        object3  (sto/put-object! storage {::sto/content content3
                                           ::sto/expired-at (ct/in-future {:hours 1})
                                           :content-type "text/plain"})]

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:minutes 0}))]
      (let [res (th/run-task! :storage-gc-deleted {})]
        (t/is (= 1 (:deleted res)))))

    (let [res (th/db-exec-one! ["select count(*) from storage_object;"])]
      (t/is (= 2 (:count res))))

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:minutes 61}))]
      (let [res (th/run-task! :storage-gc-deleted {})]
        (t/is (= 1 (:deleted res)))))

    (let [res (th/db-exec-one! ["select count(*) from storage_object;"])]
      (t/is (= 1 (:count res))))))

(t/deftest touched-gc-task-1
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        prof    (th/create-profile* 1)
        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:default-team-id prof)})

        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id prof)
                                    :is-shared false})

        mfile   {:filename "sample.jpg"
                 :path (th/tempfile "backend_tests/test_files/sample.jpg")
                 :mtype "image/jpeg"
                 :size 312043}

        params  {::th/type :upload-file-media-object
                 ::rpc/profile-id (:id prof)
                 :file-id (:id file)
                 :is-local true
                 :name "testfile"
                 :content mfile}

        out1    (th/command! params)
        out2    (th/command! params)]

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    (let [result-1 (:result out1)
          result-2 (:result out2)]

      (t/is (uuid? (:id result-1)))
      (t/is (uuid? (:id result-2)))

      (t/is (uuid? (:media-id result-1)))
      (t/is (uuid? (:media-id result-2)))

      (t/is (= (:media-id result-1) (:media-id result-2)))

      (th/db-update! :file-media-object
                     {:deleted-at (ct/now)}
                     {:id (:id result-1)})

      ;; run the objects gc task for permanent deletion
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 1 (:processed res))))

      ;; check that we still have all the storage objects
      (let [res (th/db-exec-one! ["select count(*) from storage_object"])]
        (t/is (= 2 (:count res))))

      ;; now check if the storage objects are touched
      (let [res (th/db-exec-one! ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 2 (:count res))))

      ;; run the touched gc task
      (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                  (th/run-task! :storage-gc-touched {}))]
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; now check that there are no touched objects
      (let [res (th/db-exec-one! ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 0 (:count res))))

      ;; now check that all objects are marked to be deleted
      (let [res (th/db-exec-one! ["select count(*) from storage_object where deleted_at is not null"])]
        (t/is (= 0 (:count res)))))))

(defn- upload-font-chunked!
  "Splits `font-bytes` into a single chunk, creates an upload session,
   uploads the chunk, and returns the session-id UUID."
  [prof ^bytes font-bytes mtype]
  (let [tmp        (fs/create-tempfile :dir "/tmp/penpot" :prefix "test-font-chunk-")
        _          (io/write* tmp font-bytes)
        mfile      {:filename "chunk" :path tmp :mtype mtype :size (alength font-bytes)}
        session-id (-> (th/command! {::th/type :create-upload-session
                                     ::rpc/profile-id (:id prof)
                                     :total-chunks 1})
                       :result :session-id)
        out        (th/command! {::th/type :upload-chunk
                                 ::rpc/profile-id (:id prof)
                                 :session-id session-id
                                 :index 0
                                 :content mfile})]
    (assert (nil? (:error out)))
    session-id))

(t/deftest touched-gc-task-2
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        font-id (uuid/custom 10 1)

        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id team-id})

        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id proj-id
                                    :is-shared false})

        ttfdata (-> (io/resource "backend_tests/test_files/font-1.ttf")
                    (io/read*))

        mfile   {:filename "sample.jpg"
                 :path (th/tempfile "backend_tests/test_files/sample.jpg")
                 :mtype "image/jpeg"
                 :size 312043}

        params1 {::th/type :upload-file-media-object
                 ::rpc/profile-id (:id prof)
                 :file-id (:id file)
                 :is-local true
                 :name "testfile"
                 :content mfile}

        session-id (upload-font-chunked! prof ttfdata "font/ttf")

        params2 {::th/type :create-font-variant
                 ::rpc/profile-id (:id prof)
                 :team-id team-id
                 :font-id font-id
                 :font-family "somefont"
                 :font-weight 400
                 :font-style "normal"
                 :uploads {"font/ttf" session-id}}

        out1     (th/command! params1)
        out2     (th/command! params2)]

    ;; (th/print-result! out)

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    ;; run the touched gc task
    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 5 (:freeze res)))
      (t/is (= 1 (:delete res)))

      (let [result-1 (:result out1)
            result-2 (:result out2)]

        (th/db-update! :team-font-variant
                       {:deleted-at (ct/now)}
                       {:id (:id result-2)})

        ;; run the objects gc task for permanent deletion
        (let [res (th/run-task! :objects-gc {})]
          (t/is (= 1 (:processed res))))

        ;; revert touched state to all storage objects

        (th/db-exec-one! ["update storage_object set touched_at=?" (ct/now)])

        ;; Run the task again
        (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                    (th/run-task! :storage-gc-touched {}))]
          (t/is (= 2 (:freeze res)))
          (t/is (= 4 (:delete res))))

        ;; now check that there are no touched objects
        (let [res (th/db-exec-one! ["select count(*) from storage_object where touched_at is not null"])]
          (t/is (= 0 (:count res))))

        ;; now check that all objects are marked to be deleted
        (let [res (th/db-exec-one! ["select count(*) from storage_object where deleted_at is not null"])]
          (t/is (= 4 (:count res))))))))

(t/deftest touched-gc-task-3
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        prof    (th/create-profile* 1)
        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:default-team-id prof)})
        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id prof)
                                    :is-shared false})
        mfile   {:filename "sample.jpg"
                 :path (th/tempfile "backend_tests/test_files/sample.jpg")
                 :mtype "image/jpeg"
                 :size 312043}

        params  {::th/type :upload-file-media-object
                 ::rpc/profile-id (:id prof)
                 :file-id (:id file)
                 :is-local true
                 :name "testfile"
                 :content mfile}

        out1    (th/command! params)
        out2    (th/command! params)]

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    (let [result-1 (:result out1)
          result-2 (:result out2)]

      ;; now we proceed to manually mark all storage objects touched
      (th/db-exec! ["update storage_object set touched_at=?" (ct/now)])

      ;; run the touched gc task
      (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                  (th/run-task! :storage-gc-touched {}))]
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; check that we have all object in the db
      (let [rows (th/db-exec! ["select * from storage_object"])]
        (t/is (= 2 (count rows)))))

    ;; now we proceed to manually delete all file_media_object
    (th/db-exec! ["update file_media_object set deleted_at = ?" (ct/now)])

    (let [res (th/run-task! :objects-gc {})]
      (t/is (= 2 (:processed res))))

    ;; run the touched gc task
    (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                (th/run-task! :storage-gc-touched {}))]
      (t/is (= 0 (:freeze res)))
      (t/is (= 2 (:delete res))))

    ;; check that we have all no objects
    (let [rows (th/db-exec! ["select * from storage_object where deleted_at is null"])]
      (t/is (= 0 (count rows))))))

(t/deftest tempfile-bucket-test
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content1 (sto/content "content1")
        now      (ct/now)

        object1  (sto/put-object! storage {::sto/content content1
                                           ::sto/touched-at (ct/plus now {:hours 1})
                                           :bucket "tempfile"
                                           :content-type "text/plain"})]

    ;; not eligible while the touched-at is in the future
    (binding [ct/*clock* (ct/fixed-clock now)]
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 0 (:delete res)))))

    ;; still not eligible: touched-at (now+1h) is beyond the threshold
    (binding [ct/*clock* (ct/fixed-clock (ct/plus now {:hours 2}))]
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 0 (:delete res)))))

    ;; eligible: marked for deletion immediately, without any extra delay
    (let [clock (ct/plus now {:hours 3})]
      (binding [ct/*clock* (ct/fixed-clock clock)]
        (let [res (th/run-task! :storage-gc-touched {})]
          (t/is (= 0 (:freeze res)))
          (t/is (= 1 (:delete res)))))

      (let [row (th/db-exec-one! ["select deleted_at from storage_object where id = ?" (:id object1)])]
        (t/is (ct/is-before-or-equal? (:deleted-at row) (ct/plus clock {:seconds 1})))))

    ;; removed on the next deleted gc run
    (binding [ct/*clock* (ct/fixed-clock (ct/plus now {:hours 4}))]
      (let [res (th/run-task! :storage-gc-deleted {})]
        (t/is (= 1 (:deleted res)))))))

(t/deftest touched-gc-task-skip-delay
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content1")
        now     (ct/now)

        object1 (sto/put-object! storage {::sto/content content
                                          ::sto/touched-at now
                                          :bucket "tempfile"
                                          :content-type "text/plain"})]

    ;; too recent: not processed without skip-delay
    (binding [ct/*clock* (ct/fixed-clock now)]
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 0 (:delete res)))))

    ;; processed immediately with skip-delay
    (binding [ct/*clock* (ct/fixed-clock now)]
      (let [res (th/run-task! :storage-gc-touched {:skip-delay true})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 1 (:delete res)))))

    ;; and marked for deletion without any additional delay
    (let [row (th/db-exec-one! ["select deleted_at from storage_object where id = ?" (:id object1)])]
      (t/is (ct/is-before-or-equal? (:deleted-at row) (ct/plus now {:seconds 1}))))))

(t/deftest storage-gc-deleted-immediate
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content1")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"})]

    ;; mark as deleted right now
    (th/db-exec! ["update storage_object set deleted_at = ?" (ct/now)])

    ;; the deleted gc removes it on the next run
    (let [res (th/run-task! :storage-gc-deleted {})]
      (t/is (= 1 (:deleted res))))))

(t/deftest objects-gc-task-skip-delay
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        prof    (th/create-profile* 1)
        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:default-team-id prof)})
        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id prof)
                                    :is-shared false})
        mfile   {:filename "sample.jpg"
                 :path (th/tempfile "backend_tests/test_files/sample.jpg")
                 :mtype "image/jpeg"
                 :size 312043}
        params  {::th/type :upload-file-media-object
                 ::rpc/profile-id (:id prof)
                 :file-id (:id file)
                 :is-local true
                 :name "testfile"
                 :content mfile}
        out1    (th/command! params)
        out2    (th/command! params)]

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    (let [result-1 (:result out1)
          result-2 (:result out2)]

      ;; mark as deleted but in the future (not yet eligible)
      (th/db-update! :file-media-object
                     {:deleted-at (ct/in-future {:days 1})}
                     {:id (:id result-1)})

      ;; without skip-delay the future deleted row is not processed
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 0 (:processed res))))

      ;; with skip-delay it is processed immediately
      (let [res (th/run-task! :objects-gc {:skip-delay true})]
        (t/is (= 1 (:processed res)))))))

(t/deftest put-object-write-failure-leaves-pending-row
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        ;; Point the fs backend at a path that is actually a file so the
        ;; blob write fails.
        blocked (fs/path "/tmp/penpot" (str "blocked-" (uuid/next)))
        _       (spit (str blocked) "x")]
    (try
      (let [broken  (assoc-in storage [::sto/backends :fs ::sto.fs/directory] (str blocked))
            content (sto/content "content")
            ex      (try
                      (sto/put-object! broken {::sto/content content
                                               :content-type "text/plain"})
                      nil
                      (catch Throwable cause cause))]
        (t/is (some? ex))

        ;; the pending row stays behind and is reclaimed asynchronously
        ;; by the :storage-pending-gc task
        (let [rows (th/db-query :storage-object {:status "pending"})]
          (t/is (= 1 (count rows)))

          (th/db-update! :storage-object
                         {:created-at (ct/in-past {:days 2})}
                         {:id (:id (first rows))})

          (let [res (th/run-task! :storage-pending-gc {})]
            (t/is (= 1 (:processed res))))

          (let [row (th/db-exec-one! ["select count(*) from storage_object"])]
            (t/is (= 0 (:count row))))))
      (finally
        (fs/delete blocked)))))

(t/deftest pending-gc-reclaims-unpromoted-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"})
        path    (sto/get-object-path storage object)]

    ;; valid objects are never reclaimed
    (let [res (th/run-task! :storage-pending-gc {})]
      (t/is (= 0 (:processed res))))

    ;; simulate a crash: the object was created but never promoted
    (th/db-update! :storage-object {:status "pending"
                                    :created-at (ct/in-past {:days 2})}
                   {:id (:id object)})

    (t/is (fs/exists? path))

    (let [res (th/run-task! :storage-pending-gc {})]
      (t/is (= 1 (:processed res))))

    ;; both the row and the orphaned blob are removed
    (let [row (th/db-exec-one! ["select count(*) from storage_object where id = ?" (:id object)])]
      (t/is (= 0 (:count row))))
    (t/is (not (fs/exists? path)))))

(t/deftest pending-objects-excluded-from-gc-touched
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          ::sto/touched-at (ct/now)
                                          :content-type "text/plain"})]

    ;; mark it pending and touched in the past
    (th/db-update! :storage-object {:status "pending"
                                    :touched-at (ct/in-past {:days 1})}
                   {:id (:id object)})

    (binding [ct/*clock* (ct/fixed-clock (ct/now))]
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 0 (:delete res)))))

    ;; still present and not marked as deleted
    (let [row (th/db-exec-one! ["select * from storage_object where id = ?" (:id object)])]
      (t/is (some? row))
      (t/is (nil? (:deleted-at row))))))

(t/deftest pending-objects-excluded-from-get
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"})]

    (t/is (some? (sto/get-object storage (:id object))))

    (th/db-update! :storage-object {:status "pending"} {:id (:id object)})

    (t/is (nil? (sto/get-object storage (:id object))))))

(t/deftest pending-objects-excluded-from-dedup
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (-> (sto/content "content")
                    (sto/wrap-with-hash "same-hash"))
        object1 (sto/put-object! storage {::sto/content content
                                          ::sto/deduplicate? true
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})]

    ;; mark the only matching row as pending
    (th/db-update! :storage-object {:status "pending"} {:id (:id object1)})

    (let [object2 (sto/put-object! storage {::sto/content content
                                            ::sto/deduplicate? true
                                            :bucket "file-media-object"
                                            :content-type "text/plain"})]
      (t/is (not= (:id object1) (:id object2))))))

(t/deftest dedup-reuses-existing-blob
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (-> (sto/content "content")
                    (sto/wrap-with-hash "same-hash"))
        object1 (sto/put-object! storage {::sto/content content
                                          ::sto/deduplicate? true
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})
        object2 (sto/put-object! storage {::sto/content content
                                          ::sto/deduplicate? true
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})]
    (t/is (= (:id object1) (:id object2)))
    (let [row (th/db-exec-one! ["select count(*) from storage_object"])]
      (t/is (= 1 (:count row))))))

(t/deftest dedup-repairs-stale-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (-> (sto/content "content")
                    (sto/wrap-with-hash "same-hash"))
        object1 (sto/put-object! storage {::sto/content content
                                          ::sto/deduplicate? true
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})]

    ;; remove the physical blob to simulate a stale/broken object
    (let [path (sto/get-object-path storage object1)]
      (fs/delete path))

    ;; re-uploading identical content repairs the same reference in place
    (let [object2 (sto/put-object! storage {::sto/content content
                                            ::sto/deduplicate? true
                                            :bucket "file-media-object"
                                            :content-type "text/plain"})]
      (t/is (= (:id object1) (:id object2)))

      ;; the row stays live: no tombstone and no extra row
      (let [row (th/db-exec-one! ["select status, deleted_at from storage_object where id = ?" (:id object1)])]
        (t/is (= "valid" (:status row)))
        (t/is (nil? (:deleted-at row))))

      (let [row (th/db-exec-one! ["select count(*) from storage_object"])]
        (t/is (= 1 (:count row))))

      ;; the repaired blob is readable again under the original id
      (t/is (= "content" (slurp (sto/get-object-data storage object2)))))))

(t/deftest gc-deleted-removes-broken-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"})]

    ;; mark as deleted and remove the physical blob
    (th/db-update! :storage-object {:deleted-at (ct/in-past {:minutes 1})}
                   {:id (:id object)})
    (let [path (sto/get-object-path storage object)]
      (fs/delete path))

    ;; the deleted gc removes the row without error even though the blob is
    ;; missing (the physical deletion is best-effort)
    (let [res (th/run-task! :storage-gc-deleted {})]
      (t/is (= 1 (:deleted res))))

    (let [row (th/db-exec-one! ["select count(*) from storage_object where id = ?" (:id object)])]
      (t/is (= 0 (:count row))))))

(t/deftest pending-objects-excluded-from-gc-deleted
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"})]
    ;; mark as pending + deleted in the past
    (th/db-update! :storage-object {:status "pending"
                                    :deleted-at (ct/in-past {:minutes 1})}
                   {:id (:id object)})
    ;; gc-deleted skips it because status != 'valid'
    (let [res (th/run-task! :storage-gc-deleted {})]
      (t/is (= 0 (:deleted res))))
    ;; row still exists (with deleted_at set — we set it above)
    (let [row (th/db-exec-one! ["select count(*) from storage_object where id = ?"
                                (:id object)])]
      (t/is (= 1 (:count row))))))

(t/deftest gc-deleted-gives-up-after-max-attempts
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"})]

    (th/db-update! :storage-object {:deleted-at (ct/in-past {:minutes 1})
                                    :deletion_attempts 6}
                   {:id (:id object)})

    (with-mocks [_mock {:target 'app.storage.impl/del-objects-in-bulk
                        :return (fn [_ ids] (set ids))}]
      (let [res (th/run-task! :storage-gc-deleted {})]
        (t/is (= 0 (:deleted res)))))

    (let [row (th/db-exec-one! ["select count(*) from storage_object where id = ?" (:id object)])]
      (t/is (= 0 (:count row))))))

(t/deftest dedup-reuses-existing-blob-with-touch
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (-> (sto/content "content")
                    (sto/wrap-with-hash "same-hash"))
        t0      (ct/now)
        params  {::sto/deduplicate? true
                 ::sto/touch true
                 :bucket "file-media-object"
                 :content-type "text/plain"}
        object1 (binding [ct/*clock* (ct/fixed-clock t0)]
                  (sto/put-object! storage (assoc params ::sto/content content)))]

    ;; a touched hit reuses the object and updates its touched_at
    (let [object2 (binding [ct/*clock* (ct/fixed-clock (ct/plus t0 {:hours 1}))]
                    (sto/put-object! storage (assoc params ::sto/content content)))]
      (t/is (= (:id object1) (:id object2)))

      (let [row (th/db-exec-one! ["select touched_at from storage_object where id = ?" (:id object1)])]
        (t/is (ct/is-after? (:touched-at row) t0))))

    ;; with the blob removed, the touched hit repairs the stale row in
    ;; place: the same id is kept, the row is not deleted and touched_at
    ;; is left untouched (the touch flag only applies to healthy hits)
    (let [path (sto/get-object-path storage object1)]
      (fs/delete path))

    (let [object3 (binding [ct/*clock* (ct/fixed-clock (ct/plus t0 {:hours 2}))]
                    (sto/put-object! storage (assoc params ::sto/content content)))]
      (t/is (= (:id object1) (:id object3)))

      (let [row (th/db-exec-one! ["select deleted_at, touched_at from storage_object where id = ?" (:id object1)])]
        (t/is (nil? (:deleted-at row)))
        ;; the touch flag does not apply to repairs: touched_at was last
        ;; set by the healthy hit and is not bumped by the repair
        (t/is (ct/is-before? (:touched-at row) (ct/plus t0 {:hours 2})))))))

(t/deftest put-object-repair-failure-leaves-row-intact
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (-> (sto/content "content")
                    (sto/wrap-with-hash "same-hash"))
        object  (sto/put-object! storage {::sto/content content
                                          ::sto/deduplicate? true
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})
        path    (sto/get-object-path storage object)

        ;; Point the fs backend at a path that is actually a file so the
        ;; blob write fails.
        blocked (fs/path "/tmp/penpot" (str "blocked-" (uuid/next)))
        _       (spit (str blocked) "x")]
    (try
      ;; remove the physical blob to simulate a stale/broken object
      (fs/delete path)

      (let [broken (assoc-in storage [::sto/backends :fs ::sto.fs/directory] (str blocked))
            ex     (try
                     (sto/put-object! broken {::sto/content content
                                              ::sto/deduplicate? true
                                              :bucket "file-media-object"
                                              :content-type "text/plain"})
                     nil
                     (catch Throwable cause cause))]
        (t/is (some? ex))

        ;; the failed repair leaves the original row exactly as it was:
        ;; live and valid, so a later upload can retry the healing
        (let [row (th/db-exec-one! ["select status, deleted_at from storage_object where id = ?" (:id object)])]
          (t/is (= "valid" (:status row)))
          (t/is (nil? (:deleted-at row))))

        (let [row (th/db-exec-one! ["select count(*) from storage_object"])]
          (t/is (= 1 (:count row)))))
      (finally
        (fs/delete blocked)))))

(t/deftest upload-chunks-exclude-pending
  (let [prof       (th/create-profile* 1)
        _          (th/create-project* 1 {:profile-id (:id prof)
                                          :team-id (:default-team-id prof)})
        file       (th/create-file* 1 {:profile-id (:id prof)
                                       :project-id (:default-project-id prof)
                                       :is-shared false})
        mfile      {:filename "chunk"
                    :path (th/tempfile "backend_tests/test_files/sample.jpg")
                    :mtype "image/jpeg"
                    :size 312043}
        session-id (-> (th/command! {::th/type :create-upload-session
                                     ::rpc/profile-id (:id prof)
                                     :total-chunks 1})
                       :result :session-id)
        out        (th/command! {::th/type :upload-chunk
                                 ::rpc/profile-id (:id prof)
                                 :session-id session-id
                                 :index 0
                                 :content mfile})]

    (t/is (nil? (:error out)))

    ;; mark all the chunks of this session as pending (simulates rows that
    ;; were never promoted)
    (th/db-exec! ["update storage_object set status = 'pending' where (metadata->>'~:upload-id') = ?"
                  (str session-id)])

    ;; assembling fails because no chunk is visible anymore
    (let [assemble-out (th/command! {::th/type :assemble-file-media-object
                                     ::rpc/profile-id (:id prof)
                                     :session-id session-id
                                     :file-id (:id file)
                                     :is-local true
                                     :name "assembled-image"
                                     :mtype "image/jpeg"})]
      (t/is (some? (:error assemble-out))))))

(defn- fake-s3-backend
  []
  {::sto/type         :s3
   ::sto.s3/client    (reify S3AsyncClient)
   ::sto.s3/presigner (reify S3Presigner)})

(t/deftest s3-exists-object-returns-true-on-found
  (with-mocks [mock {:target 'app.storage.s3/head-object
                     :return (p/resolved {})}]
    (t/is (true? (impl/exists-object? (fake-s3-backend) {:id (uuid/next)})))
    (t/is (= 1 (:call-count @mock)))))

(t/deftest s3-exists-object-returns-false-on-missing-key
  (with-mocks [mock {:target 'app.storage.s3/head-object
                     :return (p/rejected (-> (NoSuchKeyException/builder)
                                             (.message "no key")
                                             (.build)))}]
    (t/is (false? (impl/exists-object? (fake-s3-backend) {:id (uuid/next)})))
    ;; a missing key is a definitive answer: no retries
    (t/is (= 1 (:call-count @mock)))))

(t/deftest s3-exists-object-retries-transient-errors
  (let [calls (atom 0)]
    (with-mocks [_mock {:target 'app.storage.s3/head-object
                        :return (fn [& _]
                                  (swap! calls inc)
                                  (if (< @calls 3)
                                    (p/rejected (RuntimeException. "boom"))
                                    (p/resolved {})))}]
      (t/is (true? (impl/exists-object? (fake-s3-backend) {:id (uuid/next)})))
      (t/is (= 3 @calls)))))

(t/deftest s3-exists-object-throws-after-retries-exhausted
  (with-mocks [mock {:target 'app.storage.s3/head-object
                     :return (p/rejected (RuntimeException. "boom"))}]
    ;; p/await returns the rejection wrapped in an ExecutionException
    (let [ex (try
               (impl/exists-object? (fake-s3-backend) {:id (uuid/next)})
               nil
               (catch Throwable cause cause))]
      (t/is (some? ex))
      (t/is (= "boom" (ex-message (ex-cause ex)))))
    ;; one initial attempt plus max-retries
    (t/is (= 4 (:call-count @mock)))))
