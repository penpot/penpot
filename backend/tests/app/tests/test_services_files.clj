;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-services-files
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [app.tests.helpers :as th]
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
      (let [data {::th/type :files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= file-id (get-in result [0 :id])))
          (t/is (= "new name" (get-in result [0 :name])))
          (t/is (= 1 (count (get-in result [0 :data :pages])))))))

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
      (let [data {::th/type :files
                  :project-id proj-id
                  :profile-id (:id prof)}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))

(defn- create-file-media-object
  [{:keys [profile-id file-id]}]
  (let [mfile  {:filename "sample.jpg"
                :tempfile (th/tempfile "app/tests/_files/sample.jpg")
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

(defn- update-file
  [{:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                :id file-id
                :session-id (uuid/random)
                :profile-id profile-id
                :revn revn
                :changes changes}
        out    (th/mutation! params)]
    (t/is (nil? (:error out)))
    (:result out)))

(t/deftest file-media-gc-task
  (let [task    (:app.tasks.file-media-gc/handler th/*system*)
        storage (:app.storage/storage th/*system*)

        prof    (th/create-profile* 1)
        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:default-team-id prof)})
        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id prof)
                                    :is-shared false})

        fmo1    (create-file-media-object {:profile-id (:id prof)
                                           :file-id (:id file)})
        fmo2    (create-file-media-object {:profile-id (:id prof)
                                           :file-id (:id file)})
        shid    (uuid/random)

        ures    (update-file
                 {:file-id (:id file)
                  :profile-id (:id prof)
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

    ;; run the task inmediatelly
    (let [res (task {})]
      (t/is (= 0 (:processed res))))

    ;; make the file ellegible for GC waiting 300ms
    (th/sleep 300)

    ;; run the task again
    (let [res (task {})]
      (t/is (= 1 (:processed res))))

    ;; Retrieve file and check trimmed attribute
    (let [row (db/exec-one! th/*pool* ["select * from file where id = ?" (:id file)])]
      (t/is (:has-media-trimmed row)))

    ;; check file media objects
    (let [fmos (db/exec! th/*pool* ["select * from file_media_object where file_id = ?" (:id file)])]
      (t/is (= 1 (count fmos))))

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

    ))
