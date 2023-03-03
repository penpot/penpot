;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.storage-test
  (:require
   [backend-tests.helpers :as th]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.storage :as sto]
   [app.util.time :as dt]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(defn configure-storage-backend
  "Given storage map, returns a storage configured with the appropriate
  backend for assets."
  ([storage]
   (assoc storage ::sto/backend :assets-fs))
  ([storage conn]
   (-> storage
       (assoc ::db/pool-or-conn conn)
       (assoc ::sto/backend :assets-fs))))

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
    (t/is (= :assets-fs (:backend object)))
    (t/is (= "data" (:other (meta object))))
    (t/is (= "text/plain" (:content-type (meta object))))
    (t/is (= "content" (slurp (sto/get-object-data storage object))))
    (t/is (= "content" (slurp (sto/get-object-path storage object))))
    ))

(t/deftest put-and-retrieve-expired-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content (sto/content "content")
        object  (sto/put-object! storage {::sto/content content
                                          ::sto/expired-at (dt/in-future {:seconds 1})
                                          :content-type "text/plain"
                                          })]

    (t/is (sto/object? object))
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
    (t/is (nil? (sto/get-object storage (:id object))))
    ))

(t/deftest test-deleted-gc-task
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        content1 (sto/content "content1")
        content2 (sto/content "content2")
        object1  (sto/put-object! storage {::sto/content content1
                                           ::sto/expired-at (dt/now)
                                           :content-type "text/plain"
                                           })
        object2  (sto/put-object! storage {::sto/content content2
                                           ::sto/expired-at (dt/in-past {:hours 2})
                                           :content-type "text/plain"
                                           })]

    (th/sleep 200)

    (let [task (:app.storage/gc-deleted-task th/*system*)
          res  (task {})]
      (t/is (= 1 (:deleted res))))

    (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object;"])]
      (t/is (= 1 (:count res))))))

(t/deftest test-touched-gc-task-1
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

      (t/is (= (:media-id result-1) (:media-id result-2)))

      ;; now we proceed to manually delete one file-media-object
      (db/exec-one! th/*pool* ["delete from file_media_object where id = ?" (:id result-1)])

      ;; check that we still have all the storage objects
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object"])]
        (t/is (= 2 (:count res))))

      ;; now check if the storage objects are touched
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 2 (:count res))))

      ;; run the touched gc task
      (let [task (:app.storage/gc-touched-task th/*system*)
            res  (task {})]
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; now check that there are no touched objects
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where touched_at is not null"])]
        (t/is (= 0 (:count res))))

      ;; now check that all objects are marked to be deleted
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where deleted_at is not null"])]
        (t/is (= 0 (:count res))))
      )))


(t/deftest test-touched-gc-task-2
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
                    io/input-stream
                    io/read-as-bytes)

        mfile   {:filename "sample.jpg"
                 :path (th/tempfile "backend_tests/test_files/sample.jpg")
                 :mtype "image/jpeg"
                 :size 312043}

        params1 {::th/type :upload-file-media-object
                 :profile-id (:id prof)
                 :file-id (:id file)
                 :is-local true
                 :name "testfile"
                 :content mfile}

        params2 {::th/type :create-font-variant
                 :profile-id (:id prof)
                 :team-id team-id
                 :font-id font-id
                 :font-family "somefont"
                 :font-weight 400
                 :font-style "normal"
                 :data {"font/ttf" ttfdata}}

        out1     (th/mutation! params1)
        out2     (th/mutation! params2)]

    ;; (th/print-result! out)

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))

    ;; run the touched gc task
    (let [task (:app.storage/gc-touched-task th/*system*)
          res  (task {})]
      (t/is (= 5 (:freeze res)))
      (t/is (= 0 (:delete res)))

      (let [result-1 (:result out1)
            result-2 (:result out2)]

        ;; now we proceed to manually delete one team-font-variant
        (db/exec-one! th/*pool* ["delete from team_font_variant where id = ?" (:id result-2)])

        ;; revert touched state to all storage objects
        (db/exec-one! th/*pool* ["update storage_object set touched_at=now()"])

        ;; Run the task again
        (let [res  (task {})]
          (t/is (= 2 (:freeze res)))
          (t/is (= 3 (:delete res))))

        ;; now check that there are no touched objects
        (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where touched_at is not null"])]
          (t/is (= 0 (:count res))))

        ;; now check that all objects are marked to be deleted
        (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where deleted_at is not null"])]
          (t/is (= 3 (:count res))))))))

(t/deftest test-touched-gc-task-3
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
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; check that we have all object in the db
      (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where deleted_at is null"])]
        (t/is (= 2 (:count res)))))

    ;; now we proceed to manually delete all team_font_variant
    (db/exec-one! th/*pool* ["delete from file_media_object"])

    ;; run the touched gc task
    (let [task (:app.storage/gc-touched-task th/*system*)
          res  (task {})]
      (t/is (= 0 (:freeze res)))
      (t/is (= 2 (:delete res))))

    ;; check that we have all no objects
    (let [res (db/exec-one! th/*pool* ["select count(*) from storage_object where deleted_at is null"])]
      (t/is (= 0 (:count res))))))

