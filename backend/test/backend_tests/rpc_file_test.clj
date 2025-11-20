;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-file-test
  (:require
   [app.common.features :as cfeat]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as fdata]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.setup.clock :as clock]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- update-file!
  [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features cfeat/supported-features
                :changes changes}
        out    (th/command! params)]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (:result out)))

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
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        page-id  (uuid/random)
        shape-id (uuid/random)]

    ;; Preventive file-gc
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file) :revn (:revn file)})))

    ;; Check the number of fragments before adding the page
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 2 (count rows))))

    ;; Add page
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "test"
       :id page-id}])

    ;; Check the number of fragments before adding the page
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 3 (count rows))))

    ;; The file-gc should mark for remove unused fragments
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

    ;; Check the number of fragments
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 5 (count rows)))
      (t/is (= 3 (count (filterv :deleted-at rows)))))

    ;; The objects-gc should remove unused fragments
    (let [res (th/run-task! :objects-gc {})]
      (t/is (= 3 (:processed res))))

    ;; Check the number of fragments
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 2 (count rows))))

    ;; Add shape to page that should add a new fragment
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
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
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 3 (count rows))))

    ;; The file-gc should mark for remove unused fragments
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

    ;; The objects-gc should remove unused fragments
    (let [res (th/run-task! :objects-gc {})]
      (t/is (= 3 (:processed res))))

    ;; Check the number of fragments;
    (let [rows (th/db-query :file-data {:file-id (:id file)
                                        :type "fragment"
                                        :deleted-at nil})]
      (t/is (= 2 (count rows))))

    ;; Lets proceed to delete all changes
    (th/db-delete! :file-change {:file-id (:id file)})
    (th/db-delete! :file-data {:file-id (:id file) :type "snapshot"})

    (th/db-update! :file
                   {:has-media-trimmed false}
                   {:id (:id file)})

    ;; The file-gc should remove fragments related to changes
    ;; snapshots previously deleted.
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

    ;; Check the number of fragments;
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      ;; (pp/pprint rows)
      (t/is (= 4 (count rows)))
      (t/is (= 2 (count (remove :deleted-at rows)))))

    (let [res (th/run-task! :objects-gc {})]
      (t/is (= 2 (:processed res))))

    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 2 (count rows))))))

(t/deftest file-gc-with-thumbnails
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
       :vern 0
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
                :type :rect
                :fills [{:fill-opacity 1
                         :fill-image {:id (:id fmo1) :width 100 :height 100 :mtype "image/jpeg"}}]})}])

      ;; Check that reference storage objects on file_media_objects
      ;; are the same because of deduplication feature.
      (t/is (= (:media-id fmo1) (:media-id fmo2)))
      (t/is (= (:thumbnail-id fmo1) (:thumbnail-id fmo2)))

      ;; If we launch gc-touched-task, we should have 2 items to
      ;; freeze because of the deduplication (we have uploaded 2 times
      ;; the same files).

      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 2 (:freeze res)))
        (t/is (= 0 (:delete res))))

      ;; run the task again
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      ;; retrieve file and check trimmed attribute
      (let [row (th/db-get :file {:id (:id file)})]
        (t/is (true? (:has-media-trimmed row))))

      ;; check file media objects
      (let [[row1 row2 :as rows]
            (th/db-query :file-media-object
                         {:file-id (:id file)}
                         {:order-by [:created-at]})]

        (t/is (= (:id fmo1) (:id row1)))
        (t/is (= (:id fmo2) (:id row2)))
        (t/is (ct/inst? (:deleted-at row2))))

      (let [res (th/run-task! :objects-gc {})]
        ;; delete 2 fragments and 1 media object
        (t/is (= 3 (:processed res))))

      ;; check file media objects
      (let [rows (th/db-query :file-media-object {:file-id (:id file)})]
        (t/is (= 1 (count rows)))
        (t/is (= 1 (count (remove :deleted-at rows)))))

      ;; The underlying storage objects are still available.
      (t/is (some? (sto/get-object storage (:media-id fmo1))))
      (t/is (some? (sto/get-object storage (:thumbnail-id fmo1))))

      ;; proceed to remove usage of the file
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :vern 0
       :changes [{:type :del-obj
                  :page-id (first (get-in file [:data :pages]))
                  :id shid}])

      ;; Now, we have deleted the usage of pointers to the
      ;; file-media-objects, if we paste file-gc, they should be marked
      ;; as deleted.
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      ;; This only clears fragments, the file media objects still referenced because
      ;; snapshots are preserved
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 2 (:processed res))))

      ;; Delete all snapshots
      (th/db-exec! ["update file_data set deleted_at = now() where file_id = ? and type = 'snapshot'" (:id file)])
      (th/db-exec! ["update file_change set deleted_at = now() where file_id = ? and label is not null" (:id file)])
      (th/db-exec! ["update file set has_media_trimmed = false where id = ?" (:id file)])

      (let [res (th/run-task! :objects-gc {})]
        ;; this will remove the file change and file data entries for two snapshots
        (t/is (= 4 (:processed res))))

      ;; Rerun the file-gc and objects-gc
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      (let [res (th/run-task! :objects-gc {})]
        ;; this will remove the file media objects marked as deleted
        ;; on prev file-gc
        (t/is (= 2 (:processed res))))

      ;; Now that file-gc have deleted the file-media-object usage,
      ;; lets execute the touched-gc task, we should see that two of
      ;; them are marked to be deleted
      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 0 (:freeze res)))
        (t/is (= 2 (:delete res))))

      ;; Finally, check that some of the objects that are marked as
      ;; deleted we are unable to retrieve them using standard storage
      ;; public api
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
          s1-shid  (uuid/random)
          s2-shid  (uuid/random)
          t-shid  (uuid/random)

          page-id (first (get-in file [:data :pages]))]


      (let [rows (th/db-query :file-data {:file-id (:id file)
                                          :type "fragment"
                                          :deleted-at nil})]
        (t/is (= (count rows) 1)))

      ;; Update file inserting a new image object
      (update-file!
       :file-id (:id file)
       :profile-id (:id profile)
       :revn 0
       :vern 0
       :changes
       [{:type :add-obj
         :page-id page-id
         :id s1-shid
         :parent-id uuid/zero
         :frame-id uuid/zero
         :components-v2 true
         :obj (cts/setup-shape
               {:id s1-shid
                :name "image"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :rect
                :fills [{:fill-opacity 1 :fill-image {:id (:id fmo2) :width 101 :height 100 :mtype "image/jpeg"}}]
                :strokes [{:stroke-opacity 1 :stroke-image {:id (:id fmo3) :width 102 :height 100 :mtype "image/jpeg"}}]})}
        {:type :add-obj
         :page-id page-id
         :id s2-shid
         :parent-id uuid/zero
         :frame-id uuid/zero
         :components-v2 true
         :obj (cts/setup-shape
               {:id s2-shid
                :name "image"
                :frame-id uuid/zero
                :parent-id uuid/zero
                :type :rect
                :fills [{:fill-opacity 1 :fill-image {:id (:id fmo1) :width 103 :height 100 :mtype "image/jpeg"}}]})}
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
                :strokes [{:stroke-opacity 1 :stroke-image {:id (:id fmo5) :width 100 :height 100 :mtype "image/jpeg"}}]})}])


      ;; run the task again
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 2 (:processed res))))

      (let [rows (th/db-query :file-data {:file-id (:id file)
                                          :type "fragment"
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
       :vern 0
       :changes [{:type :del-obj
                  :page-id (first (get-in file [:data :pages]))
                  :id s1-shid}
                 {:type :del-obj
                  :page-id (first (get-in file [:data :pages]))
                  :id t-shid}
                 {:type :del-obj
                  :page-id (first (get-in file [:data :pages]))
                  :id s2-shid}])

      ;; Now, we have deleted the usage of pointers to the
      ;; file-media-objects, if we paste file-gc, they should be marked
      ;; as deleted.
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      ;; This only removes unused fragments, file media are still
      ;; referenced on snapshots.
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 2 (:processed res))))

      ;; Mark all snapshots to be a non-snapshot file change
      (th/db-exec! ["update file set has_media_trimmed = false where id = ?" (:id file)])
      (th/db-delete! :file-data {:file-id (:id file)
                                 :type "snapshot"})

      ;; Rerun file-gc and objects-gc task for the same file once all snapshots are
      ;; "expired/deleted"
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 6 (:processed res))))

      (let [rows (th/db-query :file-data {:file-id (:id file)
                                          :type "fragment"
                                          :deleted-at nil})]
        (t/is (= (count rows) 1)))

      ;; Now that file-gc have deleted the file-media-object usage,
      ;; lets execute the touched-gc task, we should see that two of
      ;; them are marked to be deleted.
      (let [res (th/run-task! :storage-gc-touched {})]
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

(t/deftest file-gc-with-object-thumbnails
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
       :vern 0
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

      (let [res (th/run-task! :storage-gc-touched {})]
        (t/is (= 1 (:freeze res)))
        (t/is (= 0 (:delete res))))

      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

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
       :vern 0
       :changes [{:type :del-obj
                  :page-id page-id
                  :id frame-id-2}])

      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id file-id})]
        (t/is (= 2 (count rows)))
        (t/is (= 1 (count (remove (comp some? :deleted-at) rows))))
        (t/is (= (thc/fmt-object-id file-id page-id frame-id-1 "frame")
                 (-> rows first :object-id))))

      ;; Now that file-gc have marked for deletion the object
      ;; thumbnail lets execute the objects-gc task which remove
      ;; the rows and mark as touched the storage object rows
      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 5 (:processed res))))

      ;; Now that objects-gc have deleted the object thumbnail lets
      ;; execute the touched-gc task
      (let [res (th/run-task! "storage-gc-touched" {})]
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
       :vern 0
       :changes [{:type :del-obj
                  :page-id page-id
                  :id frame-id-1}])

      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id file-id})]
        (t/is (= 1 (count rows)))
        (t/is (= 0 (count (remove (comp some? :deleted-at) rows)))))

      (let [res (th/run-task! :objects-gc {})]
        ;; (pp/pprint res)
        (t/is (= 3 (:processed res))))

      ;; We still have th storage objects in the table
      (let [rows (th/db-query :storage-object {:deleted-at nil})]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows))))

      ;; Now that file-gc have deleted the object thumbnail lets
      ;; execute the touched-gc task
      (let [res (th/run-task! :storage-gc-touched {})]
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
    (let [result (th/run-task! :objects-gc {})]
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

    (th/run-pending-tasks!)

    ;; query the list of files after soft deletion
    (let [data {::th/type :get-project-files
                ::rpc/profile-id (:id profile1)
                :project-id (:default-project-id profile1)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))

    (let [data {::th/type :get-file-libraries
                ::rpc/profile-id (:id profile1)
                :file-id (:id file)}
          out  (th/command! data)]
      ;; (th/print-result! out)

      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))

    ;; run permanent deletion (should be noop)
    (let [result (th/run-task! :objects-gc {})]
      (t/is (= 0 (:processed result))))

    ;; run permanent deletion
    (binding [ct/*clock* (clock/fixed (ct/in-future {:days 8}))]
      (let [result (th/run-task! :objects-gc {})]
        (t/is (= 3 (:processed result)))))

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
                      :vern 0
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
        (t/is (uuid? (get-in result [:page :objects frame1-id :thumbnail-id])))
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
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

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
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      ;; check that we have all object thumbnails
      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        ;; (app.common.pprint/pprint rows)
        (t/is (= 2 (count rows))))


      ;; check that the unknown frame thumbnail is deleted
      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        (t/is (= 2 (count rows)))
        (t/is (= 1 (count (remove :deleted-at rows)))))

      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 4 (:processed res))))

      (let [rows (th/db-query :file-tagged-object-thumbnail {:file-id (:id file)})]
        ;; (app.common.pprint/pprint rows)
        (t/is (= 1 (count rows)))))))

(t/deftest file-thumbnail-ops
  (let [prof (th/create-profile* 1 {:is-active true})
        file (th/create-file* 1 {:profile-id (:id prof)
                                 :project-id (:default-project-id prof)
                                 :revn 2
                                 :vern 0
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
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

      (let [rows (th/db-query :file-thumbnail {:file-id (:id file)})]
        (t/is (= 2 (count rows)))
        (t/is (= 1 (count (remove (comp some? :deleted-at) rows)))))

      (let [res (th/run-task! :objects-gc {})]
        (t/is (= 2 (:processed res))))

      (let [rows (th/db-query :file-thumbnail {:file-id (:id file)})]
        (t/is (= 1 (count rows)))))))

(t/deftest file-tiered-storage
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        page-id  (uuid/random)
        shape-id (uuid/random)
        sobject  (volatile! nil)]

    ;; Preventive file-gc
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

    ;; Preventive objects-gc
    (let [result (th/run-task! :objects-gc {})]
      ;; deletes the fragment created by file-gc
      (t/is (= 1 (:processed result))))

    ;; Check the number of fragments before adding the page
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 1 (count rows)))
      (t/is (every? #(some? (:data %)) rows)))

      ;; Mark the file ellegible again for GC
    (th/db-update! :file
                   {:has-media-trimmed false}
                   {:id (:id file)})

    ;; Run FileGC again, with tiered storage activated
    (with-redefs [app.config/flags (conj app.config/flags :tiered-file-data-storage)]
      (t/is (true? (th/run-task! :file-gc {:file-id (:id file)}))))

    ;; The FileGC task will schedule an inner taskq
    (th/run-pending-tasks!)

    (let [res (th/run-task! :storage-gc-touched {})]
      (t/is (= 2 (:freeze res)))
      (t/is (= 0 (:delete res))))

    ;; Clean objects after file-gc
    (let [result (th/run-task! :objects-gc {})]
      (t/is (= 1 (:processed result))))

    ;; Check the number of fragments before adding the page
    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      ;; (pp/pprint rows)
      (t/is (= 1 (count rows)))
      (t/is (every? #(nil? (:data %)) rows))
      (t/is (every? #(= "storage" (:backend %)) rows)))

    (let [file    (-> (th/db-get :file-data {:id (:id file) :type "main"})
                      (update :metadata fdata/decode-metadata))
          storage (sto/resolve th/*system*)]
      ;; (pp/pprint file)
      (t/is (= "storage" (:backend file)))
      (t/is (nil? (:data file)))

      (let [sobj (sto/get-object storage (-> file :metadata :storage-ref-id))]
        (vreset! sobject sobj)
        ;; (pp/pprint (meta sobj))
        (t/is (= "file-data" (:bucket (meta sobj))))
        (t/is (= (:id file) (:file-id (meta sobj))))))

    ;; Add shape to page that should load from cold storage again into the hot storage (db)
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "test"
       :id page-id}])

    ;; Check the number of fragments
    (let [[row1 row2 :as rows]
          (th/db-query :file-data
                       {:file-id (:id file)
                        :type "fragment"}
                       {:order-by [:created-at]})]
        ;; (pp/pprint rows)
      (t/is (= 2 (count rows)))
      (t/is (nil? (:data row1)))
      (t/is (= "storage" (:backend row1)))
      (t/is (bytes? (:data row2)))
      (t/is (= "db" (:backend row2))))


    ;; The file-gc should mark for remove unused fragments
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

    ;; The file-gc task, recreates all fragments, so after it we have
    ;; now the double of fragments, and the old ones are marked as
    ;; deleted, and the new ones are on DB
    (let [[row1 row2 row3 row4 :as rows]
          (th/db-query :file-data
                       {:file-id (:id file)
                        :type "fragment"}
                       {:order-by [:created-at]})]
      ;; (pp/pprint rows)
      (t/is (= 4 (count rows)))

      (t/is (nil? (:data row1)))
      (t/is (ct/inst? (:deleted-at row1)))
      (t/is (= "storage" (:backend row1)))

      (t/is (bytes? (:data row2)))
      (t/is (= "db" (:backend row2)))
      (t/is (ct/inst? (:deleted-at row2)))

      (t/is (bytes? (:data row3)))
      (t/is (= "db" (:backend row3)))
      (t/is (nil? (:deleted-at row3)))

      (t/is (bytes? (:data row4)))
      (t/is (= "db" (:backend row4)))
      (t/is (nil? (:deleted-at row4))))

    ;; The objects-gc should remove the marked to delete fragments
    (let [res (th/run-task! :objects-gc {})]
      (t/is (= 2 (:processed res))))

    (let [rows (th/db-query :file-data {:file-id (:id file) :type "fragment"})]
      (t/is (= 2 (count rows)))
      (t/is (every? #(bytes? (:data %)) rows))
      (t/is (every? #(= "db" (:backend %)) rows)))

    ;; we ensure that once object-gc is passed and marked two storage
    ;; objects to delete
    (let [res (th/run-task! :storage-gc-touched {})]
      (t/is (= 0 (:freeze res)))
      (t/is (= 2 (:delete res))))

    (let [storage (sto/resolve th/*system*)]
      (t/is (uuid? (:id @sobject)))
      (t/is (nil? (sto/get-object storage (:id @sobject)))))))

(t/deftest file-gc-with-components-1
  (let [storage (:app.storage/storage th/*system*)
        profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        s-id-1  (uuid/random)
        s-id-2  (uuid/random)
        c-id    (uuid/random)

        page-id (first (get-in file [:data :pages]))]

    (let [rows (th/db-query :file-data {:file-id (:id file)
                                        :type "fragment"
                                        :deleted-at nil})]
      (t/is (= (count rows) 1)))

    ;; Update file inserting new component
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id page-id
       :id s-id-1
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id s-id-1
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :main-instance true
              :component-root true
              :component-file (:id file)
              :component-id c-id})}

      {:type :add-obj
       :page-id page-id
       :id s-id-2
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id s-id-2
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :main-instance false
              :component-root true
              :component-file (:id file)
              :component-id c-id})}

      {:type :add-component
       :path ""
       :name "Board"
       :main-instance-id s-id-1
       :main-instance-page page-id
       :id c-id
       :anotation nil}])

    ;; Run the task again
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

    ;; Retrieve file and check trimmed attribute
    (let [row (th/db-get :file {:id (:id file)})]
      (t/is (true? (:has-media-trimmed row))))

    ;; Check that component exists
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result     (:result out)
            component (get-in result [:data :components c-id])]

        (t/is (some? component))
        (t/is (nil? (:objects component)))))

    ;; Now proceed to delete a component
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :del-component
       :id c-id}
      {:type :del-obj
       :page-id page-id
       :id s-id-1
       :ignore-touched true}])

    ;; ;; Check that component is marked as deleted
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result (:result out)
            component (get-in result [:data :components c-id])]
        (t/is (true? (:deleted component)))
        (t/is (some? (not-empty (:objects component))))))

    ;; Re-run the file-gc task
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))
    (let [row (th/db-get :file {:id (:id file)})]
      (t/is (true? (:has-media-trimmed row))))

    ;; Check that component is still there after file-gc task
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result (:result out)
            component (get-in result [:data :components c-id])]
        (t/is (true? (:deleted component)))
        (t/is (some? (not-empty (:objects component))))))

    ;; Now delete the last instance using deleted component
    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :del-obj
       :page-id page-id
       :id s-id-2
       :ignore-touched true}])

    ;; Now, we have deleted the usage of component if we pass file-gc,
    ;; that component should be deleted
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))

    ;; Check that component is properly removed
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result     (:result out)
            components (get-in result [:data :components])]
        (t/is (not (contains? components c-id)))))))

(t/deftest file-gc-with-components-2
  (let [storage (:app.storage/storage th/*system*)
        profile (th/create-profile* 1)
        file-1  (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})

        file-2  (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        rel     (th/link-file-to-library*
                 {:file-id (:id file-2)
                  :library-id (:id file-1)})

        s-id-1  (uuid/random)
        s-id-2  (uuid/random)
        c-id    (uuid/random)

        f1-page-id (first (get-in file-1 [:data :pages]))
        f2-page-id (first (get-in file-2 [:data :pages]))]

    ;; Update file library inserting new component
    (update-file!
     :file-id (:id file-1)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id f1-page-id
       :id s-id-1
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id s-id-1
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :main-instance true
              :component-root true
              :component-file (:id file-1)
              :component-id c-id})}
      {:type :add-component
       :path ""
       :name "Board"
       :main-instance-id s-id-1
       :main-instance-page f1-page-id
       :id c-id
       :anotation nil}])

    ;; Instanciate a component in a different file
    (update-file!
     :file-id (:id file-2)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id f2-page-id
       :id s-id-2
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id s-id-2
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :main-instance false
              :component-root true
              :component-file (:id file-1)
              :component-id c-id})}])

    ;; Run the file-gc on file and library
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file-1)})))
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file-2)})))

    ;; Check that component exists
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file-1)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result     (:result out)
            component (get-in result [:data :components c-id])]

        (t/is (some? component))
        (t/is (nil? (:objects component)))))

    ;; Now proceed to delete a component
    (update-file!
     :file-id (:id file-1)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :del-component
       :id c-id}
      {:type :del-obj
       :page-id f1-page-id
       :id s-id-1
       :ignore-touched true}])

    ;; Check that component is marked as deleted
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file-1)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result (:result out)
            component (get-in result [:data :components c-id])]
        (t/is (true? (:deleted component)))
        (t/is (some? (not-empty (:objects component))))))

    ;; Re-run the file-gc task
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file-1)})))

    ;; Check that component is still there after file-gc task
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file-1)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result (:result out)
            component (get-in result [:data :components c-id])]
        (t/is (true? (:deleted component)))
        (t/is (some? (not-empty (:objects component))))))

    ;; Now delete the last instance using deleted component
    (update-file!
     :file-id (:id file-2)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :del-obj
       :page-id f2-page-id
       :id s-id-2
       :ignore-touched true}])

    ;; Mark
    (th/db-exec! ["update file set has_media_trimmed = false where id = ?" (:id file-1)])

    ;; Now, we have deleted the usage of component if we pass file-gc,
    ;; that component should be deleted
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file-1)})))

    ;; Check that component is properly removed
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file-1)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result     (:result out)
            components (get-in result [:data :components])]
        (t/is (not (contains? components c-id)))))))




(defn add-file-media-object
  [& {:keys [profile-id file-id]}]
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



(t/deftest file-gc-with-media-assets-and-absorb-library
  (let [storage (:app.storage/storage th/*system*)
        profile (th/create-profile* 1)

        file-1  (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})

        file-2  (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        fmedia  (add-file-media-object :profile-id (:id profile) :file-id (:id file-1))


        rel     (th/link-file-to-library*
                 {:file-id (:id file-2)
                  :library-id (:id file-1)})

        s-id-1  (uuid/random)
        s-id-2  (uuid/random)
        c-id    (uuid/random)

        f1-page-id (first (get-in file-1 [:data :pages]))
        f2-page-id (first (get-in file-2 [:data :pages]))

        fills
        [{:fill-image
          {:id (:id fmedia)
           :name "test"
           :mtype "image/jpeg"
           :width 200
           :height 200}}]]

    ;; Update file library inserting new component
    (update-file!
     :file-id (:id file-1)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id f1-page-id
       :id s-id-1
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id s-id-1
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :fills fills
              :main-instance true
              :component-root true
              :component-file (:id file-1)
              :component-id c-id})}
      {:type :add-component
       :path ""
       :name "Board"
       :main-instance-id s-id-1
       :main-instance-page f1-page-id
       :id c-id
       :anotation nil}])

    ;; Instanciate a component in a different file
    (update-file!
     :file-id (:id file-2)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-obj
       :page-id f2-page-id
       :id s-id-2
       :parent-id uuid/zero
       :frame-id uuid/zero
       :components-v2 true
       :obj (cts/setup-shape
             {:id s-id-2
              :name "Board"
              :frame-id uuid/zero
              :parent-id uuid/zero
              :type :frame
              :fills fills
              :main-instance false
              :component-root true
              :component-file (:id file-1)
              :component-id c-id})}])

    ;; Check that file media object references are present for both objects
    ;; the original one and the instance.
    (let [rows (th/db-exec! ["SELECT * FROM file_media_object ORDER BY created_at ASC"])]
      (t/is (= 2 (count rows)))
      (t/is (= (:id file-1) (:file-id (get rows 0))))
      (t/is (= (:id file-2) (:file-id (get rows 1))))
      (t/is (every? (comp nil? :deleted-at) rows)))

    ;; Check if the underlying media reference on shape is different
    ;; from the instantiation
    (let [data {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id (:id file-2)}
          out  (th/command! data)]

      (t/is (th/success? out))
      (let [result (:result out)
            fill   (get-in result [:data :pages-index f2-page-id :objects s-id-2 :fills 0 :fill-image])]
        (t/is (some? fill))
        (t/is (not= (:id fill) (:id fmedia)))))

    ;; Run the file-gc on file and library
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file-1)})))
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file-2)})))

    ;; Now proceed to delete file and absorb it
    (let [data {::th/type :delete-file
                ::rpc/profile-id (:id profile)
                :id (:id file-1)}
          out  (th/command! data)]
      (t/is (th/success? out)))

    (th/run-task! :delete-object
                  {:object :file
                   :deleted-at (ct/now)
                   :id (:id file-1)})

    ;; Check that file media object references are marked all for deletion
    (let [rows (th/db-exec! ["SELECT * FROM file_media_object ORDER BY created_at ASC"])]
      ;; (pp/pprint rows)
      (t/is (= 2 (count rows)))

      (t/is (= (:id file-1) (:file-id (get rows 0))))
      (t/is (some? (:deleted-at (get rows 0))))

      (t/is (= (:id file-2) (:file-id (get rows 1))))
      (t/is (nil? (:deleted-at (get rows 1)))))

    (th/run-task! :objects-gc {})

    (let [rows (th/db-exec! ["SELECT * FROM file_media_object ORDER BY created_at ASC"])]
      (t/is (= 1 (count rows)))

      (t/is (= (:id file-2) (:file-id (get rows 0))))
      (t/is (nil? (:deleted-at (get rows 0)))))))

(t/deftest deleted-files-permanently-delete
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        file-id (uuid/next)
        now     (ct/inst "2025-10-31T00:00:00Z")]

    (binding [ct/*clock* (clock/fixed now)]
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
          (t/is (= proj-id (:project-id result)))))

      (let [data {::th/type :delete-file
                  :id file-id
                  ::rpc/profile-id (:id prof)}
            out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out))))

      ;; get deleted files
      (let [data {::th/type :get-team-deleted-files
                  ::rpc/profile-id (:id prof)
                  :team-id team-id}
            out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [[row1 :as result] (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= (:will-be-deleted-at row1) #penpot/inst "2025-11-07T00:00:00Z"))
          (t/is (= (:created-at row1) #penpot/inst "2025-10-31T00:00:00Z"))
          (t/is (= (:modified-at row1) #penpot/inst "2025-10-31T00:00:00Z"))))

      (let [data {::th/type :permanently-delete-team-files
                  ::rpc/profile-id (:id prof)
                  :team-id team-id
                  :ids #{file-id}}
            out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (= (:ids data) result)))

        (let [row (th/db-exec-one! ["select * from file where id = ?" file-id])]
          (t/is (= (:deleted-at row) now)))))))

(t/deftest restore-deleted-files
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        file-id (uuid/next)
        now     (ct/inst "2025-10-31T00:00:00Z")]

    (binding [ct/*clock* (clock/fixed now)]
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
          (t/is (= proj-id (:project-id result)))))

      (let [data {::th/type :delete-file
                  :id file-id
                  ::rpc/profile-id (:id prof)}
            out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out))))

      ;; get deleted files
      (let [data {::th/type :get-team-deleted-files
                  ::rpc/profile-id (:id prof)
                  :team-id team-id}
            out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [[row1 :as result] (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= (:will-be-deleted-at row1) #penpot/inst "2025-11-07T00:00:00Z"))
          (t/is (= (:created-at row1) #penpot/inst "2025-10-31T00:00:00Z"))
          (t/is (= (:modified-at row1) #penpot/inst "2025-10-31T00:00:00Z"))))

      (let [data {::th/type :restore-deleted-team-files
                  ::rpc/profile-id (:id prof)
                  :team-id team-id
                  :ids #{file-id}}
            out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (fn? result))

          (let [events (th/consume-sse result)]
            ;; (pp/pprint events)
            (t/is (= 2 (count events)))
            (t/is (= :end (first (last events))))
            (t/is (= (:ids data) (last (last events)))))))

      (let [row (th/db-exec-one! ["select * from file where id = ?" file-id])]
        (t/is (nil? (:deleted-at row)))))))


(t/deftest restore-deleted-files-and-projets
  (let [profile (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id profile)
        now     (ct/inst "2025-10-31T00:00:00Z")]

    (binding [ct/*clock* (clock/fixed now)]
      (let [project (th/create-project* 1 {:profile-id (:id profile)
                                           :team-id team-id})
            file    (th/create-file* 1 {:profile-id (:id profile)
                                        :project-id (:id project)})

            data    {::th/type :delete-project
                     :id (:id project)
                     ::rpc/profile-id (:id profile)}
            out     (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))

        (th/run-pending-tasks!)

        ;; get deleted files
        (let [data {::th/type :get-team-deleted-files
                    ::rpc/profile-id (:id profile)
                    :team-id team-id}
              out (th/command! data)]
          ;; (th/print-result! out)
          (t/is (nil? (:error out)))
          (let [[row1 :as result] (:result out)]
            (t/is (= 1 (count result)))
            (t/is (= (:will-be-deleted-at row1) #penpot/inst "2025-11-07T00:00:00Z"))
            (t/is (= (:created-at row1) #penpot/inst "2025-10-31T00:00:00Z"))
            (t/is (= (:modified-at row1) #penpot/inst "2025-10-31T00:00:00Z"))))

        ;; Check if project is deleted
        (let [[row1 :as rows] (th/db-query :project {:id (:id project)})]
          ;; (pp/pprint rows)
          (t/is (= 1 (count rows)))
          (t/is (= (:deleted-at row1) #penpot/inst "2025-11-07T00:00:00Z"))
          (t/is (= (:created-at row1) #penpot/inst "2025-10-31T00:00:00Z"))
          (t/is (= (:modified-at row1) #penpot/inst "2025-10-31T00:00:00Z")))

        ;; Restore files
        (let [data {::th/type :restore-deleted-team-files
                    ::rpc/profile-id (:id profile)
                    :team-id team-id
                    :ids #{(:id file)}}
              out (th/command! data)]
          ;; (th/print-result! out)
          (t/is (nil? (:error out)))
          (let [result (:result out)]
            (t/is (fn? result))
            (let [events (th/consume-sse result)]
              ;; (pp/pprint events)
              (t/is (= 2 (count events)))
              (t/is (= :end (first (last events))))
              (t/is (= (:ids data) (last (last events)))))))


        (let [[row1 :as rows] (th/db-query :file {:project-id (:id project)})]
          ;; (pp/pprint rows)
          (t/is (= 1 (count rows)))
          (t/is (= (:created-at row1) #penpot/inst "2025-10-31T00:00:00Z"))
          (t/is (nil? (:deleted-at row1))))


        ;; Check if project is restored
        (let [[row1 :as rows] (th/db-query :project {:id (:id project)})]
          ;; (pp/pprint rows)
          (t/is (= 1 (count rows)))
          (t/is (= (:created-at row1) #penpot/inst "2025-10-31T00:00:00Z"))
          (t/is (nil? (:deleted-at row1))))))))
