;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.services-teams-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [app.test-helpers :as th]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [clojure.test :as t]
   [datoteka.core :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest invite-team-member
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})
          profile3 (th/create-profile* 3 {:is-active true :is-muted true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})

          pool     (:app.db/pool th/*system*)
          data     {::th/type :invite-team-member
                    :team-id (:id team)
                    :role :editor
                    :profile-id (:id profile1)}]

      ;; invite external user without complaints
      (let [data       (assoc data :email "foo@bar.com")
            out        (th/mutation! data)
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
      (let [data (assoc data :email (:email profile2))
            out  (th/mutation! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; invite user with complaint
      (th/create-global-complaint-for pool {:type :complaint :email "foo@bar.com"})
      (th/reset-mock! mock)
      (let [data (assoc data :email "foo@bar.com")
            out  (th/mutation! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; invite user with bounce
      (th/reset-mock! mock)

      (th/create-global-complaint-for pool {:type :bounce :email "foo@bar.com"})
      (let [data  (assoc data :email "foo@bar.com")
            out   (th/mutation! data)]

        (t/is (not (th/success? out)))
        (t/is (= 0 (:call-count @mock)))

        (let [edata (-> out :error ex-data)]
          (t/is (= :validation (:type edata)))
          (t/is (= :email-has-permanent-bounces (:code edata)))))

      ;; invite internal user that is muted
      (th/reset-mock! mock)

      (let [data  (assoc data :email (:email profile3))
            out   (th/mutation! data)]

        (t/is (not (th/success? out)))
        (t/is (= 0 (:call-count @mock)))

        (let [edata (-> out :error ex-data)]
          (t/is (= :validation (:type edata)))
          (t/is (= :member-is-muted (:code edata)))))

      )))


(t/deftest invitation-tokens
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})

          sprops   (:app.setup/props th/*system*)
          pool     (:app.db/pool th/*system*)]

      ;; Try to invite a not existing user
      (let [data {::th/type :invite-team-member
                  :email "notexisting@example.com"
                  :team-id (:id team)
                  :role :editor
                  :profile-id (:id profile1)}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))
        (t/is (= 1 (-> out :result count)))

        (let [token (-> out :result first)
              claims (tokens/decode sprops token)]
          (t/is (= :team-invitation (:iss claims)))
          (t/is (= (:id profile1) (:profile-id claims)))
          (t/is (= :editor (:role claims)))
          (t/is (= (:id team) (:team-id claims)))
          (t/is (= (:email data) (:member-email claims)))
          (t/is (nil? (:member-id claims)))))

      (th/reset-mock! mock)

      ;; Try to invite existing user
      (let [data {::th/type :invite-team-member
                  :email (:email profile2)
                  :team-id (:id team)
                  :role :editor
                  :profile-id (:id profile1)}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))
        (t/is (= 1 (-> out :result count)))

        (let [token (-> out :result first)
              claims (tokens/decode sprops token)]
          (t/is (= :team-invitation (:iss claims)))
          (t/is (= (:id profile1) (:profile-id claims)))
          (t/is (= :editor (:role claims)))
          (t/is (= (:id team) (:team-id claims)))
          (t/is (= (:email data) (:member-email claims)))
          (t/is (= (:id profile2) (:member-id claims)))))

      )))


(t/deftest accept-invitation-tokens
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})

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

      ;; --- Verify token as anonymous user

      (db/insert! pool :team-invitation
                  {:team-id (:id team)
                   :email-to (:email profile2)
                   :role "editor"
                   :valid-until (dt/in-future "48h")})

      (let [data {::th/type :verify-token :token token}
            out  (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (th/success? out))
        (let [result (:result out)]
          (t/is (= :created (:state result)))
          (t/is (= (:email profile2) (:member-email result)))
          (t/is (= (:id profile2) (:member-id result))))

        (let [rows (db/query pool :team-profile-rel {:team-id (:id team)})]
          (t/is (= 2 (count rows)))))

      ;; Clean members
      (db/delete! pool :team-profile-rel
                  {:team-id (:id team)
                   :profile-id (:id profile2)})


      ;; --- Verify token as logged-in user

      (db/insert! pool :team-invitation
                  {:team-id (:id team)
                   :email-to (:email profile2)
                   :role "editor"
                   :valid-until (dt/in-future "48h")})

      (let [data {::th/type :verify-token :token token :profile-id (:id profile2)}
            out  (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (th/success? out))
        (let [result (:result out)]
          (t/is (= :created (:state result)))
          (t/is (= (:email profile2) (:member-email result)))
          (t/is (= (:id profile2) (:member-id result))))

        (let [rows (db/query pool :team-profile-rel {:team-id (:id team)})]
          (t/is (= 2 (count rows)))))


      ;; --- Verify token as logged-in wrong user

      (db/insert! pool :team-invitation
                  {:team-id (:id team)
                   :email-to (:email profile2)
                   :role "editor"
                   :valid-until (dt/in-future "48h")})

      (let [data {::th/type :verify-token :token token :profile-id (:id profile1)}
            out  (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (not (th/success? out)))
        (let [edata (-> out :error ex-data)]
          (t/is (= :validation (:type edata)))
          (t/is (= :invalid-token (:code edata)))))

      )))



(t/deftest invite-team-member-with-email-verification-disabled
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})
          profile3 (th/create-profile* 3 {:is-active true :is-muted true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})

          pool     (:app.db/pool th/*system*)
          data     {::th/type :invite-team-member
                    :team-id (:id team)
                    :role :editor
                    :profile-id (:id profile1)}]

      ;; invite internal user without complaints
      (with-redefs [app.config/flags #{}]
        (th/reset-mock! mock)
        (let [data (assoc data :email (:email profile2))
              out  (th/mutation! data)]
          (t/is (th/success? out))
          (t/is (= 0 (:call-count (deref mock)))))

        (let [members (db/query pool :team-profile-rel
                                {:team-id (:id team)
                                 :profile-id (:id profile2)})]
          (t/is (= 1 (count members)))
          (t/is (true? (-> members first :can-edit))))))))

(t/deftest team-deletion
  (let [profile1 (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile1)})
        pool     (:app.db/pool th/*system*)
        data     {::th/type :delete-team
                  :team-id (:id team)
                  :profile-id (:id profile1)}]

    ;; team is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration 0)})]
      (t/is (= 0 (:processed result))))

    ;; query the list of teams
    (let [data {::th/type :teams
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (th/success? out))
      (let [result (:result out)]
        (t/is (= 2 (count result)))
        (t/is (= (:id team) (get-in result [1 :id])))
        (t/is (= (:default-team-id profile1) (get-in result [0 :id])))))

    ;; Request team to be deleted
    (let [params {::th/type :delete-team
                  :id (:id team)
                  :profile-id (:id profile1)}
          out    (th/mutation! params)]
      (t/is (th/success? out)))

    ;; query the list of teams after soft deletion
    (let [data {::th/type :teams
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (th/success? out))
      (let [result (:result out)]
        (t/is (= 1 (count result)))
        (t/is (= (:default-team-id profile1) (get-in result [0 :id])))))

    ;; run permanent deletion (should be noop)
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration {:minutes 1})})]
      (t/is (= 0 (:processed result))))

    ;; query the list of projects after hard deletion
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :not-found (:type edata)))))

    ;; run permanent deletion
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration 0)})]
      (t/is (= 1 (:processed result))))

    ;; query the list of projects of a after hard deletion
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id profile1)}
          out  (th/query! data)]
      ;; (th/print-result! out)

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :not-found (:type edata)))))
    ))

(t/deftest query-team-invitations
  (let [prof (th/create-profile* 1 {:is-active true})
        team (th/create-team* 1 {:profile-id (:id prof)})
        data {::th/type :team-invitations
              :profile-id (:id prof)
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

    (let [out (th/query! data)]
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
              :profile-id (:id prof)
              :team-id (:id team)
              :email "TEST1@mail.com"
              :role :admin}]

    ;; insert an entry on the database with an invitation
    (db/insert! th/*pool* :team-invitation
                {:team-id (:team-id data)
                 :email-to "test1@mail.com"
                 :role "editor"
                 :valid-until (dt/in-future "48h")})

    (let [out (th/mutation! data)
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
              :profile-id (:id prof)
              :team-id (:id team)
              :email "TEST1@mail.com"}]

    ;; insert an entry on the database with an invitation
    (db/insert! th/*pool* :team-invitation
                {:team-id (:team-id data)
                 :email-to "test1@mail.com"
                 :role "editor"
                 :valid-until (dt/in-future "48h")})

    (let [out (th/mutation! data)
          ;; retrieve the value from the database and check its content
          res (db/get* th/*pool* :team-invitation
                       {:team-id (:team-id data) :email-to "test1@mail.com"})]

      (t/is (th/success? out))
      (t/is (nil? (:result out)))
      (t/is (nil? res)))))
