;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-file-test
  (:require
   [app.common.features :as cfeat]
   [app.common.pprint :as pp]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.util.time :as dt]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

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
                  ::rpc/profile-id (:id prof)
                  :project-id proj-id
                  :id file-id
                  :name "foobar"
                  :is-shared false
                  :components-v2 true}
            out (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:name data) (:name result)))
          (t/is (= proj-id (:project-id result))))))

    (t/testing "rename file"
      (let [data {::th/type :rename-file
                  :id file-id
                  :name "new name"
                  ::rpc/profile-id (:id prof)}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "query files"
      (let [data {::th/type :get-project-files
                  ::rpc/profile-id (:id prof)
                  :project-id proj-id}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= file-id (get-in result [0 :id])))
          (t/is (= "new name" (get-in result [0 :name]))))))

    (t/testing "query single file without users"
      (let [data {::th/type :get-file
                  ::rpc/profile-id (:id prof)
                  :id file-id
                  :components-v2 true}
            out  (th/command! data)]

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
                  ::rpc/profile-id (:id prof)}
            out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query single file after delete"
      (let [data {::th/type :get-file
                  ::rpc/profile-id (:id prof)
                  :id file-id
                  :components-v2 true}
            out (th/command! data)]

        ;; (th/print-result! out)

        (let [error      (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found)))))

    (t/testing "query list files after delete"
      (let [data {::th/type :get-project-files
                  ::rpc/profile-id (:id prof)
                  :project-id proj-id}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))))

(t/deftest file-gc-with-fragments
  (letfn [(update-file! [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
            (let [params {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn revn
                          :features cfeat/supported-features
                          :changes changes}
                  out    (th/command! params)]
              ;; (th/print-result! out)
              (t/is (nil? (:error out)))
              (:result out)))]

    (let [profile (th/create-profile* 1)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})

          page-id  (uuid/random)
          shape-id (uuid/random)]

      ;; Preventive file-gc
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; Check the number of fragments before adding the page
      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)})]
        (t/is (= 2 (count rows))))

      ;; Add page
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :changes
       [{:type :add-page
         :name "test"
         :id page-id}])

      ;; Check the number of fragments before adding the page
      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)})]
        (t/is (= 3 (count rows))))

      ;; The file-gc should mark for remove unused fragments
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; Check the number of fragments
      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)})]
        (t/is (= 5 (count rows))))

      ;; The objects-gc should remove unused fragments
      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 3 (:processed res))))

      ;; Check the number of fragments
      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)})]
        (t/is (= 2 (count rows))))

      ;; Add shape to page that should add a new fragment
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :changes
       [{:type :add-obj
         :page-id page-id
         :id shape-id
         :parent-id uuid/zero
         :frame-id uuid/zero
         :components-v2 true
         :obj (cts/setup-shape
               {:id shape-id
                :name "image"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :rect})}])

      ;; Check the number of fragments
      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)})]
        (t/is (= 3 (count rows))))

      ;; The file-gc should mark for remove unused fragments
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; The objects-gc should remove unused fragments
      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 3 (:processed res))))

      ;; Check the number of fragments; should be 3 because changes
      ;; are also holding pointers to fragments;
      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)
                                                   :deleted-at nil})]
        (t/is (= 2 (count rows))))

      ;; Lets proceed to delete all changes
      (th/db-delete! :file-change {:file-id (:id file)})
      (th/db-update! :file
                     {:has-media-trimmed false}
                     {:id (:id file)})

      ;; The file-gc should remove fragments related to changes
      ;; snapshots previously deleted.
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; Check the number of fragments;
      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)})]
        ;; (pp/pprint rows)
        (t/is (= 4 (count rows)))
        (t/is (= 2 (count (remove (comp some? :deleted-at) rows)))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 2 (:processed res))))

      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)})]
        (t/is (= 2 (count rows)))))))

(t/deftest file-gc-task-with-thumbnails
  (letfn [(add-file-media-object [& {:keys [profile-id file-id]}]
            (let [mfile  {:filename "sample.jpg"
                          :path (th/tempfile "backend_tests/test_files/sample.jpg")
                          :mtype "image/jpeg"
                          :size 312043}
                  params {::th/type :upload-file-media-object
                          ::rpc/profile-id profile-id
                          :file-id file-id
                          :is-local true
                          :name "testfile"
                          :content mfile}
                  out    (th/command! params)]

              ;; (th/print-result! out)
              (t/is (nil? (:error out)))
              (:result out)))

          (update-file! [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
            (let [params {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn revn
                          :features cfeat/supported-features
                          :changes changes}
                  out    (th/command! params)]
              ;; (th/print-result! out)
              (t/is (nil? (:error out)))
              (:result out)))]

    (let [storage (:app.storage/storage th/*system*)

          profile (th/create-profile* 1)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})

          fmo1    (add-file-media-object :profile-id (:id profile) :file-id (:id file))
          fmo2    (add-file-media-object :profile-id (:id profile) :file-id (:id file))
          shid    (uuid/random)

          page-id (first (get-in file [:data :pages]))]


      ;; Update file inserting a new image object
      (update-file!
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
         :obj (cts/setup-shape
               {:id shid
                :name "image"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :image
                :metadata {:id (:id fmo1) :width 100 :height 100 :mtype "image/jpeg"}})}])

      ;; Check that reference storage objects on filemediaobjects
      ;; are the same because of deduplication feature.
      (t/is (= (:media-id fmo1) (:media-id fmo2)))
      (t/is (= (:thumbnail-id fmo1) (:thumbnail-id fmo2)))

      ;; If we launch gc-touched-task, we should have 2 items to
      ;; freeze because of the deduplication (we have uploaded 2 times
      ;; the same files).

      (let [res (th/run-task! :storage-gc-touched {:min-age 0})]
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; run the file-gc task immediately without forced min-age
      (let [res (th/run-task! :file-gc)]
        (t/is (= 0 (:processed res))))

      ;; run the task again
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; retrieve file and check trimmed attribute
      (let [row (th/db-get :file {:id (:id file)})]
        (t/is (true? (:has-media-trimmed row))))

      ;; check file media objects
      (let [rows (th/db-query :file-media-object {:file-id (:id file)})]
        (t/is (= 2 (count rows)))
        (t/is (= 1 (count (remove (comp some? :deleted-at) rows)))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 3 (:processed res))))

      ;; check file media objects
      (let [rows (th/db-query :file-media-object {:file-id (:id file)})]
        (t/is (= 1 (count rows)))
        (t/is (= 1 (count (remove (comp some? :deleted-at) rows)))))

      ;; The underlying storage objects are still available.
      (t/is (some? (sto/get-object storage (:media-id fmo2))))
      (t/is (some? (sto/get-object storage (:thumbnail-id fmo2))))
      (t/is (some? (sto/get-object storage (:media-id fmo1))))
      (t/is (some? (sto/get-object storage (:thumbnail-id fmo1))))

      ;; proceed to remove usage of the file
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :changes [{:type :del-obj
                  :page-id (first (get-in file [:data :pages]))
                  :id shid}])

      ;; Now, we have deleted the usage of pointers to the
      ;; file-media-objects, if we paste file-gc, they should be marked
      ;; as deleted.
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 3 (:processed res))))

      ;; Now that file-gc have deleted the file-media-object usage,
      ;; lets execute the touched-gc task, we should see that two of
      ;; them are marked to be deleted.
      (let [res (th/run-task! :storage-gc-touched {:min-age 0})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 2 (:delete res))))

      ;; Finally, check that some of the objects that are marked as
      ;; deleted we are unable to retrieve them using standard storage
      ;; public api.
      (t/is (nil? (sto/get-object storage (:media-id fmo2))))
      (t/is (nil? (sto/get-object storage (:thumbnail-id fmo2))))
      (t/is (nil? (sto/get-object storage (:media-id fmo1))))
      (t/is (nil? (sto/get-object storage (:thumbnail-id fmo1)))))))

(t/deftest file-gc-image-fills-and-strokes
  (letfn [(add-file-media-object [& {:keys [profile-id file-id]}]
            (let [mfile  {:filename "sample.jpg"
                          :path (th/tempfile "backend_tests/test_files/sample.jpg")
                          :mtype "image/jpeg"
                          :size 312043}
                  params {::th/type :upload-file-media-object
                          ::rpc/profile-id profile-id
                          :file-id file-id
                          :is-local true
                          :name "testfile"
                          :content mfile}
                  out    (th/command! params)]

              ;; (th/print-result! out)
              (t/is (nil? (:error out)))
              (:result out)))

          (update-file! [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
            (let [params {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn revn
                          :components-v2 true
                          :changes changes}
                  out    (th/command! params)]
              ;; (th/print-result! out)
              (t/is (nil? (:error out)))
              (:result out)))]

    (let [storage (:app.storage/storage th/*system*)

          profile (th/create-profile* 1)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})

          fmo1    (add-file-media-object :profile-id (:id profile) :file-id (:id file))
          fmo2    (add-file-media-object :profile-id (:id profile) :file-id (:id file))
          fmo3    (add-file-media-object :profile-id (:id profile) :file-id (:id file))
          fmo4    (add-file-media-object :profile-id (:id profile) :file-id (:id file))
          fmo5    (add-file-media-object :profile-id (:id profile) :file-id (:id file))
          s-shid  (uuid/random)
          t-shid  (uuid/random)

          page-id (first (get-in file [:data :pages]))]


      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)
                                                   :deleted-at nil})]
        (t/is (= (count rows) 1)))

      ;; Update file inserting a new image object
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :changes
       [{:type :add-obj
         :page-id page-id
         :id s-shid
         :parent-id uuid/zero
         :frame-id uuid/zero
         :components-v2 true
         :obj (cts/setup-shape
               {:id s-shid
                :name "image"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :image
                :metadata {:id (:id fmo1) :width 100 :height 100 :mtype "image/jpeg"}
                :fills [{:opacity 1 :fill-image {:id (:id fmo2) :width 100 :height 100 :mtype "image/jpeg"}}]
                :strokes [{:opacity 1 :stroke-image {:id (:id fmo3) :width 100 :height 100 :mtype "image/jpeg"}}]})}
        {:type :add-obj
         :page-id page-id
         :id t-shid
         :parent-id uuid/zero
         :frame-id uuid/zero
         :components-v2 true
         :obj (cts/setup-shape
               {:id t-shid
                :name "text"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :text
                :content {:type "root"
                          :children [{:type "paragraph-set"
                                      :children [{:type "paragraph"
                                                  :children [{:fills [{:fill-opacity 1
                                                                       :fill-image {:id (:id fmo4)
                                                                                    :width 417
                                                                                    :height 354
                                                                                    :mtype "image/png"
                                                                                    :name "text fill image"}}]
                                                              :text "hi"}
                                                             {:fills [{:fill-opacity 1
                                                                       :fill-color "#000000"}]
                                                              :text "bye"}]}]}]}
                :strokes [{:opacity 1 :stroke-image {:id (:id fmo5) :width 100 :height 100 :mtype "image/jpeg"}}]})}])

      ;; run the file-gc task immediately without forced min-age
      (let [res (th/run-task! :file-gc)]
        (t/is (= 0 (:processed res))))

      ;; run the task again
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 2 (:processed res))))

      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)
                                                   :deleted-at nil})]
        (t/is (= (count rows) 1)))

      ;; retrieve file and check trimmed attribute
      (let [row (th/db-get :file {:id (:id file)})]
        (t/is (true? (:has-media-trimmed row))))

      ;; check file media objects
      (let [rows (th/db-exec! ["select * from file_media_object where file_id = ?" (:id file)])]
        (t/is (= 5 (count rows))))

      ;; The underlying storage objects are still available.
      (t/is (some? (sto/get-object storage (:media-id fmo5))))
      (t/is (some? (sto/get-object storage (:media-id fmo4))))
      (t/is (some? (sto/get-object storage (:media-id fmo3))))
      (t/is (some? (sto/get-object storage (:media-id fmo2))))
      (t/is (some? (sto/get-object storage (:media-id fmo1))))

      ;; proceed to remove usage of the file
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :changes [{:type :del-obj
                  :page-id (first (get-in file [:data :pages]))
                  :id s-shid}
                 {:type :del-obj
                  :page-id (first (get-in file [:data :pages]))
                  :id t-shid}])

      ;; Now, we have deleted the usage of pointers to the
      ;; file-media-objects, if we paste file-gc, they should be marked
      ;; as deleted.

      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 7 (:processed res))))

      (let [rows (th/db-query :file-data-fragment {:file-id (:id file)
                                                   :deleted-at nil})]
        (t/is (= (count rows) 1)))

      ;; Now that file-gc have deleted the file-media-object usage,
      ;; lets execute the touched-gc task, we should see that two of
      ;; them are marked to be deleted.
      (let [res (th/run-task! :storage-gc-touched {:min-age 0})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 2 (:delete res))))

      ;; Finally, check that some of the objects that are marked as
      ;; deleted we are unable to retrieve them using standard storage
      ;; public api.
      (t/is (nil? (sto/get-object storage (:media-id fmo5))))
      (t/is (nil? (sto/get-object storage (:media-id fmo4))))
      (t/is (nil? (sto/get-object storage (:media-id fmo3))))
      (t/is (nil? (sto/get-object storage (:media-id fmo2))))
      (t/is (nil? (sto/get-object storage (:media-id fmo1)))))))

(t/deftest file-gc-task-with-object-thumbnails
  (letfn [(insert-file-object-thumbnail! [& {:keys [profile-id file-id page-id frame-id]}]
            (let [object-id (thc/fmt-object-id file-id page-id frame-id "frame")
                  mfile     {:filename "sample.jpg"
                             :path (th/tempfile "backend_tests/test_files/sample.jpg")
                             :mtype "image/jpeg"
                             :size 312043}

                  params    {::th/type :create-file-object-thumbnail
                             ::rpc/profile-id profile-id
                             :file-id file-id
                             :object-id object-id
                             :tag "frame"
                             :media mfile}
                  out       (th/command! params)]

              ;; (th/print-result! out)
              (t/is (nil? (:error out)))
              (:result out)))

          (update-file! [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
            (let [params {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn revn
                          :features cfeat/supported-features
                          :changes changes}
                  out    (th/command! params)]
              ;; (th/print-result! out)
              (t/is (nil? (:error out)))
              (:result out)))]

    (let [storage  (:app.storage/storage th/*system*)
          profile  (th/create-profile* 1)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)
                                       :is-shared false})

          file-id  (get file :id)
          page-id  (first (get-in file [:data :pages]))

          frame-id-1 (uuid/random)
          frame-id-2 (uuid/random)

          fot-1      (insert-file-object-thumbnail! :profile-id (:id profile)
                                                    :file-id file-id
                                                    :page-id page-id
                                                    :frame-id frame-id-1)
          fot-2      (insert-file-object-thumbnail! :profile-id (:id profile)
                                                    :page-id page-id
                                                    :file-id file-id
                                                    :frame-id frame-id-2)]

      ;; Add a two frames

      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :changes
       [{:type :add-obj
         :page-id page-id
         :id frame-id-1
         :parent-id uuid/zero
         :frame-id uuid/zero
         :obj (cts/setup-shape
               {:id frame-id-2
                :name "Board"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :frame})}

        {:type :add-obj
         :page-id page-id
         :id frame-id-2
         :parent-id uuid/zero
         :frame-id uuid/zero
         :obj (cts/setup-shape
               {:id frame-id-2
                :name "Board"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :frame})}])

      ;; Check that reference storage objects are the same because of
      ;; deduplication feature.
      (t/is (= (:media-id fot-1) (:media-id fot-2)))

      ;; If we launch gc-touched-task, we should have 1 item to freeze
      ;; because of the deduplication (we have uploaded 2 times the
      ;; same files).

      (let [res (th/run-task! :storage-gc-touched {:min-age 0})]
        (t/is (= 1 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; run the file-gc task immediately without forced min-age
      (let [res (th/run-task! :file-gc)]
        (t/is (= 0 (:processed res))))

      ;; run the task again
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; retrieve file and check trimmed attribute
      (let [row (th/db-get :file {:id (:id file)})]
        (t/is (true? (:has-media-trimmed row))))

      ;; check file media objects
      (let [rows (th/db-exec! ["select * from file_tagged_object_thumbnail where file_id = ?" file-id])]
        ;; (pp/pprint rows)
        (t/is (= 2 (count rows))))

      ;; check file media objects
      (let [rows (th/db-exec! ["select * from storage_object where deleted_at is null"])]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows))))

      ;; The underlying storage objects are available.
      (t/is (some? (sto/get-object storage (:media-id fot-1))))
      (t/is (some? (sto/get-object storage (:media-id fot-2))))

      ;; proceed to remove one frame
      (update-file!
       :file-id file-id
       :profile-id (:id profile)
       :revn 0
       :changes [{:type :del-obj
                  :page-id page-id
                  :id frame-id-2}])

      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id file-id})]
        (t/is (= 2 (count rows)))
        (t/is (= 1 (count (remove (comp some? :deleted-at) rows))))
        (t/is (= (thc/fmt-object-id file-id page-id frame-id-1 "frame")
                 (-> rows first :object-id))))

      ;; Now that file-gc have marked for deletion the object
      ;; thumbnail lets execute the objects-gc task which remove
      ;; the rows and mark as touched the storage object rows
      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 5 (:processed res))))

      ;; Now that objects-gc have deleted the object thumbnail lets
      ;; execute the touched-gc task
      (let [res (th/run-task! "storage-gc-touched" {:min-age 0})]
        (t/is (= 1 (:freeze res))))

      ;; check file media objects
      (let [rows (th/db-query :storage-object {:deleted-at nil})]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows))))

      ;; proceed to remove one frame
      (update-file!
       :file-id file-id
       :profile-id (:id profile)
       :revn 0
       :changes [{:type :del-obj
                  :page-id page-id
                  :id frame-id-1}])

      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id file-id})]
        (t/is (= 1 (count rows)))
        (t/is (= 0 (count (remove (comp some? :deleted-at) rows)))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        ;; (pp/pprint res)
        (t/is (= 3 (:processed res))))

      ;; We still have th storage objects in the table
      (let [rows (th/db-query :storage-object {:deleted-at nil})]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows))))

      ;; Now that file-gc have deleted the object thumbnail lets
      ;; execute the touched-gc task
      (let [res (th/run-task! :storage-gc-touched {:min-age 0})]
        (t/is (= 1 (:delete res))))

      ;; check file media objects
      (let [rows (th/db-query :storage-object {:deleted-at nil})]
        ;; (pp/pprint rows)
        (t/is (= 0 (count rows)))))))


(t/deftest permissions-checks-creating-file
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)

        data     {::th/type :create-file
                  ::rpc/profile-id (:id profile2)
                  :project-id (:default-project-id profile1)
                  :name "foobar"
                  :is-shared false
                  :components-v2 true}
        out      (th/command! data)
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
                  ::rpc/profile-id (:id profile2)
                  :name "foobar"}
        out      (th/command! data)
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
                  ::rpc/profile-id (:id profile2)
                  :id (:id file)}
        out      (th/command! data)
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
                  ::rpc/profile-id (:id profile2)
                  :id (:id file)
                  :is-shared true}
        out      (th/command! data)
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
                  ::rpc/profile-id (:id profile2)
                  :file-id (:id file2)
                  :library-id (:id file1)}

        out      (th/command! data)
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
                  ::rpc/profile-id (:id profile2)
                  :file-id (:id file2)
                  :library-id (:id file1)}

        out      (th/command! data)
        error    (:error out)]

      ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest deletion
  (let [profile1 (th/create-profile* 1)
        file     (th/create-file* 1 {:project-id (:default-project-id profile1)
                                     :profile-id (:id profile1)})]
    ;; file is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (th/run-task! :objects-gc {:min-age 0})]
      (t/is (= 0 (:processed result))))

    ;; query the list of files
    (let [data {::th/type :get-project-files
                ::rpc/profile-id (:id profile1)
                :project-id (:default-project-id profile1)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 1 (count result)))))

    ;; Request file to be deleted
    (let [params {::th/type :delete-file
                  :id (:id file)
                  ::rpc/profile-id (:id profile1)}
          out    (th/command! params)]
      (t/is (nil? (:error out))))

    ;; query the list of files after soft deletion
    (let [data {::th/type :get-project-files
                ::rpc/profile-id (:id profile1)
                :project-id (:default-project-id profile1)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))

    ;; run permanent deletion (should be noop)
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration {:minutes 1})})]
      (t/is (= 0 (:processed result))))

    ;; query the list of file libraries of a after hard deletion
    (let [data {::th/type :get-file-libraries
                ::rpc/profile-id (:id profile1)
                :file-id (:id file)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))

    ;; run permanent deletion
    (let [result (th/run-task! :objects-gc {:min-age 0})]
      (t/is (= 1 (:processed result))))

    ;; query the list of file libraries of a after hard deletion
    (let [data {::th/type :get-file-libraries
                ::rpc/profile-id (:id profile1)
                :file-id (:id file)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))))


(t/deftest object-thumbnails-ops
  (let [prof (th/create-profile* 1 {:is-active true})
        file (th/create-file* 1 {:profile-id (:id prof)
                                 :project-id (:default-project-id prof)
                                 :is-shared false})
        page-id   (get-in file [:data :pages 0])
        frame1-id (uuid/next)
        shape1-id (uuid/next)
        frame2-id (uuid/next)
        shape2-id (uuid/next)

        changes   [{:type :add-obj
                    :page-id page-id
                    :id frame1-id
                    :parent-id uuid/zero
                    :frame-id uuid/zero
                    :obj (cts/setup-shape
                          {:id frame1-id
                           :use-for-thumbnail? true
                           :name "test-frame1"
                           :type :frame})}
                   {:type :add-obj
                    :page-id page-id
                    :id shape1-id
                    :parent-id frame1-id
                    :frame-id frame1-id
                    :obj (cts/setup-shape
                          {:id shape1-id
                           :name "test-shape1"
                           :type :rect})}
                   {:type :add-obj
                    :page-id page-id
                    :id frame2-id
                    :parent-id uuid/zero
                    :frame-id uuid/zero
                    :obj (cts/setup-shape
                          {:id frame2-id
                           :name "test-frame2"
                           :type :frame})}
                   {:type :add-obj
                    :page-id page-id
                    :id shape2-id
                    :parent-id frame2-id
                    :frame-id frame2-id
                    :obj (cts/setup-shape
                          {:id shape2-id
                           :name "test-shape2"
                           :type :rect})}]]
    ;; Update the file
    (th/update-file* {:file-id (:id file)
                      :profile-id (:id prof)
                      :revn 0
                      :components-v2 true
                      :changes changes})

    (t/testing "RPC page query (rendering purposes)"

      ;; Query :page RPC method without passing page-id
      (let [data {::th/type :get-page
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :features cfeat/supported-features}
            {:keys [error result] :as out} (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (map? result))
        (t/is (contains? result :objects))
        (t/is (contains? (:objects result) frame1-id))
        (t/is (contains? (:objects result) shape1-id))
        (t/is (contains? (:objects result) frame2-id))
        (t/is (contains? (:objects result) shape2-id))
        (t/is (contains? (:objects result) uuid/zero)))

      ;; Query :page RPC method with page-id
      (let [data {::th/type :get-page
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :page-id page-id
                  :features cfeat/supported-features}
            {:keys [error result] :as out} (th/command! data)]
        ;; (th/print-result! out)
        (t/is (map? result))
        (t/is (contains? result :objects))
        (t/is (contains? (:objects result) frame1-id))
        (t/is (contains? (:objects result) shape1-id))
        (t/is (contains? (:objects result) frame2-id))
        (t/is (contains? (:objects result) shape2-id))
        (t/is (contains? (:objects result) uuid/zero)))

      ;; Query :page RPC method with page-id and object-id
      (let [data {::th/type :get-page
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :page-id page-id
                  :object-id frame1-id
                  :features cfeat/supported-features}
            {:keys [error result] :as out} (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (map? result))
        (t/is (contains? result :objects))
        (t/is (contains? (:objects result) frame1-id))
        (t/is (contains? (:objects result) shape1-id))
        (t/is (not (contains? (:objects result) uuid/zero)))
        (t/is (not (contains? (:objects result) frame2-id)))
        (t/is (not (contains? (:objects result) shape2-id))))

      ;; Query :page RPC method with wrong params
      (let [data {::th/type :get-page
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :object-id frame1-id
                  :features cfeat/supported-features}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (not (th/success? out)))
        (let [{:keys [type code]} (-> out :error ex-data)]
          (t/is (= :validation type))
          (t/is (= :params-validation code)))))

    (t/testing "RPC :file-data-for-thumbnail"
      ;; Insert a thumbnail data for the frame-id
      (let [data {::th/type :create-file-object-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :object-id (thc/fmt-object-id (:id file) page-id frame1-id "frame")
                  :media {:filename "sample.jpg"
                          :size 7923
                          :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                          :mtype "image/jpeg"}}
            {:keys [error result] :as out} (th/command! data)]
        (t/is (nil? error))
        (t/is (map? result)))

      ;; Check the result
      (let [data {::th/type :get-file-data-for-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :features cfeat/supported-features}
            {:keys [error result] :as out} (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (map? result))
        (t/is (contains? result :page))
        (t/is (contains? result :revn))
        (t/is (contains? result :file-id))

        (t/is (= (:id file) (:file-id result)))
        (t/is (str/starts-with? (get-in result [:page :objects frame1-id :thumbnail])
                                "http://localhost:3449/assets/by-id/"))
        (t/is (= [] (get-in result [:page :objects frame1-id :shapes]))))

      ;; Delete thumbnail data
      (let [data {::th/type :delete-file-object-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :object-id (thc/fmt-object-id (:id file) page-id frame1-id "frame")}
            {:keys [error result] :as out} (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (nil? result)))

      ;; Check the result
      (let [data {::th/type :get-file-data-for-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :features cfeat/supported-features}
            {:keys [error result] :as out} (th/command! data)]
        ;; (th/print-result! out)
        (t/is (map? result))
        (t/is (contains? result :page))
        (t/is (contains? result :revn))
        (t/is (contains? result :file-id))
        (t/is (= (:id file) (:file-id result)))
        (t/is (nil? (get-in result [:page :objects frame1-id :thumbnail])))
        (t/is (not= [] (get-in result [:page :objects frame1-id :shapes])))))

    (t/testing "TASK :file-gc"

      ;; insert object snapshot for known frame
      (let [data {::th/type :create-file-object-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :object-id (thc/fmt-object-id (:id file) page-id frame1-id "frame")
                  :media {:filename "sample.jpg"
                          :size 7923
                          :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                          :mtype "image/jpeg"}}
            {:keys [error result] :as out} (th/command! data)]
        (t/is (nil? error))
        (t/is (map? result)))

      ;; Wait to file be ellegible for GC
      (th/sleep 300)

      ;; run the task
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; check that object thumbnails are still here
      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        ;; (app.common.pprint/pprint rows)
        (t/is (= 1 (count rows))))

      ;; insert object snapshot for for unknown frame
      (let [data {::th/type :create-file-object-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :object-id (thc/fmt-object-id (:id file) page-id (uuid/next) "frame")
                  :media {:filename "sample.jpg"
                          :size 7923
                          :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                          :mtype "image/jpeg"}}
            {:keys [error result] :as out} (th/command! data)]
        (t/is (nil? error))
        (t/is (map? result)))

      ;; Mark file as modified
      (th/db-exec! ["update file set has_media_trimmed=false where id=?" (:id file)])

      ;; check that we have all object thumbnails
      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        ;; (app.common.pprint/pprint rows)
        (t/is (= 2 (count rows))))

      ;; run the task again
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      ;; check that we have all object thumbnails
      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        ;; (app.common.pprint/pprint rows)
        (t/is (= 2 (count rows))))


      ;; check that the unknown frame thumbnail is deleted
      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        (t/is (= 2 (count rows)))
        (t/is (= 1 (count (remove :deleted-at rows)))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 4 (:processed res))))

      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        ;; (app.common.pprint/pprint rows)
        (t/is (= 1 (count rows)))))))

(t/deftest file-thumbnail-ops
  (let [prof (th/create-profile* 1 {:is-active true})
        file (th/create-file* 1 {:profile-id (:id prof)
                                 :project-id (:default-project-id prof)
                                 :revn 2
                                 :is-shared false})]

    (t/testing "create a file thumbnail"
      ;; insert object snapshot for known frame
      (let [data {::th/type :create-file-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :revn 1
                  :media {:filename "sample.jpg"
                          :size 7923
                          :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                          :mtype "image/jpeg"}}
            {:keys [error result] :as out} (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (map? result)))

      ;; insert another thumbnail with different revn
      (let [data {::th/type :create-file-thumbnail
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :revn 2
                  :media {:filename "sample.jpg"
                          :size 7923
                          :path (th/tempfile "backend_tests/test_files/sample2.jpg")
                          :mtype "image/jpeg"}}
            {:keys [error result] :as out} (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (map? result)))

      (let [rows (th/db-query :file-thumbnail {:file-id (:id file)})]
        (t/is (= 2 (count rows)))))

    (t/testing "gc task"
      (let [res (th/run-task! :file-gc {:min-age 0})]
        (t/is (= 1 (:processed res))))

      (let [rows (th/db-query :file-thumbnail {:file-id (:id file)})]
        (t/is (= 2 (count rows)))
        (t/is (= 1 (count (remove (comp some? :deleted-at) rows)))))

      (let [res (th/run-task! :objects-gc {:min-age 0})]
        (t/is (= 2 (:processed res))))

      (let [rows (th/db-query :file-thumbnail {:file-id (:id file)})]
        (t/is (= 1 (count rows)))))))
