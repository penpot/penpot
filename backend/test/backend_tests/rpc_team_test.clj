;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-team-test
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest create-team-invitations
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})
          profile3 (th/create-profile* 3 {:is-active true :is-muted true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})

          pool     (:app.db/pool th/*system*)
          data     {::th/type :create-team-invitations
                    ::rpc/profile-id (:id profile1)
                    :team-id (:id team)
                    :role :editor}]

      ;; invite external user without complaints
      (let [data       (assoc data :emails ["foo@bar.com"])
            out        (th/command! data)
            ;; retrieve the value from the database and check its content
            invitation (db/exec-one!
                        th/*pool*
                        ["select count(*) as num from team_invitation where team_id = ? and email_to = ?"
                         (:team-id data) "foo@bar.com"])]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (= 1 (:call-count (deref mock))))
        (t/is (= 1 (:num invitation))))

      ;; invite internal user without complaints
      (th/reset-mock! mock)
      (let [data (assoc data :emails [(:email profile2)])
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; invite user with complaint
      (th/create-global-complaint-for pool {:type :complaint :email "foo@bar.com"})
      (th/reset-mock! mock)
      (let [data (assoc data :emails ["foo@bar.com"])
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; get invitation token
      (let [params {::th/type :get-team-invitation-token
                    ::rpc/profile-id (:id profile1)
                    :team-id (:id team)
                    :email "foo@bar.com"}
            out    (th/command! params)]
        (t/is (th/success? out))
        (let [result (:result out)]
          (contains? result :token)))

      ;; invite user with bounce
      (th/reset-mock! mock)

      (th/create-global-complaint-for pool {:type :bounce :email "foo@bar.com"})
      (let [data  (assoc data :emails ["foo@bar.com"])
            out   (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= 0 (:call-count @mock)))

        (let [edata (-> out :error ex-data)]
          (t/is (= :validation (:type edata)))
          (t/is (= :email-has-permanent-bounces (:code edata)))))

      ;; invite internal user that is muted
      (th/reset-mock! mock)

      (let [data  (assoc data :emails [(:email profile3)])
            out   (th/command! data)]

        (t/is (not (th/success? out)))
        (t/is (= 0 (:call-count @mock)))

        (let [edata (-> out :error ex-data)]
          (t/is (= :validation (:type edata)))
          (t/is (= :member-is-muted (:code edata))))))))


(t/deftest invitation-tokens
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})

          sprops   (:app.setup/props th/*system*)
          pool     (:app.db/pool th/*system*)]

      ;; Try to invite a not existing user
      (let [data {::th/type :create-team-invitations
                  ::rpc/profile-id (:id profile1)
                  :emails ["notexisting@example.com"]
                  :team-id (:id team)
                  :role :editor}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))
        (t/is (= 1 (-> out :result :total)))

        (let [token  (-> out :result :invitations first)
              claims (tokens/decode sprops token)]
          (t/is (= :team-invitation (:iss claims)))
          (t/is (= (:id profile1) (:profile-id claims)))
          (t/is (= :editor (:role claims)))
          (t/is (= (:id team) (:team-id claims)))
          (t/is (= (first (:emails data)) (:member-email claims)))
          (t/is (nil? (:member-id claims)))))

      (th/reset-mock! mock)

      ;; Try to invite existing user
      (let [data {::th/type :create-team-invitations
                  ::rpc/profile-id (:id profile1)
                  :emails [(:email profile2)]
                  :team-id (:id team)
                  :role :editor}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))
        (t/is (= 1 (-> out :result :total)))

        (let [token (-> out :result :invitations first)
              claims (tokens/decode sprops token)]
          (t/is (= :team-invitation (:iss claims)))
          (t/is (= (:id profile1) (:profile-id claims)))
          (t/is (= :editor (:role claims)))
          (t/is (= (:id team) (:team-id claims)))
          (t/is (= (first (:emails data)) (:member-email claims)))
          (t/is (= (:id profile2) (:member-id claims))))))))


(t/deftest accept-invitation-tokens
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        profile3 (th/create-profile* 3 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id profile1)})

        sprops   (:app.setup/props th/*system*)
        pool     (:app.db/pool th/*system*)]

    (let [token (tokens/generate sprops
                                 {:iss :team-invitation
                                  :exp (dt/in-future "1h")
                                  :profile-id (:id profile1)
                                  :role :editor
                                  :team-id (:id team)
                                  :member-email (:email profile2)
                                  :member-id (:id profile2)})]

      (t/testing "Verify token as anonymous user"
        (db/insert! pool :team-invitation
                    {:team-id (:id team)
                     :email-to (:email profile2)
                     :role "editor"
                     :valid-until (dt/in-future "48h")})

        (let [data {::th/type :verify-token :token token}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (th/success? out))

          (let [result (:result out)]
            (t/is (contains? result :invitation-token))
            (t/is (contains? result :iss))
            (t/is (contains? result :redirect-to))
            (t/is (contains? result :state))

            (t/is (= :pending (:state result)))
            (t/is (= :auth-login (:redirect-to result))))

          (let [rows (db/query pool :team-profile-rel {:team-id (:id team)})]
            (t/is (= 1 (count rows))))))

      ;; Clean members
      (db/delete! pool :team-profile-rel
                  {:team-id (:id team)
                   :profile-id (:id profile2)})


      (t/testing "Verify token as logged-in user"
        (let [data {::th/type :verify-token
                    ::rpc/profile-id (:id profile2)
                    :token token}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (th/success? out))
          (let [result (:result out)]
            (t/is (= :created (:state result)))
            (t/is (= (:email profile2) (:member-email result)))
            (t/is (= (:id profile2) (:member-id result))))

          (let [rows (db/query pool :team-profile-rel {:team-id (:id team)})]
            (t/is (= 2 (count rows))))))

      (t/testing "Verify token as logged-in wrong user"
        (db/insert! pool :team-invitation
                    {:team-id (:id team)
                     :email-to (:email profile3)
                     :role "editor"
                     :valid-until (dt/in-future "48h")})

        (let [data {::th/type :verify-token
                    ::rpc/profile-id (:id profile1)
                    :token token}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (not (th/success? out)))
          (let [edata (-> out :error ex-data)]
            (t/is (= :validation (:type edata)))
            (t/is (= :invalid-token (:code edata)))))))))

(t/deftest create-team-invitations-with-email-verification-disabled
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})
          profile3 (th/create-profile* 3 {:is-active true :is-muted true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})

          pool     (:app.db/pool th/*system*)
          data     {::th/type :create-team-invitations
                    ::rpc/profile-id (:id profile1)
                    :team-id (:id team)
                    :role :editor}]

      ;; invite internal user without complaints
      (with-redefs [app.config/flags #{}]
        (th/reset-mock! mock)
        (let [data (assoc data :emails [(:email profile2)])
              out  (th/command! data)]
          (t/is (th/success? out))
          (t/is (= 0 (:call-count (deref mock)))))

        (let [members (db/query pool :team-profile-rel
                                {:team-id (:id team)
                                 :profile-id (:id profile2)})]
          (t/is (= 1 (count members)))
          (t/is (true? (-> members first :can-edit))))))))

(t/deftest query-team-invitations
  (let [prof (th/create-profile* 1 {:is-active true})
        team (th/create-team* 1 {:profile-id (:id prof)})
        data {::th/type :get-team-invitations
              ::rpc/profile-id (:id prof)
              :team-id (:id team)}]

    ;; insert an entry on the database with an enabled invitation
    (db/insert! th/*pool* :team-invitation
                {:team-id (:team-id data)
                 :email-to "test1@mail.com"
                 :role "editor"
                 :valid-until (dt/in-future "48h")})

    ;; insert an entry on the database with an expired invitation
    (db/insert! th/*pool* :team-invitation
                {:team-id (:team-id data)
                 :email-to "test2@mail.com"
                 :role "editor"
                 :valid-until (dt/in-past "48h")})

    (let [out (th/command! data)]
      (t/is (th/success? out))
      (let [result (:result out)
            one    (first result)
            two    (second result)]
        (t/is (= 2 (count result)))
        (t/is (= "test1@mail.com" (:email one)))
        (t/is (= "test2@mail.com" (:email two)))
        (t/is (false? (:expired one)))
        (t/is (true? (:expired two)))))))

(t/deftest update-team-invitation-role
  (let [prof (th/create-profile* 1 {:is-active true})
        team (th/create-team* 1 {:profile-id (:id prof)})
        data {::th/type :update-team-invitation-role
              ::rpc/profile-id (:id prof)
              :team-id (:id team)
              :email "TEST1@mail.com"
              :role :admin}]

    ;; insert an entry on the database with an invitation
    (db/insert! th/*pool* :team-invitation
                {:team-id (:team-id data)
                 :email-to "test1@mail.com"
                 :role "editor"
                 :valid-until (dt/in-future "48h")})

    (let [out (th/command! data)
          ;; retrieve the value from the database and check its content
          res (db/get* th/*pool* :team-invitation
                       {:team-id (:team-id data) :email-to "test1@mail.com"})]
      (t/is (th/success? out))
      (t/is (nil? (:result out)))
      (t/is (= "admin" (:role res))))))

(t/deftest delete-team-invitation
  (let [prof (th/create-profile* 1 {:is-active true})
        team (th/create-team* 1 {:profile-id (:id prof)})
        data {::th/type :delete-team-invitation
              ::rpc/profile-id (:id prof)
              :team-id (:id team)
              :email "TEST1@mail.com"}]

    ;; insert an entry on the database with an invitation
    (db/insert! th/*pool* :team-invitation
                {:team-id (:team-id data)
                 :email-to "test1@mail.com"
                 :role "editor"
                 :valid-until (dt/in-future "48h")})

    (let [out (th/command! data)
          ;; retrieve the value from the database and check its content
          res (db/get* th/*pool* :team-invitation
                       {:team-id (:team-id data) :email-to "test1@mail.com"})]

      (t/is (th/success? out))
      (t/is (nil? (:result out)))
      (t/is (nil? res)))))


(t/deftest team-deletion-1
  (let [profile1 (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile1)})
        pool     (:app.db/pool th/*system*)
        data     {::th/type :delete-team
                  ::rpc/profile-id (:id profile1)
                  :team-id (:id team)}]

    ;; team is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration 0)})]
      (t/is (= 0 (:processed result))))

    ;; query the list of teams
    (let [data {::th/type :get-teams
                ::rpc/profile-id (:id profile1)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (th/success? out))
      (let [result (:result out)]
        (t/is (= 2 (count result)))
        (t/is (= (:id team) (get-in result [1 :id])))
        (t/is (= (:default-team-id profile1) (get-in result [0 :id])))))

    ;; Request team to be deleted
    (let [params {::th/type :delete-team
                  ::rpc/profile-id (:id profile1)
                  :id (:id team)}
          out    (th/command! params)]
      (t/is (th/success? out)))

    ;; query the list of teams after soft deletion
    (let [data {::th/type :get-teams
                ::rpc/profile-id (:id profile1)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (th/success? out))
      (let [result (:result out)]
        (t/is (= 1 (count result)))
        (t/is (= (:default-team-id profile1) (get-in result [0 :id])))))

    (th/run-pending-tasks!)

    ;; run permanent deletion (should be noop)
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration {:minutes 1})})]
      (t/is (= 0 (:processed result))))

    ;; query the list of projects after hard deletion
    (let [data {::th/type :get-projects
                ::rpc/profile-id (:id profile1)
                :team-id (:id team)}
          out  (th/command! data)]
      ;; (th/print-result! out)
      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :not-found (:type edata)))))

    ;; run permanent deletion
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration 0)})]
      (t/is (= 2 (:processed result))))

    ;; query the list of projects of a after hard deletion
    (let [data {::th/type :get-projects
                ::rpc/profile-id (:id profile1)
                :team-id (:id team)}
          out  (th/command! data)]
      ;; (th/print-result! out)

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :not-found (:type edata)))))))


(t/deftest team-deletion-2
  (let [storage (-> (:app.storage/storage th/*system*)
                    (assoc ::sto/backend :assets-fs))
        prof    (th/create-profile* 1)

        team     (th/create-team* 1 {:profile-id (:id prof)})

        proj    (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:id team)})
        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id team)
                                    :is-shared false})

        mfile   {:filename "sample.jpg"
                 :path (th/tempfile "backend_tests/test_files/sample.jpg")
                 :mtype "image/jpeg"
                 :size 312043}]


    (let [params {::th/type :upload-file-media-object
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :is-local true
                  :name "testfile"
                  :content mfile}

          out      (th/command! params)]
      (t/is (nil? (:error out))))

    (let [params {::th/type :delete-team
                  ::rpc/profile-id (:id prof)
                  :id (:id team)}
          out      (th/command! params)]
      #_(th/print-result! out)
      (t/is (nil? (:error out))))

    (th/run-pending-tasks!)

    (let [rows (th/db-exec! ["select * from team where id = ?" (:id team)])]
      (t/is (= 1 (count rows)))
      (t/is (dt/instant? (:deleted-at (first rows)))))

    (let [result (th/run-task! :objects-gc {:min-age 0})]
      (t/is (= 5 (:processed result))))))
