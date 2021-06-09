;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.services-projects-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.test-helpers :as th]
   [app.util.time :as dt]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest projects-simple-crud
  (let [profile    (th/create-profile* 1)
        team       (th/create-team* 1 {:profile-id (:id profile)})
        project-id (uuid/next)]

    ;; create project
    (let [data {::th/type :create-project
                :id project-id
                :profile-id (:id profile)
                :team-id (:id team)
                :name "test project"}
          out  (th/mutation! data)]
        ;; (th/print-result! out)

      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= (:name data) (:name result)))))

    ;; query the list of projects of a team
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id profile)}
          out  (th/query! data)]
      ;; (th/print-result! out)

      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 1 (count result)))
        (t/is project-id (get-in result [0 :id]))
        (t/is (= "test project" (get-in result [0 :name])))))

    ;; query all projects of a user
    (let [data {::th/type :all-projects
                :profile-id (:id profile)}
          out  (th/query! data)]
      ;; (th/print-result! out)

      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 2 (count result)))
        (t/is (not= project-id (get-in result [0 :id])))
        (t/is (= "Drafts" (get-in result [0 :name])))
        (t/is (= "Default" (get-in result [0 :team-name])))
        (t/is (= true (get-in result [0 :is-default-team])))
        (t/is project-id (get-in result [1 :id]))
        (t/is (= "test project" (get-in result [1 :name])))
        (t/is (= "team1" (get-in result [1 :team-name])))
        (t/is (= false (get-in result [1 :is-default-team])))))

    ;; rename project
    (let [data {::th/type :rename-project
                :id project-id
                :name "renamed project"
                :profile-id (:id profile)}
          out  (th/mutation! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    ;; retrieve project
    (let [data {::th/type :project
                :id project-id
                :profile-id (:id profile)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= "renamed project" (:name result)))))

    ;; delete project
    (let [data {::th/type :delete-project
                :id project-id
                :profile-id (:id profile)}
          out  (th/mutation! data)]

        ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    ;; query a list of projects after delete"
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id profile)}
          out (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))
  ))

(t/deftest permissions-checks-create-project
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)

        data     {::th/type :create-project
                  :profile-id (:id profile2)
                  :team-id (:default-team-id profile1)
                  :name "test project"}
        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-rename-project
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)
        project  (th/create-project* 1 {:team-id (:default-team-id profile1)
                                        :profile-id (:id profile1)})
        data     {::th/type :rename-project
                  :id (:id project)
                  :profile-id (:id profile2)
                  :name "foobar"}
        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-delete-project
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)
        project  (th/create-project* 1 {:team-id (:default-team-id profile1)
                                        :profile-id (:id profile1)})
        data     {::th/type :delete-project
                  :id (:id project)
                  :profile-id (:id profile2)}
        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))

(t/deftest permissions-checks-delete-project
  (let [profile1 (th/create-profile* 1)
        profile2 (th/create-profile* 2)
        project  (th/create-project* 1 {:team-id (:default-team-id profile1)
                                        :profile-id (:id profile1)})
        data     {::th/type :update-project-pin
                  :id (:id project)
                  :team-id (:default-team-id profile1)
                  :profile-id (:id profile2)
                  :is-pinned true}

        out      (th/mutation! data)
        error    (:error out)]

    ;; (th/print-result! out)
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :not-found))))


(t/deftest test-deletion
  (let [task     (:app.tasks.objects-gc/handler th/*system*)
        profile1 (th/create-profile* 1)
        project  (th/create-project* 1 {:team-id (:default-team-id profile1)
                                        :profile-id (:id profile1)})]

    ;; project is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (task {:max-age (dt/duration 0)})]
      (t/is (nil? result)))

    ;; query the list of projects
    (let [data {::th/type :projects
                :team-id (:default-team-id profile1)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 2 (count result)))))

    ;; Request project to be deleted
    (let [params {::th/type :delete-project
                  :id (:id project)
                  :profile-id (:id profile1)}
          out    (th/mutation! params)]
      (t/is (nil? (:error out))))

    ;; query the list of projects after soft deletion
    (let [data {::th/type :projects
                :team-id (:default-team-id profile1)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 1 (count result)))))

    ;; run permanent deletion (should be noop)
    (let [result (task {:max-age (dt/duration {:minutes 1})})]
      (t/is (nil? result)))

    ;; query the list of files of a after soft deletion
    (let [data {::th/type :project-files
                :project-id (:id project)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))

    ;; run permanent deletion
    (let [result (task {:max-age (dt/duration 0)})]
      (t/is (nil? result)))

    ;; query the list of files of a after hard deletion
    (let [data {::th/type :project-files
                :project-id (:id project)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))
    ))
