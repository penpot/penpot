;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-quotes-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.cond :as cond]
   [app.rpc.quotes :as-alias quotes]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.core :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest teams-per-profile-quote-by-config
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-teams-per-profile 2})}]
    (let [profile    (th/create-profile* 1)
          data       {::th/type :create-team
                      ::rpc/profile-id (:id profile)}]

      ;; create first team
      (let [data (assoc data :name "test team 1")
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (some? (:result out))))

      ;; create secont team
      (let [data (assoc data :name "test team 2")
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (not (th/success? out)))
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (= :restriction (th/ex-type error)))
          (t/is (= :max-quote-reached (th/ex-code error)))
          (t/is (= ::quotes/teams-per-profile (:target (ex-data error))))))
      )))


(t/deftest teams-per-profile-quote-by-database
  (let [profile    (th/create-profile* 1)
        data       {::th/type :create-team
                    ::rpc/profile-id (:id profile)}]

    ;; Add generic quote for team
    (th/db-insert! :usage-quote
                   {:profile-id (:id profile)
                    :target "team"
                    :quote 2})

    ;; create first team
    (let [data (assoc data :name "test team 1")
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (some? (:result out))))

    ;; create secont team
    (let [data (assoc data :name "test team 2")
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (not (th/success? out)))
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (= :restriction (th/ex-type error)))
        (t/is (= :max-quote-reached (th/ex-code error)))
        (t/is (= ::quotes/teams-per-profile (:target (ex-data error))))))
    ))

(t/deftest projects-per-team-quote-by-config
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-projects-per-team 2})}]

    (let [profile    (th/create-profile* 1)
          team-id    (:default-team-id profile)
          data       {::th/type :create-project
                      :profile-id (:id profile)
                      :team-id team-id}]

      ;; create first project
      (let [data (assoc data :name "test project 1")
            out  (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (some? (:result out))))

      ;; create second project
      (let [data (assoc data :name "test project 2")
            out  (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (not (th/success? out)))
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (= :restriction (th/ex-type error)))
          (t/is (= :max-quote-reached (th/ex-code error)))
          (t/is (= ::quotes/projects-per-team (:target (ex-data error))))))
      )))

(t/deftest projects-per-team-quote-by-database
  (let [profile    (th/create-profile* 1)
        team-id    (:default-team-id profile)
        data       {::th/type :create-project
                    :profile-id (:id profile)
                    :team-id team-id}]

    ;; Add generic quote for team
    (th/db-insert! :usage-quote
                   {:team-id team-id
                    :target "project"
                    :quote 2})

    ;; create first project
    (let [data (assoc data :name "test project 1")
          out  (th/mutation! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (some? (:result out))))

    ;; create second project
    (let [data (assoc data :name "test project 2")
          out  (th/mutation! data)]
      ;; (th/print-result! out)
      (t/is (not (th/success? out)))
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (= :restriction (th/ex-type error)))
        (t/is (= :max-quote-reached (th/ex-code error)))
        (t/is (= ::quotes/projects-per-team (:target (ex-data error))))))

    ;; Insert quote to specific profile
    (th/db-insert! :usage-quote
                   {:profile-id (:id profile)
                    :team-id team-id
                    :target "project"
                    :quote 3})

    ;; create second project
    (let [data (assoc data :name "test project 2")
          out  (th/mutation! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (some? (:result out))))

    ))

(t/deftest invitations-per-team-quote-by-config
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-invitations-per-team 2})}]

    (let [profile    (th/create-profile* 1)
          team-id    (:default-team-id profile)
          data       {::th/type :create-team-invitations
                      ::rpc/profile-id (:id profile)
                      :team-id team-id
                      :role :editor}]

      ;; create invitations
      (let [data (assoc data :emails ["foo1@example.net" "foo2@example.net"])
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (some? (:result out))))

      ;; create invitations
      (let [data (assoc data :emails ["foo3@example.net" "foo4@example.net"])
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (not (th/success? out)))
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (= :restriction (th/ex-type error)))
          (t/is (= :max-quote-reached (th/ex-code error)))
          (t/is (= ::quotes/invitations-per-team (:target (ex-data error))))))
      )))

(t/deftest invitations-per-team-quote-by-database
  (let [profile    (th/create-profile* 1)
        team-id    (:default-team-id profile)
        data       {::th/type :create-team-invitations
                    ::rpc/profile-id (:id profile)
                    :team-id team-id
                    :role :editor}]

    ;; Add generic quote for team
    (th/db-insert! :usage-quote
                   {:team-id team-id
                    :target "team-invitation"
                    :quote 2})

    ;; create invitations
    (let [data (assoc data :emails ["foo1@example.net" "foo2@example.net"])
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (some? (:result out))))

    ;; create invitations
    (let [data (assoc data :emails ["foo3@example.net" "foo4@example.net"])
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (not (th/success? out)))
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (= :restriction (th/ex-type error)))
        (t/is (= :max-quote-reached (th/ex-code error)))
        (t/is (= ::quotes/invitations-per-team (:target (ex-data error))))))
    ))

(t/deftest members-per-team-quote-by-config
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-profiles-per-team 4})}]

    (let [profile    (th/create-profile* 1)
          profile2   (th/create-profile* 2)
          team-id    (:default-team-id profile)
          data       {::th/type :create-team-invitations
                      ::rpc/profile-id (:id profile)
                      :team-id team-id
                      :role :editor}]

      (th/create-team-role* {:team-id team-id
                             :profile-id (:id profile2)
                             :role :admin})
      ;; create members
      (let [data (assoc data :emails ["foo1@example.net" "foo2@example.net"])
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (some? (:result out))))

      ;; create members
      (let [data (assoc data :emails ["foo3@example.net" "foo4@example.net"])
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (not (th/success? out)))
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (= :restriction (th/ex-type error)))
          (t/is (= :max-quote-reached (th/ex-code error)))
          (t/is (= ::quotes/profiles-per-team (:target (ex-data error))))))
      )))

(t/deftest members-per-team-quote-by-database
  (let [profile    (th/create-profile* 1)
        profile2   (th/create-profile* 2)
        team-id    (:default-team-id profile)
        data       {::th/type :create-team-invitations
                    ::rpc/profile-id (:id profile)
                    :team-id team-id
                    :role :editor}]

    (th/create-team-role* {:team-id team-id
                           :profile-id (:id profile2)
                           :role :admin})

    ;; Add generic quote for team
    (th/db-insert! :usage-quote
                   {:team-id team-id
                    :target "team-member"
                    :quote 4})

    ;; create members
    (let [data (assoc data :emails ["foo1@example.net" "foo2@example.net"])
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (some? (:result out))))

    ;; create members
    (let [data (assoc data :emails ["foo3@example.net" "foo4@example.net"])
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (not (th/success? out)))
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (= :restriction (th/ex-type error)))
        (t/is (= :max-quote-reached (th/ex-code error)))
        (t/is (= ::quotes/profiles-per-team (:target (ex-data error))))))
    ))

(t/deftest files-per-team-quote-by-config
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-files-per-team 1})}]

    (let [profile    (th/create-profile* 1)
          team-id    (:default-team-id profile)
          project    (th/create-project* 1 {:profile-id (:id profile)
                                            :team-id (:default-team-id profile)})
          data       {::th/type :create-file
                      ::rpc/profile-id (:id profile)
                      :project-id (:id project)}]

      ;; create first file
      (let [data (assoc data :name "test file 1")
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (some? (:result out))))

      ;; create second file
      (let [data (assoc data :name "test file 2")
            out  (th/command! data)]
        ;; (th/print-result! out)
        (t/is (not (th/success? out)))
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (= :restriction (th/ex-type error)))
          (t/is (= :max-quote-reached (th/ex-code error)))
          (t/is (= ::quotes/files-per-team (:target (ex-data error))))))
      )))

(t/deftest files-per-team-quote-by-database
  (let [profile    (th/create-profile* 1)
        team-id    (:default-team-id profile)
        project    (th/create-project* 1 {:profile-id (:id profile)
                                          :team-id (:default-team-id profile)})
        data       {::th/type :create-file
                    ::rpc/profile-id (:id profile)
                    :project-id (:id project)}]

    ;; Add generic quote for team
    (th/db-insert! :usage-quote
                   {:team-id (:default-team-id profile)
                    :target "file"
                    :quote 1})

    ;; create first file
    (let [data (assoc data :name "test file 1")
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (some? (:result out))))

    ;; create second file
    (let [data (assoc data :name "test file 2")
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (not (th/success? out)))
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (= :restriction (th/ex-type error)))
        (t/is (= :max-quote-reached (th/ex-code error)))
        (t/is (= ::quotes/files-per-team (:target (ex-data error))))))
    ))
