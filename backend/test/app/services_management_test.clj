;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.services-management-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [app.test-helpers :as th]
   [clojure.test :as t]
   [buddy.core.bytes :as b]
   [datoteka.core :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest duplicate-file
  (let [storage (:app.storage/storage th/*system*)
        sobject (sto/put-object storage {:content (sto/content "content")
                                         :content-type "text/plain"
                                         :other "data"})
        profile (th/create-profile* 1 {:is-active true})
        project (th/create-project* 1 {:team-id (:default-team-id profile)
                                       :profile-id (:id profile)})
        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project)
                                    :is-shared true})

        libl    (th/link-file-to-library* {:file-id (:id file1)
                                           :library-id (:id file2)})

        mobj    (th/create-file-media-object* {:file-id (:id file1)
                                               :is-local false
                                               :media-id (:id sobject)})]
    (th/update-file*
     {:file-id (:id file1)
      :profile-id (:id profile)
      :changes [{:type :add-media
                 :object (select-keys mobj [:id :width :height :mtype :name])}]})

    (let [data {::th/type :duplicate-file
                :profile-id (:id profile)
                :file-id (:id file1)
                :name "file 1 (copy)"}
          out  (th/mutation! data)]

      ;; (th/print-result! out)

      ;; Check that result is correct
      (t/is (nil? (:error out)))
      (let [result (:result out)]

        ;; Check that the returned result is a file but has different id
        ;; and different name.
        (t/is (= "file 1 (copy)" (:name result)))
        (t/is (not= (:id file1) (:id result)))

        ;; Check that the new file has a correct file library relation
        (let [[item :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id result)})]
          (t/is (= 1 (count rows)))
          (t/is (= (:id file2) (:library-file-id item))))

        ;; Check that the new file has a correct file media objects
        (let [[item :as rows] (db/query th/*pool* :file-media-object {:file-id (:id result)})]
          (t/is (= 1 (count rows)))

          ;; Check that both items have different ids
          (t/is (not= (:id item) (:id mobj)))

          ;; check that both file-media-objects points to the same storage object.
          (t/is (= (:media-id item) (:media-id mobj)))
          (t/is (= (:media-id item) (:id sobject)))

          ;; Check if media correctly contains the new file-media-object id
          (t/is (contains? (get-in result [:data :media]) (:id item)))

          ;; And does not contains the old one
          (t/is (not (contains? (get-in result [:data :media]) (:id mobj)))))

        ;; Check the total number of files
        (let [rows (db/query th/*pool* :file {:project-id (:id project)})]
          (t/is (= 3 (count rows))))

        ))))

(t/deftest duplicate-file-with-deleted-rels
  (let [storage (:app.storage/storage th/*system*)
        sobject (sto/put-object storage {:content (sto/content "content")
                                         :content-type "text/plain"
                                         :other "data"})
        profile (th/create-profile* 1 {:is-active true})
        project (th/create-project* 1 {:team-id (:default-team-id profile)
                                       :profile-id (:id profile)})
        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project)
                                    :is-shared true})

        libl    (th/link-file-to-library* {:file-id (:id file1)
                                           :library-id (:id file2)})

        mobj    (th/create-file-media-object* {:file-id (:id file1)
                                               :is-local false
                                               :media-id (:id sobject)})

        _       (th/mark-file-deleted* {:id (:id file2)})
        _       (sto/del-object storage (:id sobject))]

    (th/update-file*
     {:file-id (:id file1)
      :profile-id (:id profile)
      :changes [{:type :add-media
                 :object (select-keys mobj [:id :width :height :mtype :name])}]})

    (let [data {::th/type :duplicate-file
                :profile-id (:id profile)
                :file-id (:id file1)
                :name "file 1 (copy)"}
          out  (th/mutation! data)]

      ;; (th/print-result! out)

      ;; Check that result is correct
      (t/is (nil? (:error out)))
      (let [result (:result out)]

        ;; Check that the returned result is a file but has different id
        ;; and different name.
        (t/is (= "file 1 (copy)" (:name result)))
        (t/is (not= (:id file1) (:id result)))

        ;; Check that the deleted library is not duplicated
        (let [[item :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id result)})]
          (t/is (= 0 (count rows))))

        ;; Check that the new file has no media objects
        (let [[item :as rows] (db/query th/*pool* :file-media-object {:file-id (:id result)})]
          (t/is (= 0 (count rows))))

        ;; Check the total number of files
        (let [rows (db/query th/*pool* :file {:project-id (:id project)})]
          (t/is (= 3 (count rows))))

        ))))

(t/deftest duplicate-project
  (let [storage (:app.storage/storage th/*system*)
        sobject (sto/put-object storage {:content (sto/content "content")
                                         :content-type "text/plain"
                                         :other "data"})
        profile (th/create-profile* 1 {:is-active true})
        project (th/create-project* 1 {:team-id (:default-team-id profile)
                                       :profile-id (:id profile)})
        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project)
                                    :is-shared true})

        libl    (th/link-file-to-library* {:file-id (:id file1)
                                           :library-id (:id file2)})
        mobj    (th/create-file-media-object* {:file-id (:id file1)
                                               :is-local false
                                               :media-id (:id sobject)})]

    (th/update-file*
     {:file-id (:id file1)
      :profile-id (:id profile)
      :changes [{:type :add-media
                 :object (select-keys mobj [:id :width :height :mtype :name])}]})


    (let [data {::th/type :duplicate-project
                :profile-id (:id profile)
                :project-id (:id project)
                :name "project 1 (copy)"}
          out  (th/mutation! data)]

      ;; Check that result is correct
      (t/is (nil? (:error out)))

      (let [result (:result out)]
        ;; Check that they are the same project but different id and name
        (t/is (= "project 1 (copy)" (:name result)))
        (t/is (not= (:id project) (:id result)))

        ;; Check the total number of projects (previously is 2, now is 3)
        (let [rows (db/query th/*pool* :project {:team-id (:default-team-id profile)})]
          (t/is (= 3 (count rows))))

        ;; Check that the new project has the same files
        (let [p1-files (db/query th/*pool* :file
                                 {:project-id (:id project)}
                                 {:order-by [:name]})
              p2-files (db/query th/*pool* :file
                                 {:project-id (:id result)}
                                 {:order-by [:name]})]
          (t/is (= (count p1-files)
                   (count p2-files)))

          ;; check that the both files are equivalent
          (doseq [[fa fb] (map vector p1-files p2-files)]
            (t/is (not= (:id fa) (:id fb)))
            (t/is (= (:name fa) (:name fb)))

            (when (= (:id fa) (:id file1))
              (t/is (false? (b/equals? (:data fa)
                                       (:data fb)))))

            (when (= (:id fa) (:id file2))
              (t/is (false? (b/equals? (:data fa)
                                       (:data fb))))))

          )))))

(t/deftest duplicate-project-with-deleted-files
  (let [storage (:app.storage/storage th/*system*)
        sobject (sto/put-object storage {:content (sto/content "content")
                                         :content-type "text/plain"
                                         :other "data"})
        profile (th/create-profile* 1 {:is-active true})
        project (th/create-project* 1 {:team-id (:default-team-id profile)
                                       :profile-id (:id profile)})
        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project)
                                    :is-shared true})

        libl    (th/link-file-to-library* {:file-id (:id file1)
                                           :library-id (:id file2)})
        mobj    (th/create-file-media-object* {:file-id (:id file1)
                                               :is-local false
                                               :media-id (:id sobject)})]

    (th/update-file*
     {:file-id (:id file1)
      :profile-id (:id profile)
      :changes [{:type :add-media
                 :object (select-keys mobj [:id :width :height :mtype :name])}]})

    (th/mark-file-deleted* {:id (:id file1)})

    (let [data {::th/type :duplicate-project
                :profile-id (:id profile)
                :project-id (:id project)
                :name "project 1 (copy)"}
          out  (th/mutation! data)]

      ;; Check that result is correct
      (t/is (nil? (:error out)))

      (let [result (:result out)]
        ;; Check that they are the same project but different id and name
        (t/is (= "project 1 (copy)" (:name result)))
        (t/is (not= (:id project) (:id result)))

        ;; Check the total number of projects (previously is 2, now is 3)
        (let [rows (db/query th/*pool* :project {:team-id (:default-team-id profile)})]
          (t/is (= 3 (count rows))))

        ;; Check that the new project has only the second file
        (let [p1-files (db/query th/*pool* :file
                                 {:project-id (:id project)}
                                 {:order-by [:name]})
              p2-files (db/query th/*pool* :file
                                 {:project-id (:id result)}
                                 {:order-by [:name]})]
          (t/is (= (count (rest p1-files))
                   (count p2-files)))

          ;; check that the both files are equivalent
          (doseq [[fa fb] (map vector (rest p1-files) p2-files)]
            (t/is (not= (:id fa) (:id fb)))
            (t/is (= (:name fa) (:name fb)))

            (when (= (:id fa) (:id file1))
              (t/is (false? (b/equals? (:data fa)
                                       (:data fb)))))

            (when (= (:id fa) (:id file2))
              (t/is (false? (b/equals? (:data fa)
                                       (:data fb))))))

          )))))

(t/deftest move-file-on-same-team
  (let [profile  (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile)})

        project1 (th/create-project* 1 {:team-id (:default-team-id profile)
                                        :profile-id (:id profile)})

        project2 (th/create-project* 2 {:team-id (:default-team-id profile)
                                        :profile-id (:id profile)})

        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project1)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project1)
                                    :is-shared true})]

    (th/link-file-to-library* {:file-id (:id file1)
                               :library-id (:id file2)})

    ;; Try to move to same project
    (let [data  {::th/type :move-files
                 :profile-id (:id profile)
                 :project-id (:id project1)
                 :ids #{(:id file1)}}

          out   (th/mutation! data)
          error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :cant-move-to-same-project)))

    ;; initially project1 should have 2 files
    (let [rows (db/query th/*pool* :file {:project-id (:id project1)})]
      (t/is (= 2 (count rows))))

    ;; initially project2 should be empty
    (let [rows (db/query th/*pool* :file {:project-id (:id project2)})]
      (t/is (= 0 (count rows))))

    ;; move a file1 to project2 (in the same team)
    (let [data {::th/type :move-files
                :profile-id (:id profile)
                :project-id (:id project2)
                :ids #{(:id file1)}}

          out  (th/mutation! data)]

      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      ;; project1 now should contain 1 file
      (let [rows (db/query th/*pool* :file {:project-id (:id project1)})]
        (t/is (= 1 (count rows))))

      ;; project2 now should contain 1 file
      (let [rows (db/query th/*pool* :file {:project-id (:id project2)})]
        (t/is (= 1 (count rows))))

      ;; file1 should be still linked to file2
      (let [[item :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id file1)})]
        (t/is (= 1 (count rows)))
        (t/is (= (:file-id item) (:id file1)))
        (t/is (= (:library-file-id item) (:id file2))))

      ;; should be no libraries on file2
      (let [rows (db/query th/*pool* :file-library-rel {:file-id (:id file2)})]
        (t/is (= 0 (count rows))))
      )))


;; TODO: move a library to other team
(t/deftest move-file-to-other-team
  (let [profile  (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile)})

        project1 (th/create-project* 1 {:team-id (:default-team-id profile)
                                        :profile-id (:id profile)})

        project2 (th/create-project* 2 {:team-id (:id team)
                                        :profile-id (:id profile)})

        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project1)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project1)
                                    :is-shared true})
        file3   (th/create-file* 3 {:profile-id (:id profile)
                                    :project-id (:id project1)
                                    :is-shared true})]

    (th/link-file-to-library* {:file-id (:id file1)
                               :library-id (:id file2)})

    (th/link-file-to-library* {:file-id (:id file2)
                               :library-id (:id file3)})

    ;; --- initial data checks

    ;; the project1 should have 3 files
    (let [rows (db/query th/*pool* :file {:project-id (:id project1)})]
      (t/is (= 3 (count rows))))

    ;; should be no files on project2
    (let [rows (db/query th/*pool* :file {:project-id (:id project2)})]
      (t/is (= 0 (count rows))))

    ;; the file1 should be linked to file2
    (let [[item :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id file1)})]
      (t/is (= 1 (count rows)))
      (t/is (= (:file-id item) (:id file1)))
      (t/is (= (:library-file-id item) (:id file2))))

    ;; the file2 should be linked to file3
    (let [[item :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id file2)})]
      (t/is (= 1 (count rows)))
      (t/is (= (:file-id item) (:id file2)))
      (t/is (= (:library-file-id item) (:id file3))))

    ;; should be no libraries on file3
    (let [rows (db/query th/*pool* :file-library-rel {:file-id (:id file3)})]
      (t/is (= 0 (count rows))))

    ;; move to other project in other team
    (let [data {::th/type :move-files
                :profile-id (:id profile)
                :project-id (:id project2)
                :ids #{(:id file1)}}
          out  (th/mutation! data)]

      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      ;; project1 now should have 2 file
      (let [[item1 item2 :as rows] (db/query th/*pool* :file {:project-id (:id project1)}
                                      {:order-by [:created-at]})]
        ;; (clojure.pprint/pprint rows)
        (t/is (= 2 (count rows)))
        (t/is (= (:id item1) (:id file2))))

      ;; project2 now should have 1 file
      (let [[item :as rows] (db/query th/*pool* :file {:project-id (:id project2)})]
        (t/is (= 1 (count rows)))
        (t/is (= (:id item) (:id file1))))

      ;; the moved file1 should not have any link to libraries
      (let [rows (db/query th/*pool* :file-library-rel {:file-id (:id file1)})]
        (t/is (zero? (count rows))))

      ;; the file2 should still be linked to file3
      (let [[item :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id file2)})]
        (t/is (= 1 (count rows)))
        (t/is (= (:file-id item) (:id file2)))
        (t/is (= (:library-file-id item) (:id file3))))
      )))


(t/deftest move-library-to-other-team
  (let [profile  (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile)})

        project1 (th/create-project* 1 {:team-id (:default-team-id profile)
                                        :profile-id (:id profile)})

        project2 (th/create-project* 2 {:team-id (:id team)
                                        :profile-id (:id profile)})

        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project1)})
        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project1)
                                    :is-shared true})]

    (th/link-file-to-library* {:file-id (:id file1)
                               :library-id (:id file2)})

    ;; --- initial data checks

    ;; the project1 should have 2 files
    (let [rows (db/query th/*pool* :file {:project-id (:id project1)})]
      (t/is (= 2 (count rows))))

    ;; should be no files on project2
    (let [rows (db/query th/*pool* :file {:project-id (:id project2)})]
      (t/is (= 0 (count rows))))

    ;; the file1 should be linked to file2
    (let [[item :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id file1)})]
      (t/is (= 1 (count rows)))
      (t/is (= (:file-id item) (:id file1)))
      (t/is (= (:library-file-id item) (:id file2))))

    ;; should be no libraries on file2
    (let [rows (db/query th/*pool* :file-library-rel {:file-id (:id file2)})]
      (t/is (= 0 (count rows))))

    ;; move the library to other project
    (let [data {::th/type :move-files
                :profile-id (:id profile)
                :project-id (:id project2)
                :ids #{(:id file2)}}
          out  (th/mutation! data)]

      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      ;; project1 now should have 1 file
      (let [[item :as rows] (db/query th/*pool* :file {:project-id (:id project1)}
                                      {:order-by [:created-at]})]
        (t/is (= 1 (count rows)))
        (t/is (= (:id item) (:id file1))))

      ;; project2 now should have 1 file
      (let [[item :as rows] (db/query th/*pool* :file {:project-id (:id project2)})]
        (t/is (= 1 (count rows)))
        (t/is (= (:id item) (:id file2))))

      ;; the file1 should not have any link to libraries
      (let [rows (db/query th/*pool* :file-library-rel {:file-id (:id file1)})]
        (t/is (zero? (count rows))))

      ;; the file2 should not have any link to libraries
      (let [rows (db/query th/*pool* :file-library-rel {:file-id (:id file2)})]
        (t/is (zero? (count rows))))

      )))

(t/deftest move-project
  (let [profile  (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile)})

        project1 (th/create-project* 1 {:team-id (:default-team-id profile)
                                        :profile-id (:id profile)})

        project2 (th/create-project* 2 {:team-id (:default-team-id profile)
                                        :profile-id (:id profile)})

        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project1)})

        file2   (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project1)
                                    :is-shared true})

        file3   (th/create-file* 3 {:profile-id (:id profile)
                                    :project-id (:id project2)
                                    :is-shared true})]

    (th/link-file-to-library* {:file-id (:id file1)
                               :library-id (:id file2)})

    (th/link-file-to-library* {:file-id (:id file1)
                               :library-id (:id file3)})

    ;; --- initial data checks

    ;; the project1 should have 2 files
    (let [rows (db/query th/*pool* :file {:project-id (:id project1)})]
      (t/is (= 2 (count rows))))

    ;; the project2 should have 1 file
    (let [rows (db/query th/*pool* :file {:project-id (:id project2)})]
      (t/is (= 1 (count rows))))

    ;; the file1 should be linked to file2 and file3
    (let [[item1 item2 :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id file1)}
                                           {:order-by [:created-at]})]
      (t/is (= 2 (count rows)))
      (t/is (= (:file-id item1) (:id file1)))
      (t/is (= (:library-file-id item1) (:id file2)))
      (t/is (= (:file-id item2) (:id file1)))
      (t/is (= (:library-file-id item2) (:id file3))))

    ;; the file2 should not be linked to any file
    (let [[rows] (db/query th/*pool* :file-library-rel {:file-id (:id file2)})]
      (t/is (= 0 (count rows))))

    ;; the file3 should not be linked to any file
    (let [[rows] (db/query th/*pool* :file-library-rel {:file-id (:id file3)})]
      (t/is (= 0 (count rows))))

    ;; move project1 to other team
    ;; TODO: correct team change of project
    (let [data {::th/type :move-project
                :profile-id (:id profile)
                :project-id (:id project1)
                :team-id (:id team)}
          out  (th/mutation! data)]

      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      ;; project1 now should still have 2 files
      (let [[item1 item2 :as rows] (db/query th/*pool* :file {:project-id (:id project1)}
                                             {:order-by [:created-at]})]
        ;; (clojure.pprint/pprint rows)
        (t/is (= 2 (count rows)))
        (t/is (= (:id item1) (:id file1)))
        (t/is (= (:id item2) (:id file2))))

      ;; project2 now should still have 1 file
      (let [[item :as rows] (db/query th/*pool* :file {:project-id (:id project2)})]
        (t/is (= 1 (count rows)))
        (t/is (= (:id item) (:id file3))))

      ;; the file1 should be linked to file2 but not file3
      (let [[item1 :as rows] (db/query th/*pool* :file-library-rel {:file-id (:id file1)}
                                       {:order-by [:created-at]})]
        (t/is (= 1 (count rows)))
        (t/is (= (:file-id item1) (:id file1)))
        (t/is (= (:library-file-id item1) (:id file2))))

      )))



