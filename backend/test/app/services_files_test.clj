;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.services-files-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [app.test-helpers :as th]
   [app.util.time :as dt]
   [clojure.test :as t]
   [datoteka.core :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest files-crud
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        file-id (uuid/next)
        page-id (uuid/next)]

    (t/testing "create file"
      (let [data {::th/type :create-file
                  :profile-id (:id prof)
                  :project-id proj-id
                  :id file-id
                  :name "foobar"
                  :is-shared false}
            out (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:name data) (:name result)))
          (t/is (= proj-id (:project-id result))))))

    (t/testing "rename file"
      (let [data {::th/type :rename-file
                  :id file-id
                  :name "new name"
                  :profile-id (:id prof)}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "query files"
      (let [data {::th/type :project-files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= file-id (get-in result [0 :id])))
          (t/is (= "new name" (get-in result [0 :name]))))))

    (t/testing "query single file without users"
      (let [data {::th/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= file-id (:id result)))
          (t/is (= "new name" (:name result)))
          (t/is (= 1 (count (get-in result [:data :pages]))))
          (t/is (nil? (:users result))))))

    (t/testing "delete file"
      (let [data {::th/type :delete-file
                  :id file-id
                  :profile-id (:id prof)}
            out (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query single file after delete"
      (let [data {::th/type :file
                  :profile-id (:id prof)
                  :id file-id}
            out (th/query! data)]

        ;; (th/print-result! out)

        (let [error      (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found)))))

    (t/testing "query list files after delete"
      (let [data {::th/type :project-files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))

(t/deftest file-media-gc-task
  (letfn [(create-file-media-object [{:keys [profile-id file-id]}]
            (let [mfile  {:filename "sample.jpg"
                          :tempfile (th/tempfile "app/test_files/sample.jpg")
                          :content-type "image/jpeg"
                          :size 312043}
                  params {::th/type :upload-file-media-object
                          :profile-id profile-id
                          :file-id file-id
                          :is-local true
                          :name "testfile"
                          :content mfile}
                  out    (th/mutation! params)]
              (t/is (nil? (:error out)))
              (:result out)))

          (update-file [{:keys [profile-id file-id changes revn] :or {revn 0}}]
            (let [params {::th/type :update-file
                          :id file-id
                          :session-id (uuid/random)
                          :profile-id profile-id
                          :revn revn
                          :changes changes}
                  out    (th/mutation! params)]
              (t/is (nil? (:error out)))
              (:result out)))]

    (let [storage (:app.storage/storage th/*system*)

          profile (th/create-profile* 1)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})

          fmo1    (create-file-media-object {:profile-id (:id profile)
                                             :file-id (:id file)})
          fmo2    (create-file-media-object {:profile-id (:id profile)
                                             :file-id (:id file)})
          shid    (uuid/random)

          ures    (update-file
                   {:file-id (:id file)
                    :profile-id (:id profile)
                    :revn 0
                    :changes
                    [{:type :add-obj
                      :page-id (first (get-in file [:data :pages]))
                      :id shid
                      :parent-id uuid/zero
                      :frame-id uuid/zero
                      :obj {:id shid
                            :name "image"
                            :frame-id uuid/zero
                            :parent-id uuid/zero
                            :type :image
                            :metadata {:id (:id fmo1)}}}]})]

      ;; run the task immediately
      (let [task  (:app.tasks.file-media-gc/handler th/*system*)
            res   (task {})]
        (t/is (= 0 (:processed res))))

      ;; make the file eligible for GC waiting 300ms (configured
      ;; timeout for testing)
      (th/sleep 300)

      ;; run the task again
      (let [task  (:app.tasks.file-media-gc/handler th/*system*)
            res   (task {})]
        (t/is (= 1 (:processed res))))

      ;; retrieve file and check trimmed attribute
      (let [row (db/exec-one! th/*pool* ["select * from file where id = ?" (:id file)])]
        (t/is (true? (:has-media-trimmed row))))

      ;; check file media objects
      (let [rows (db/exec! th/*pool* ["select * from file_media_object where file_id = ?" (:id file)])]
        (t/is (= 1 (count rows))))

      ;; The underlying storage objects are still available.
      (t/is (some? (sto/get-object storage (:media-id fmo2))))
      (t/is (some? (sto/get-object storage (:thumbnail-id fmo2))))
      (t/is (some? (sto/get-object storage (:media-id fmo1))))
      (t/is (some? (sto/get-object storage (:thumbnail-id fmo1))))

      ;; but if we pass the touched gc task two of them should disappear
      (let [task (:app.storage/gc-touched-task th/*system*)
            res  (task {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 2 (:delete res)))

        (t/is (nil? (sto/get-object storage (:media-id fmo2))))
        (t/is (nil? (sto/get-object storage (:thumbnail-id fmo2))))
        (t/is (some? (sto/get-object storage (:media-id fmo1))))
        (t/is (some? (sto/get-object storage (:thumbnail-id fmo1)))))

      )))

(t/deftest permissions-checks-creating-file
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)

        data     {::th/type :create-file
                  :profile-id (:id profile2)
                  :project-id (:default-project-id profile1)
                  :name "foobar"
                  :is-shared false}
        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-rename-file
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)

        file     (th/create-file* 1 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)})
        data     {::th/type :rename-file
                  :id (:id file)
                  :profile-id (:id profile2)
                  :name "foobar"}
        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-delete-file
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)

        file     (th/create-file* 1 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)})
        data     {::th/type :delete-file
                  :profile-id (:id profile2)
                  :id (:id file)}
        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-set-file-shared
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)
        file     (th/create-file* 1 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)})
        data     {::th/type :set-file-shared
                  :profile-id (:id profile2)
                  :id (:id file)
                  :is-shared true}
        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-link-to-library-1
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)
        file1    (th/create-file* 1 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)
                                     :is-shared true})
        file2    (th/create-file* 2 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)})

        data     {::th/type :link-file-to-library
                  :profile-id (:id profile2)
                  :file-id (:id file2)
                  :library-id (:id file1)}

        out      (th/mutation! data)
        error    (:error out)]

      ;; (th/print-result! out)
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-link-to-library-2
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)
        file1    (th/create-file* 1 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)
                                     :is-shared true})

        file2    (th/create-file* 2 {:project-id (:default-project-id profile2)
                                     :profile-id (:id profile2)})

        data     {::th/type :link-file-to-library
                  :profile-id (:id profile2)
                  :file-id (:id file2)
                  :library-id (:id file1)}

        out      (th/mutation! data)
        error    (:error out)]

      ;; (th/print-result! out)
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :not-found))))

(t/deftest deletion-test
  (let [task     (:app.tasks.objects-gc/handler th/*system*)
        profile1 (th/create-profile* 1)
        file     (th/create-file* 1 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)})]
    ;; file is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (task {:max-age (dt/duration 0)})]
      (t/is (nil? result)))

    ;; query the list of files
    (let [data {::th/type :project-files
                :project-id (:default-project-id profile1)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 1 (count result)))))

    ;; Request file to be deleted
    (let [params {::th/type :delete-file
                  :id (:id file)
                  :profile-id (:id profile1)}
          out    (th/mutation! params)]
      (t/is (nil? (:error out))))

    ;; query the list of files after soft deletion
    (let [data {::th/type :project-files
                :project-id (:default-project-id profile1)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))

    ;; run permanent deletion (should be noop)
    (let [result (task {:max-age (dt/duration {:minutes 1})})]
      (t/is (nil? result)))

    ;; query the list of file libraries of a after hard deletion
    (let [data {::th/type :file-libraries
                :file-id (:id file)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))

    ;; run permanent deletion
    (let [result (task {:max-age (dt/duration 0)})]
      (t/is (nil? result)))

    ;; query the list of file libraries of a after hard deletion
    (let [data {::th/type :file-libraries
                :file-id (:id file)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))
    ))
