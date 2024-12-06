;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.storage-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.util.time :as dt]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]]))

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

(t/deftest put-and-retrieve-expired-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          ::sto/expired-at (dt/in-future {:seconds 1})
                                          :content-type "text/plain"})]

    (t/is (sto/object? object))
    (t/is (dt/instant? (:expired-at object)))
    (t/is (dt/is-after? (:expired-at object) (dt/now)))
    (t/is (= object (sto/get-object storage (:id object))))

    (th/sleep 1000)
    (t/is (nil? (sto/get-object storage (:id object))))
    (t/is (nil? (sto/get-object-data storage object)))
    (t/is (nil? (sto/get-object-url storage object)))
    (t/is (nil? (sto/get-object-path storage object)))))

(t/deftest put-and-delete-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          :content-type "text/plain"
                                          :expired-at (dt/in-future {:seconds 1})})]
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
                                           ::sto/expired-at (dt/now)
                                           :content-type "text/plain"})
        object2  (sto/put-object! storage {::sto/content content2
                                           ::sto/expired-at (dt/in-past {:hours 2})
                                           :content-type "text/plain"})
        object3  (sto/put-object! storage {::sto/content content3
                                           ::sto/expired-at (dt/in-past {:hours 1})
                                           :content-type "text/plain"})]


    (th/sleep 200)

    (let [res (th/run-task! :storage-gc-deleted {})]
      (t/is (= 1 (:deleted res))))

    (let [res (th/db-exec-one! ["select count(*) from storage_object;"])]
      (t/is (= 2 (:count res))))))

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
                     {:deleted-at (dt/now)}
                     {:id (:id result-1)})

      ;; run the objects gc task for permanent deletion
      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; check that we still have all the storage objects
      (let [res (th/db-exec-one! ["select count(*) from storage_object"])]
        (t/is (= 2 (:count res))))

      ;; now check if the storage objects are touched
      (let [res (th/db-exec-one! ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 2 (:count res))))

      ;; run the touched gc task
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; now check that there are no touched objects
      (let [res (th/db-exec-one! ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 0 (:count res))))

      ;; now check that all objects are marked to be deleted
      (let [res (th/db-exec-one! ["select count(*) from storage_object where deleted_at is not null"])]
        (t/is (= 0 (:count res)))))))


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

        params2 {::th/type :create-font-variant
                 ::rpc/profile-id (:id prof)
                 :team-id team-id
                 :font-id font-id
                 :font-family "somefont"
                 :font-weight 400
                 :font-style "normal"
                 :data {"font/ttf" ttfdata}}

        out1     (th/command! params1)
        out2     (th/command! params2)]

    ;; (th/print-result! out)

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    ;; run the touched gc task
    (let [res (th/run-task! :storage-gc-touched {})]
      (t/is (= 5 (:freeze res)))
      (t/is (= 0 (:delete res)))

      (let [result-1 (:result out1)
            result-2 (:result out2)]

        (th/db-update! :team-font-variant
                       {:deleted-at (dt/now)}
                       {:id (:id result-2)})

        ;; run the objects gc task for permanent deletion
        (let [res (th/run-task! :objects-gc {:min-age 0})]
          (t/is (= 1 (:processed res))))

        ;; revert touched state to all storage objects
        (th/db-exec-one! ["update storage_object set touched_at=now()"])

        ;; Run the task again
        (let [res (th/run-task! :storage-gc-touched {})]
          (t/is (= 2 (:freeze res)))
          (t/is (= 3 (:delete res))))

        ;; now check that there are no touched objects
        (let [res (th/db-exec-one! ["select count(*) from storage_object where touched_at is not null"])]
          (t/is (= 0 (:count res))))

        ;; now check that all objects are marked to be deleted
        (let [res (th/db-exec-one! ["select count(*) from storage_object where deleted_at is not null"])]
          (t/is (= 3 (:count res))))))))

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
      (th/db-exec! ["update storage_object set touched_at=now()"])

      ;; run the touched gc task
      (let [res (th/run-task! "storage-gc-touched" {:min-age 0})]
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; check that we have all object in the db
      (let [rows (th/db-exec! ["select * from storage_object"])]
        (t/is (= 2 (count rows)))))

    ;; now we proceed to manually delete all file_media_object
    (th/db-exec! ["update file_media_object set deleted_at = now()"])

    (let [res (th/run-task! "objects-gc" {:min-age 0})]
      (t/is (= 2 (:processed res))))

    ;; run the touched gc task
    (let [res (th/run-task! "storage-gc-touched" {:min-age 0})]
      (t/is (= 0 (:freeze res)))
      (t/is (= 2 (:delete res))))

    ;; check that we have all no objects
    (let [rows (th/db-exec! ["select * from storage_object where deleted_at is null"])]
      (t/is (= 0 (count rows))))))
