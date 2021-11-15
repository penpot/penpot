;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage-test
  (:require
   [app.common.exceptions :as ex]
   [app.db :as db]
   [app.storage :as sto]
   [app.test-helpers :as th]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))


(t/deftest put-and-retrieve-object
  (let [storage (:app.storage/storage th/*system*)
        content (sto/content "content")
        object  (sto/put-object storage {:content content
                                         :content-type "text/plain"
                                         :other "data"})]
    (t/is (sto/storage-object? object))
    (t/is (fs/path? (sto/get-object-path storage object)))
    (t/is (nil? (:expired-at object)))
    (t/is (= :tmp (:backend object)))
    (t/is (= "data" (:other (meta object))))
    (t/is (= "text/plain" (:content-type (meta object))))
    (t/is (= "content" (slurp (sto/get-object-data storage object))))
    (t/is (= "content" (slurp (sto/get-object-path storage object))))
    ))


(t/deftest put-and-retrieve-expired-object
  (let [storage (:app.storage/storage th/*system*)
        content (sto/content "content")
        object  (sto/put-object storage {:content content
                                         :content-type "text/plain"
                                         :expired-at (dt/in-future {:seconds 1})})]
    (t/is (sto/storage-object? object))
    (t/is (dt/instant? (:expired-at object)))
    (t/is (dt/is-after? (:expired-at object) (dt/now)))
    (t/is (= object (sto/get-object storage (:id object))))

    (th/sleep 1000)
    (t/is (nil? (sto/get-object storage (:id object))))
    (t/is (nil? (sto/get-object-data storage object)))
    (t/is (nil? (sto/get-object-url storage object)))
    (t/is (nil? (sto/get-object-path storage object)))
    ))

(t/deftest put-and-delete-object
  (let [storage (:app.storage/storage th/*system*)
        content (sto/content "content")
        object  (sto/put-object storage {:content content
                                         :content-type "text/plain"
                                         :expired-at (dt/in-future {:seconds 1})})]
    (t/is (sto/storage-object? object))
    (t/is (true? (sto/del-object storage object)))

    ;; retrieving the same object should be not nil because the
    ;; deletion is not immediate
    (t/is (some? (sto/get-object-data storage object)))
    (t/is (some? (sto/get-object-url storage object)))
    (t/is (some? (sto/get-object-path storage object)))

    ;; But you can't retrieve the object again because in database is
    ;; marked as deleted/expired.
    (t/is (nil? (sto/get-object storage (:id object))))
    ))

(t/deftest test-deleted-gc-task
  (let [storage (:app.storage/storage th/*system*)
        content (sto/content "content")
        object1 (sto/put-object storage {:content content
                                         :content-type "text/plain"
                                         :expired-at (dt/now)})
        object2 (sto/put-object storage {:content content
                                         :content-type "text/plain"
                                         :expired-at (dt/in-past {:hours 2})})]
    (th/sleep 200)

    (let [task (:app.storage/gc-deleted-task th/*system*)
          res  (task {})]
      (t/is (= 1 (:deleted res))))

    (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object;"])]
      (t/is (= 1 (:count res))))))

(t/deftest test-touched-gc-task
  (let [storage (:app.storage/storage th/*system*)
        prof    (th/create-profile* 1)
        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:default-team-id prof)})
        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id prof)
                                    :is-shared false})
        mfile   {:filename "sample.jpg"
                 :tempfile (th/tempfile "app/test_files/sample.jpg")
                 :content-type "image/jpeg"
                 :size 312043}

        params  {::th/type :upload-file-media-object
                 :profile-id (:id prof)
                 :file-id (:id file)
                 :is-local true
                 :name "testfile"
                 :content mfile}

        out1    (th/mutation! params)
        out2    (th/mutation! params)]

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    (let [result-1 (:result out1)
          result-2 (:result out2)]

      (t/is (uuid? (:id result-1)))
      (t/is (uuid? (:id result-2)))

      (t/is (uuid? (:media-id result-1)))
      (t/is (uuid? (:media-id result-2)))

      ;; now we proceed to manually delete one file-media-object
      (db/exec-one! th/*pool* ["delete from file_media_object where id = ?" (:id result-1)])

      ;; check that we still have all the storage objects
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object"])]
        (t/is (= 4 (:count res))))

      ;; now check if the storage objects are touched
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 2 (:count res))))

      ;; run the touched gc task
      (let [task (:app.storage/gc-touched-task th/*system*)
            res  (task {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 2 (:delete res))))

      ;; now check that there are no touched objects
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 0 (:count res))))

      ;; now check that all objects are marked to be deleted
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where deleted_at is not null"])]
        (t/is (= 2 (:count res))))
    )))

(t/deftest test-touched-gc-task-without-delete
  (let [storage (:app.storage/storage th/*system*)
        prof    (th/create-profile* 1)
        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:default-team-id prof)})
        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id prof)
                                    :is-shared false})
        mfile   {:filename "sample.jpg"
                 :tempfile (th/tempfile "app/test_files/sample.jpg")
                 :content-type "image/jpeg"
                 :size 312043}

        params  {::th/type :upload-file-media-object
                 :profile-id (:id prof)
                 :file-id (:id file)
                 :is-local true
                 :name "testfile"
                 :content mfile}

        out1    (th/mutation! params)
        out2    (th/mutation! params)]

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    (let [result-1 (:result out1)
          result-2 (:result out2)]

      ;; now we proceed to manually mark all storage objects touched
      (db/exec-one! th/*pool* ["update storage_object set touched_at=now()"])

      ;; run the touched gc task
      (let [task (:app.storage/gc-touched-task th/*system*)
            res  (task {})]
        (t/is (= 4 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; check that we have all object in the db
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where deleted_at is null"])]
        (t/is (= 4 (:count res)))))))


;; Recheck is the mechanism for delete leaked resources on
;; transaction failure.

(t/deftest test-recheck
  (let [storage (:app.storage/storage th/*system*)
        content (sto/content "content")
        object  (sto/put-object storage {:content content
                                         :content-type "text/plain"})]
    ;; Sleep fo 50ms
    (th/sleep 50)

    (let [rows (db/exec! th/*pool* ["select * from storage_pending"])]
      (t/is (= 1 (count rows)))
      (t/is (= (:id object) (:id (first rows)))))

    ;; Artificially make all storage_pending object 1 hour older.
    (db/exec-one! th/*pool* ["update storage_pending set created_at = created_at - '1 hour'::interval"])

    ;; Sleep fo 50ms
    (th/sleep 50)

    ;; Run recheck task
    (let [task (:app.storage/recheck-task th/*system*)
          res  (task {})]
      (t/is (= 1 (:processed res)))
      (t/is (= 0 (:deleted res))))

    ;; After recheck task, storage-pending table should be empty
    (let [rows (db/exec! th/*pool* ["select * from storage_pending"])]
      (t/is (= 0 (count rows))))))

(t/deftest test-recheck-with-rollback
  (let [storage (:app.storage/storage th/*system*)
        content (sto/content "content")]

    ;; check with aborted transaction
    (ex/ignoring
     (db/with-atomic [conn th/*pool*]
       (let [storage (assoc storage :conn conn)] ; make participate storage in the transaction
         (sto/put-object storage {:content content
                                  :content-type "text/plain"})
         (throw (ex-info "expected" {})))))

    ;; let a 200ms window for recheck registration thread
    ;; completion before proceed.
    (th/sleep 200)

    ;; storage_pending table should have the object
    ;; registered independently of the aborted transaction.
    (let [rows (db/exec! th/*pool* ["select * from storage_pending"])]
      (t/is (= 1 (count rows))))

    ;; Artificially make all storage_pending object 1 hour older.
    (db/exec-one! th/*pool* ["update storage_pending set created_at = created_at - '1 hour'::interval"])

    ;; Sleep fo 50ms
    (th/sleep 50)

    ;; Run recheck task
    (let [task (:app.storage/recheck-task th/*system*)
          res  (task {})]
      (t/is (= 1 (:processed res)))
      (t/is (= 1 (:deleted res))))

    ;; After recheck task, storage-pending table should be empty
    (let [rows (db/exec! th/*pool* ["select * from storage_pending"])]
      (t/is (= 0 (count rows))))))
