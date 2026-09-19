;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-team-test
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email.blacklist :as email.blacklist]
   [app.http :as http]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.storage :as sto]
   [app.tokens :as tokens]
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
      (let [data        (assoc data :emails ["foo@bar.com"])
            out         (th/command! data)
            ;; retrieve the value from the database and check its content
            invitations (th/db-query :team-invitation
                                     {:team-id (:team-id data)
                                      :email-to "foo@bar.com"})]

        ;; (th/print-result! out)
        (t/is (th/success? out))
        (t/is (= 1 (:call-count (deref mock))))
        (t/is (= 1 (count invitations))))

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
        (t/is (not (th/success? out)))
        (t/is (= 0 (:call-count (deref mock)))))

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
          (t/is (= :restriction (:type edata)))
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

(t/deftest create-and-update-team-invitations-include-organization-props
  (with-mocks [email-mock {:target 'app.email/send! :return nil}
               audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (let [owner      (th/create-profile* 101 {:is-active true})
          invitee    (th/create-profile* 102 {:is-active true})
          organization-team   (th/create-team* 101 {:profile-id (:id owner)})
          plain-team (th/create-team* 102 {:profile-id (:id owner)})
          organization-id     (uuid/random)
          organization         {:id organization-id
                                :name "Acme"
                                :slug "acme"
                                :owner-id (:id owner)
                                :avatar-bg-url "https://example.com/avatar.svg"
                                :permissions {:new-team-members "anyone"}}
          nitrate-call
          (fn [_cfg method params]
            (case method
              :get-team-organization
              (if (= (:team-id params) (:id organization-team))
                {:organization organization :is-your-penpot false}
                {:organization nil :is-your-penpot false})

              :get-organization-members
              [(:id invitee)]

              nil))
          invite!     (fn [team email]
                        (th/command! {::th/type :create-team-invitations
                                      ::rpc/profile-id (:id owner)
                                      :team-id (:id team)
                                      :role :editor
                                      :emails [email]}))]
      (with-redefs [cf/flags (conj cf/flags :admin-console :email-verification)
                    nitrate/call nitrate-call]
        (t/is (th/success? (invite! organization-team (:email invitee))))
        (t/is (th/success? (invite! organization-team (:email invitee))))
        (t/is (th/success? (invite! plain-team "external@example.com"))))

      (let [events       (mapv second (:call-args-list @audit-mock))
            create-organization   (first (filter #(and (= "create-team-invitation" (:name %))
                                                       (= (:email invitee)
                                                          (get-in % [:props :member-email])))
                                                 events))
            update-organization   (first (filter #(= "update-team-invitation" (:name %)) events))
            create-plain (first (filter #(and (= "create-team-invitation" (:name %))
                                              (= "external@example.com"
                                                 (get-in % [:props :member-email])))
                                        events))]
        (doseq [event [create-organization update-organization]]
          (t/is (= (str (:id owner))
                   (get-in event [:props :user-who-send-invitation])))
          (t/is (true? (get-in event [:props :team-belongs-to-organization])))
          (t/is (true? (get-in event [:props :adds-invitee-to-organization])))
          (t/is (true? (get-in event [:props :invitee-already-organization-member]))))

        (t/is (= (str (:id owner))
                 (get-in create-plain [:props :user-who-send-invitation])))
        (t/is (false? (get-in create-plain [:props :team-belongs-to-organization])))
        (t/is (false? (get-in create-plain [:props :adds-invitee-to-organization])))
        (t/is (false? (get-in create-plain [:props :invitee-already-organization-member])))))))

(t/deftest create-team-invitations-blacklisted-domain
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          team     (th/create-team* 1 {:profile-id (:id profile1)})
          data     {::th/type :create-team-invitations
                    ::rpc/profile-id (:id profile1)
                    :team-id (:id team)
                    :role :editor}]

      ;; invite from a directly blacklisted domain should fail
      (with-redefs [email.blacklist/enabled?  (constantly true)
                    email.blacklist/contains? (fn [_ email]
                                                (clojure.string/ends-with? email "@blacklisted.com"))]
        (let [out (th/command! (assoc data :emails ["user@blacklisted.com"]))]
          (t/is (not (th/success? out)))
          (t/is (= 0 (:call-count @mock)))
          (let [edata (-> out :error ex-data)]
            (t/is (= :restriction (:type edata)))
            (t/is (= :email-domain-is-not-allowed (:code edata))))))

      ;; invite from a subdomain of a blacklisted domain should also fail
      (th/reset-mock! mock)
      (with-redefs [email.blacklist/enabled?  (constantly true)
                    email.blacklist/contains? (fn [_ email]
                                                (clojure.string/ends-with? email "@sub.blacklisted.com"))]
        (let [out (th/command! (assoc data :emails ["user@sub.blacklisted.com"]))]
          (t/is (not (th/success? out)))
          (t/is (= 0 (:call-count @mock)))
          (let [edata (-> out :error ex-data)]
            (t/is (= :restriction (:type edata)))
            (t/is (= :email-domain-is-not-allowed (:code edata))))))

      ;; invite from a non-blacklisted domain should succeed
      (th/reset-mock! mock)
      (with-redefs [email.blacklist/enabled?  (constantly true)
                    email.blacklist/contains? (constantly false)]
        (let [out (th/command! (assoc data :emails ["user@allowed.com"]))]
          (t/is (th/success? out))
          (t/is (= 1 (:call-count @mock))))))))

(t/deftest create-team-invitations-with-request-access
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1  (th/create-profile* 1 {:is-active true})
          requester (th/create-profile* 2 {:is-active true :email "requester@example.com"})

          team      (th/create-team* 1 {:profile-id (:id profile1)})
          proj      (th/create-project* 1 {:profile-id (:id profile1)
                                           :team-id (:id team)})
          file      (th/create-file* 1 {:profile-id (:id profile1)
                                        :project-id (:id proj)})]
      (let [data {::th/type :create-team-access-request
                  ::rpc/profile-id (:id requester)
                  :file-id (:id file)}
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock))))

      (th/reset-mock! mock)

      (let [data {::th/type :create-team-invitations
                  ::rpc/profile-id (:id profile1)
                  :team-id (:id team)
                  :role :editor
                  :emails ["requester@example.com"]}
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))

        ;; Check that request is properly removed
        (let [requests (th/db-query :team-access-request
                                    {:requester-id (:id requester)})]
          (t/is (= 0 (count requests))))

        (let [rows (th/db-query :team-profile-rel {:team-id (:id team)})]
          (t/is (= 2 (count rows))))))))


(t/deftest create-team-invitations-with-request-access-2
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1   (th/create-profile* 1 {:is-active true})
          requester  (th/create-profile* 2 {:is-active true
                                            :email "requester@example.com"})

          team       (th/create-team* 1 {:profile-id (:id profile1)})
          proj       (th/create-project* 1 {:profile-id (:id profile1)
                                            :team-id (:id team)})
          file       (th/create-file* 1 {:profile-id (:id profile1)
                                         :project-id (:id proj)})]

      ;; Create the first access request
      (let [data {::th/type :create-team-access-request
                  ::rpc/profile-id (:id requester)
                  :file-id (:id file)}
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock))))

      (th/reset-mock! mock)

      ;; Proceed to delete the requester user
      (th/db-update! :profile
                     {:deleted-at (ct/in-past "1h")}
                     {:id (:id requester)})

      ;; Create a new profile with the same email
      (let [requester' (th/create-profile* 3 {:is-active true :email "requester@example.com"})]

        ;; Create a request access with new requester
        (let [data {::th/type :create-team-access-request
                    ::rpc/profile-id (:id requester')
                    :file-id (:id file)}
              out  (th/command! data)]
          (t/is (th/success? out))
          (t/is (= 1 (:call-count @mock))))

        (th/reset-mock! mock)

        ;; Create an invitation for the requester email
        (let [data {::th/type :create-team-invitations
                    ::rpc/profile-id (:id profile1)
                    :team-id (:id team)
                    :role :editor
                    :emails ["requester@example.com"]}
              out  (th/command! data)]
          (t/is (th/success? out))
          (t/is (= 1 (:call-count @mock))))

        ;; Check that request is properly removed
        (let [requests (th/db-query :team-access-request
                                    {:requester-id (:id requester')})]
          (t/is (= 0 (count requests))))

        (let [[r1 r2 :as rows] (th/db-query :team-profile-rel
                                            {:team-id (:id team)}
                                            {:order-by [:created-at]})]
          (t/is (= 2 (count rows)))
          (t/is (= (:profile-id r1) (:id profile1)))
          (t/is (= (:profile-id r2) (:id requester'))))))))


(t/deftest invitation-tokens
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})

          team     (th/create-team* 1 {:profile-id (:id profile1)})
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
              claims (tokens/decode th/*system* token)]
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
              claims (tokens/decode th/*system* token)]
          (t/is (= :team-invitation (:iss claims)))
          (t/is (= (:id profile1) (:profile-id claims)))
          (t/is (= :editor (:role claims)))
          (t/is (= (:id team) (:team-id claims)))
          (t/is (= (first (:emails data)) (:member-email claims)))
          (t/is (= (:id profile2) (:member-id claims))))))))


(t/deftest get-team-invitation-token-requires-edition-permissions
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile1)})
        pool     (:app.db/pool th/*system*)]
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id profile2)
                           :role :viewer})
    (db/insert! pool :team-invitation
                {:team-id (:id team)
                 :email-to "victim@example.com"
                 :role "editor"
                 :valid-until (ct/in-future "48h")})
    (let [data {::th/type :get-team-invitation-token
                ::rpc/profile-id (:id profile2)
                :team-id (:id team)
                :email "victim@example.com"}
          out (th/command! data)]
      (t/is (not (th/success? out)))
      (t/is (= :not-found (-> out :error ex-data :type))))))


(t/deftest accept-invitation-tokens
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        profile3 (th/create-profile* 3 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id profile1)})

        pool     (:app.db/pool th/*system*)]

    (let [token (tokens/generate th/*system*
                                 {:iss :team-invitation
                                  :exp (ct/in-future "1h")
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
                     :valid-until (ct/in-future "48h")})

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
                     :valid-until (ct/in-future "48h")})

        (let [data {::th/type :verify-token
                    ::rpc/profile-id (:id profile1)
                    :token token}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (not (th/success? out)))
          (let [edata (-> out :error ex-data)]
            (t/is (= :validation (:type edata)))
            (t/is (= :invalid-token (:code edata)))))))))

(t/deftest accept-organization-invitation-audit-event
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (let [inviter          (th/create-profile* 201 {:is-active true})
          invitee          (th/create-profile* 202 {:is-active true})
          team             (th/create-team* 201 {:profile-id (:id inviter)})
          organization-id  (uuid/random)
          default-team-id  (uuid/random)
          direct-token     (tokens/generate
                            th/*system*
                            {:iss :team-invitation
                             :exp (ct/in-future "1h")
                             :profile-id (:id inviter)
                             :role :editor
                             :organization-id organization-id
                             :member-email (:email invitee)
                             :member-id (:id invitee)})
          team-token       (tokens/generate
                            th/*system*
                            {:iss :team-invitation
                             :exp (ct/in-future "1h")
                             :profile-id (:id inviter)
                             :role :editor
                             :team-id (:id team)
                             :member-email (:email invitee)
                             :member-id (:id invitee)})
          verify!          (fn [token]
                             (th/command! {::th/type :verify-token
                                           ::rpc/profile-id (:id invitee)
                                           :token token}))
          organization-event
          (fn []
            (->> (:call-args-list @audit-mock)
                 (map second)
                 (filter #(= "accept-organization-invitation" (:name %)))
                 first))
          frontend-event     (atom nil)]

      (db/insert! (:app.db/pool th/*system*)
                  :team-invitation
                  {:org-id organization-id
                   :email-to (:email invitee)
                   :created-by (:id inviter)
                   :role "editor"
                   :valid-until (ct/in-future "48h")})

      (with-redefs [cf/flags (conj cf/flags :admin-console)
                    nitrate/call
                    (fn [_cfg method _params]
                      (case method
                        :get-organization-membership {:organization-id organization-id
                                                      :is-member false}
                        :get-organization-members [(:id inviter) (uuid/random) (uuid/random)]
                        nil))
                    teams/initialize-user-in-organization
                    (fn [& _] default-team-id)]
        (let [out (verify! direct-token)]
          (t/is (th/success? out))
          (reset! frontend-event
                  (get-in out [:result :organization-invitation-audit]))))

      (let [event (organization-event)]
        (t/is (= organization-id (get-in event [:props :organization-id])))
        (t/is (= (:id invitee) (get-in event [:props :user-id])))
        (t/is (= (:id inviter)
                 (get-in event [:props :user-who-send-invitation])))
        (t/is (not (contains? (:props event) :organization-member-add-source)))
        (t/is (not (contains? (:props event) :belongs-to-team-on-add)))
        (t/is (not (contains? (:props event) :organization-member-count-before)))
        (t/is (= :editor (get-in event [:props :role])))
        (t/is (uuid? (get-in event [:props :invitation-id])))
        (t/is (= "organization-invitation-acceptance"
                 (:origin @frontend-event)))
        (t/is (= organization-id
                 (get-in @frontend-event [:props :organization-id])))
        (t/is (= (:id invitee)
                 (get-in @frontend-event [:props :user-id])))
        (t/is (= (:id inviter)
                 (get-in @frontend-event [:props :user-who-send-invitation])))
        (t/is (= "direct-organization-invitation"
                 (get-in @frontend-event [:props :organization-member-add-source])))
        (t/is (false? (get-in @frontend-event [:props :belongs-to-team-on-add])))
        (t/is (= 3
                 (get-in @frontend-event [:props :organization-member-count-before])))
        (t/is (not-any? #(contains? #{"accept-team-invitation"
                                      "accept-team-invitation-from"}
                                    (:name (second %)))
                        (:call-args-list @audit-mock))))

      (th/reset-mock! audit-mock)
      (db/insert! (:app.db/pool th/*system*)
                  :team-invitation
                  {:team-id (:id team)
                   :email-to (:email invitee)
                   :created-by (:id inviter)
                   :role "editor"
                   :valid-until (ct/in-future "48h")})

      (with-redefs [cf/flags (conj cf/flags :admin-console)
                    nitrate/call
                    (fn [_cfg method _params]
                      (case method
                        :get-organization-membership-by-team {:organization-id organization-id
                                                              :is-member false}
                        :get-organization-members (into [(:id inviter)]
                                                        (repeatedly 4 uuid/random))
                        nil))
                    teams/add-profile-to-team! (fn [& _] nil)]
        (let [out (verify! team-token)]
          (t/is (th/success? out))
          (reset! frontend-event
                  (get-in out [:result :organization-invitation-audit]))))

      (let [events (mapv second (:call-args-list @audit-mock))
            event  (organization-event)]
        (t/is (some #(= "accept-team-invitation" (:name %)) events))
        (t/is (some #(= "accept-team-invitation-from" (:name %)) events))
        (t/is (= (:id team) (get-in event [:props :team-id])))
        (t/is (= organization-id (get-in event [:props :organization-id])))
        (t/is (= (:id invitee) (get-in event [:props :user-id])))
        (t/is (= (:id inviter)
                 (get-in event [:props :user-who-send-invitation])))
        (t/is (not (contains? (:props event) :organization-member-add-source)))
        (t/is (not (contains? (:props event) :belongs-to-team-on-add)))
        (t/is (not (contains? (:props event) :organization-member-count-before)))
        (t/is (= "team-invitation-acceptance"
                 (:origin @frontend-event)))
        (t/is (= (:id team) (get-in @frontend-event [:props :team-id])))
        (t/is (= organization-id
                 (get-in @frontend-event [:props :organization-id])))
        (t/is (= (:id invitee)
                 (get-in @frontend-event [:props :user-id])))
        (t/is (= (:id inviter)
                 (get-in @frontend-event [:props :user-who-send-invitation])))
        (t/is (= "team-invitation"
                 (get-in @frontend-event [:props :organization-member-add-source])))
        (t/is (true? (get-in @frontend-event [:props :belongs-to-team-on-add])))
        (t/is (= 5
                 (get-in @frontend-event [:props :organization-member-count-before]))))

      (th/reset-mock! audit-mock)
      (db/insert! (:app.db/pool th/*system*)
                  :team-invitation
                  {:team-id (:id team)
                   :email-to (:email invitee)
                   :role "editor"
                   :valid-until (ct/in-future "48h")})

      (with-redefs [cf/flags (conj cf/flags :admin-console)
                    nitrate/call
                    (fn [_cfg method _params]
                      (case method
                        :get-organization-membership-by-team {:organization-id organization-id
                                                              :is-member true}
                        :get-organization-members (throw (ex-info "unexpected member count" {}))
                        nil))
                    teams/add-profile-to-team! (fn [& _] nil)]
        (let [out (verify! team-token)]
          (t/is (th/success? out))
          (reset! frontend-event
                  (get-in out [:result :organization-invitation-audit]))))

      (let [events (mapv second (:call-args-list @audit-mock))]
        (t/is (some #(= "accept-team-invitation" (:name %)) events))
        (t/is (not-any? #(= "accept-organization-invitation" (:name %)) events))
        (t/is (nil? @frontend-event))))))

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
          ;; (th/print-result! out)
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
                 :valid-until (ct/in-future "48h")})

    ;; insert an entry on the database with an expired invitation
    (db/insert! th/*pool* :team-invitation
                {:team-id (:team-id data)
                 :email-to "test2@mail.com"
                 :role "editor"
                 :valid-until (ct/in-past "48h")})

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
                 :valid-until (ct/in-future "48h")})

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
                 :valid-until (ct/in-future "48h")})

    (let [out (th/command! data)
          ;; retrieve the value from the database and check its content
          res (db/get* th/*pool* :team-invitation
                       {:team-id (:team-id data) :email-to "test1@mail.com"})]

      (t/is (th/success? out))
      (t/is (nil? (:result out)))
      (t/is (nil? res)))))


(t/deftest get-owned-teams
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        team1    (th/create-team* 1 {:profile-id (:id profile1)})
        team2    (th/create-team* 2 {:profile-id (:id profile2)})

        params   {::th/type :get-owned-teams
                  ::rpc/profile-id (:id profile1)}
        out      (th/command! params)]

    ;; (th/print-result! out)
    (t/is (th/success? out))
    (let [[item1 :as result] (:result out)]
      (t/is (= 1 (count result)))
      (t/is (= (:id team1) (:id item1)))
      (t/is (= 1 (:total-members item1)))
      (t/is (= 1 (:total-editors item1)))
      (t/is (not= (:default-team-id profile1) (:id item1))))))


(t/deftest get-teams-fetches-organizations-in-one-batch
  (let [profile           (th/create-profile* 1 {:is-active true})
        organization-team (th/create-team* 1 {:profile-id (:id profile)})
        plain-team        (th/create-team* 2 {:profile-id (:id profile)})
        expired-team      (th/create-team* 3 {:profile-id (:id profile)})
        organization-id   (uuid/random)
        calls             (atom [])
        organization      {:id organization-id
                           :name "Acme"
                           :slug "acme"
                           :owner-id (:id profile)
                           :avatar-bg-url "https://example.com/avatar.svg"}
        nitrate-call      (fn [_cfg method params]
                            (swap! calls conj [method params])
                            [{:id (:id organization-team)
                              :is-your-penpot false
                              :organization organization}
                             {:id (:id expired-team)
                              :is-your-penpot false
                              :organization (assoc organization :expired-license true)}])
        params            {::th/type :get-teams
                           ::rpc/profile-id (:id profile)}]
    (with-redefs [cf/flags (conj cf/flags :admin-console)
                  nitrate/call nitrate-call]
      (let [out    (th/command! params)
            teams (:result out)]
        (t/is (th/success? out))
        (t/is (= 1 (count @calls)))
        (t/is (= :get-teams-organizations (ffirst @calls)))
        (t/is (= #{(:default-team-id profile)
                   (:id organization-team)
                   (:id plain-team)
                   (:id expired-team)}
                 (-> @calls first second :team-ids set)))
        (t/is (= #{(:default-team-id profile)
                   (:id organization-team)
                   (:id plain-team)}
                 (into #{} (map :id) teams)))
        (t/is (= organization
                 (->> teams
                      (filter #(= (:id organization-team) (:id %)))
                      first
                      :organization)))))))


(t/deftest get-teams-rejects-invalid-organization-batch-response
  (let [profile (th/create-profile* 1 {:is-active true})
        calls   (atom [])
        params  {::th/type :get-teams
                 ::rpc/profile-id (:id profile)}]
    (with-redefs [cf/flags (conj cf/flags :admin-console)
                  nitrate/call (fn [_cfg method call-params]
                                 (swap! calls conj [method call-params])
                                 nil)]
      (let [out (th/command! params)]
        (t/is (not (th/success? out)))
        (t/is (= :nitrate-unavailable (th/ex-type (:error out))))
        (t/is (= 1 (count @calls)))
        (t/is (= :get-teams-organizations (ffirst @calls)))))))


(t/deftest team-deletion-1
  (let [profile1 (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile1)})
        pool     (:app.db/pool th/*system*)
        data     {::th/type :delete-team
                  ::rpc/profile-id (:id profile1)
                  :team-id (:id team)}]

    ;; team is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (th/run-task! :objects-gc {})]
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
    (let [result (th/run-task! :objects-gc {})]
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
    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
      (let [result (th/run-task! :objects-gc {})]
        (t/is (= 2 (:processed result)))))

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
      (t/is (ct/inst? (:deleted-at (first rows)))))

    (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
      (let [result (th/run-task! :objects-gc {})]
        (t/is (= 7 (:processed result)))))))

(t/deftest create-team-access-request
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner      (th/create-profile* 1 {:is-active true :email "owner@bar.com"})
          requester  (th/create-profile* 3 {:is-active true :email "requester@bar.com"})
          team       (th/create-team* 1 {:profile-id (:id owner)})
          proj       (th/create-project* 1 {:profile-id (:id owner)
                                            :team-id (:id team)})
          file       (th/create-file* 1 {:profile-id (:id owner)
                                         :project-id (:id proj)})

          data       {::th/type :create-team-access-request
                      ::rpc/profile-id (:id requester)
                      :file-id (:id file)}]

      ;; request success
      (let [out        (th/command! data)
            ;; retrieve the value from the database and check its content
            requests   (th/db-query :team-access-request
                                    {:team-id (:id team)
                                     :requester-id (:id requester)})]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))
        (t/is (= 1 (count requests))))

      ;; request again fails
      (th/reset-mock! mock)
      (let [out        (th/command! data)
            edata (-> out :error ex-data)]
        (t/is (not (th/success? out)))
        (t/is (= 0 (:call-count @mock)))

        (t/is (= :validation (:type edata)))
        (t/is (= :request-already-sent (:code edata))))


      ;; request again when is expired success
      (th/reset-mock! mock)

      (th/db-update! :team-access-request
                     {:valid-until (ct/in-past "1h")}
                     {:team-id (:id team)
                      :requester-id (:id requester)})

      (t/is (th/success? (th/command! data)))
      (t/is (= 1 (:call-count @mock))))))


(t/deftest create-team-access-request-owner-muted
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner       (th/create-profile* 1 {:is-active true :is-muted true :email "owner@bar.com"})
          requester   (th/create-profile* 2 {:is-active true :email "requester@bar.com"})
          team        (th/create-team* 1 {:profile-id (:id owner)})
          proj        (th/create-project* 1 {:profile-id (:id owner)
                                             :team-id (:id team)})
          file        (th/create-file* 1 {:profile-id (:id owner)
                                          :project-id (:id proj)})

          data        {::th/type :create-team-access-request
                       ::rpc/profile-id (:id requester)
                       :file-id (:id file)}]

      ;; request to team with owner muted should success
      (t/is (th/success? (th/command! data)))
      (t/is (= 1 (:call-count @mock))))))


(t/deftest create-team-access-request-requester-muted
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner       (th/create-profile* 1 {:is-active true :email "owner@bar.com"})
          requester   (th/create-profile* 2 {:is-active true :is-muted true :email "requester@bar.com"})
          team        (th/create-team* 1 {:profile-id (:id owner)})
          proj        (th/create-project* 1 {:profile-id (:id owner)
                                             :team-id (:id team)})
          file        (th/create-file* 1 {:profile-id (:id owner)
                                          :project-id (:id proj)})

          data        {::th/type :create-team-access-request
                       ::rpc/profile-id (:id requester)
                       :file-id (:id file)}

          out   (th/command! data)
          edata (-> out :error ex-data)]

      ;; request with requester muted should fail
      (t/is (not (th/success? out)))
      (t/is (= 0 (:call-count @mock)))

      (t/is (= :validation (:type edata)))
      (t/is (= :member-is-muted (:code edata)))
      (t/is (= (:email requester) (:email edata))))))


(t/deftest create-team-access-request-owner-bounce
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner       (th/create-profile* 1 {:is-active true :email "owner@bar.com"})
          requester   (th/create-profile* 2 {:is-active true :email "requester@bar.com"})
          team        (th/create-team* 1 {:profile-id (:id owner)})
          proj        (th/create-project* 1 {:profile-id (:id owner)
                                             :team-id (:id team)})
          file        (th/create-file* 1 {:profile-id (:id owner)
                                          :project-id (:id proj)})

          pool        (:app.db/pool th/*system*)
          data        {::th/type :create-team-access-request
                       ::rpc/profile-id (:id requester)
                       :file-id (:id file)}]


      (th/create-global-complaint-for pool {:type :bounce :email "owner@bar.com"})
      (let [out   (th/command! data)
            edata (-> out :error ex-data)]

        ;; request with owner bounce should fail
        (t/is (not (th/success? out)))
        (t/is (= 0 (:call-count @mock)))

        (t/is (= :restriction (:type edata)))
        (t/is (= :email-has-permanent-bounces (:code edata)))
        (t/is (= "private" (:email edata)))))))

(t/deftest create-team-access-request-requester-bounce
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner       (th/create-profile* 1 {:is-active true :email "owner@bar.com"})
          requester   (th/create-profile* 2 {:is-active true :email "requester@bar.com"})
          team        (th/create-team* 1 {:profile-id (:id owner)})
          proj        (th/create-project* 1 {:profile-id (:id owner)
                                             :team-id (:id team)})
          file        (th/create-file* 1 {:profile-id (:id owner)
                                          :project-id (:id proj)})

          pool        (:app.db/pool th/*system*)
          data        {::th/type :create-team-access-request
                       ::rpc/profile-id (:id requester)
                       :file-id (:id file)}]

      ;; request with requester bounce should success
      (th/create-global-complaint-for pool {:type :bounce :email "requester@bar.com"})
      (t/is (th/success? (th/command! data)))
      (t/is (= 1 (:call-count @mock))))))

(t/deftest create-team-with-invalid-name
  (let [profile (th/create-profile* 1 {:is-active true})]

    ;; name with a dot should fail
    (let [data {::th/type :create-team
                ::rpc/profile-id (:id profile)
                :name "foo.bar"}
          out  (th/command! data)]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :params-validation)))

    ;; name with a colon should fail
    (let [data {::th/type :create-team
                ::rpc/profile-id (:id profile)
                :name "foo:bar"}
          out  (th/command! data)]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :params-validation)))

    ;; name with a slash should fail
    (let [data {::th/type :create-team
                ::rpc/profile-id (:id profile)
                :name "foo/bar"}
          out  (th/command! data)]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :params-validation)))

    ;; valid name should succeed
    (let [data {::th/type :create-team
                ::rpc/profile-id (:id profile)
                :name "My Valid Team"}
          out  (th/command! data)]
      (t/is (th/success? out)))))

(t/deftest create-team-invitations-email-cooldown
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          team     (th/create-team* 1 {:profile-id (:id profile1)})

          data     {::th/type :create-team-invitations
                    ::rpc/profile-id (:id profile1)
                    :team-id (:id team)
                    :role :editor
                    :emails ["cooldown-test@example.com"]}]

      ;; First invitation sends email
      (let [out (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock))))

      ;; Resending immediately should NOT send email (cooldown active)
      (th/reset-mock! mock)
      (let [out (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 0 (:call-count @mock))))

      ;; Resending to a different email should send email
      (th/reset-mock! mock)
      (let [data (assoc data :emails ["different@example.com"])
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock))))

      ;; After cooldown expires, resending should send email
      (th/reset-mock! mock)
      (th/db-update! :team-invitation
                     {:updated-at (ct/in-past "10m")}
                     {:team-id (:id team)
                      :email-to "cooldown-test@example.com"})
      (let [data (assoc data :emails ["cooldown-test@example.com"])
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))))))

(t/deftest update-team-with-invalid-name
  (let [profile (th/create-profile* 1 {:is-active true})
        team    (th/create-team* 1 {:profile-id (:id profile)})]

    ;; name with a dot should fail
    (let [data {::th/type :update-team
                ::rpc/profile-id (:id profile)
                :id (:id team)
                :name "foo.bar"}
          out  (th/command! data)]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :params-validation)))

    ;; name with a colon should fail
    (let [data {::th/type :update-team
                ::rpc/profile-id (:id profile)
                :id (:id team)
                :name "foo:bar"}
          out  (th/command! data)]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :params-validation)))

    ;; name with a slash should fail
    (let [data {::th/type :update-team
                ::rpc/profile-id (:id profile)
                :id (:id team)
                :name "foo/bar"}
          out  (th/command! data)]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :params-validation)))

    ;; valid name should succeed
    (let [data {::th/type :update-team
                ::rpc/profile-id (:id profile)
                :id (:id team)
                :name "My Valid Team"}
          out  (th/command! data)]
      (t/is (th/success? out)))))

(t/deftest create-team-in-organization-regression
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (let [owner           (th/create-profile* 401 {:is-active true})
          non-member      (th/create-profile* 402 {:is-active true})
          organization-id (uuid/random)
          params          {::th/type :create-team
                           ::rpc/profile-id (:id owner)
                           :name "Test Team"
                           :organization-id organization-id}

          nitrate-call-fn
          (fn [_cfg method p]
            (case method
              :get-organization-membership
              (if (= (:profile-id p) (:id non-member))
                {:organization-id organization-id :is-member false}
                {:organization-id organization-id :is-member true})

              :get-organization-permissions
              {:owner-id (:id owner)
               :permissions {:create-teams "any"}}

              :set-team-organization
              (let [team-id (:team-id p)]
                {:id team-id
                 :name "Test Team"
                 :organization-id organization-id
                 :default-project-id (uuid/random)})

              nil))]

      ;; Non-member should be denied with :user-doesnt-belong-organization
      (with-redefs [cf/flags (conj cf/flags :admin-console)
                    nitrate/call nitrate-call-fn]
        (let [out (th/command! (assoc params ::rpc/profile-id (:id non-member)))]
          (t/is (not (th/success? out)))
          (let [edata (-> out :error ex-data)]
            (t/is (= :validation (:type edata)))
            (t/is (= :user-doesnt-belong-organization (:code edata))))))

      ;; Authorized member should succeed
      (th/reset-mock! audit-mock)
      (with-redefs [cf/flags (conj cf/flags :admin-console)
                    nitrate/call nitrate-call-fn]
        (let [out (th/command! params)]
          (t/is (th/success? out))
          (let [team (:result out)]
            (t/is (uuid? (:id team)))
            (t/is (= "Test Team" (:name team)))))))))

;; --- T7-F-01: Role ceiling in team invitations ---

(t/deftest admin-cannot-create-invitation-with-owner-role
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner   (th/create-profile* 1 {:is-active true})
          admin   (th/create-profile* 2 {:is-active true})
          team    (th/create-team* 1 {:profile-id (:id owner)})]

      ;; Add admin as team member with :admin role
      (th/create-team-role* {:team-id (:id team)
                             :profile-id (:id admin)
                             :role :admin})

      ;; Admin tries to create invitation with :owner role (emails+role format)
      ;; This should FAIL with :cant-promote-to-owner
      (let [data {::th/type :create-team-invitations
                  ::rpc/profile-id (:id admin)
                  :team-id (:id team)
                  :role :owner
                  :emails ["invitee@example.com"]}
            out  (th/command! data)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation))
        (t/is (th/ex-of-code? (:error out) :cant-promote-to-owner))
        (t/is (= 0 (:call-count @mock)))))))

(t/deftest admin-cannot-create-invitation-with-owner-role-invitations-format
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner   (th/create-profile* 1 {:is-active true})
          admin   (th/create-profile* 2 {:is-active true})
          team    (th/create-team* 1 {:profile-id (:id owner)})]

      ;; Add admin as team member with :admin role
      (th/create-team-role* {:team-id (:id team)
                             :profile-id (:id admin)
                             :role :admin})

      ;; Admin tries to create invitation with :owner role (invitations format)
      ;; This should FAIL with :cant-promote-to-owner
      (let [data {::th/type :create-team-invitations
                  ::rpc/profile-id (:id admin)
                  :team-id (:id team)
                  :invitations [{:email "invitee@example.com" :role :owner}]}
            out  (th/command! data)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation))
        (t/is (th/ex-of-code? (:error out) :cant-promote-to-owner))
        (t/is (= 0 (:call-count @mock)))))))

(t/deftest admin-cannot-update-invitation-role-to-owner
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner   (th/create-profile* 1 {:is-active true})
          admin   (th/create-profile* 2 {:is-active true})
          team    (th/create-team* 1 {:profile-id (:id owner)})]

      ;; Add admin as team member with :admin role
      (th/create-team-role* {:team-id (:id team)
                             :profile-id (:id admin)
                             :role :admin})

      ;; Owner creates an invitation with :editor role
      (let [data {::th/type :create-team-invitations
                  ::rpc/profile-id (:id owner)
                  :team-id (:id team)
                  :role :editor
                  :emails ["invitee@example.com"]}
            out  (th/command! data)]
        (t/is (th/success? out)))

      (th/reset-mock! mock)

      ;; Admin tries to update invitation role to :owner
      ;; This should FAIL with :cant-promote-to-owner
      (let [data {::th/type :update-team-invitation-role
                  ::rpc/profile-id (:id admin)
                  :team-id (:id team)
                  :email "invitee@example.com"
                  :role :owner}
            out  (th/command! data)]
        (t/is (not (th/success? out)))
        (t/is (th/ex-of-type? (:error out) :validation))
        (t/is (th/ex-of-code? (:error out) :cant-promote-to-owner))))))

(t/deftest owner-can-create-invitation-with-owner-role
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [owner   (th/create-profile* 1 {:is-active true})
          team    (th/create-team* 1 {:profile-id (:id owner)})]

      ;; Owner creates invitation with :owner role
      ;; This should SUCCEED (owner has full privileges)
      (let [data {::th/type :create-team-invitations
                  ::rpc/profile-id (:id owner)
                  :team-id (:id team)
                  :role :owner
                  :emails ["invitee@example.com"]}
            out  (th/command! data)]
        (t/is (th/success? out))
        (t/is (= 1 (:call-count @mock)))))))

(t/deftest admin-cannot-remove-team-owner
  (let [owner  (th/create-profile* 1 {:is-active true})
        admin  (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]

    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id admin)
                           :role :admin})

    (let [out (th/command! {::th/type :delete-team-member
                            ::rpc/profile-id (:id admin)
                            :team-id (:id team)
                            :member-id (:id owner)})]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :cant-remove-owner)))))

(t/deftest owner-can-remove-another-owner
  (let [owner1 (th/create-profile* 1 {:is-active true})
        owner2 (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner1)})]

    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id owner2)
                           :role :owner})

    (let [out (th/command! {::th/type :delete-team-member
                            ::rpc/profile-id (:id owner1)
                            :team-id (:id team)
                            :member-id (:id owner2)})]
      (t/is (th/success? out)))))

(t/deftest owner-can-remove-admin
  (let [owner  (th/create-profile* 1 {:is-active true})
        admin  (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]

    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id admin)
                           :role :admin})

    (let [out (th/command! {::th/type :delete-team-member
                            ::rpc/profile-id (:id owner)
                            :team-id (:id team)
                            :member-id (:id admin)})]
      (t/is (th/success? out)))))

(t/deftest admin-can-remove-admin
  (let [owner  (th/create-profile* 1 {:is-active true})
        admin1 (th/create-profile* 2 {:is-active true})
        admin2 (th/create-profile* 3 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})]

    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id admin1)
                           :role :admin})

    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id admin2)
                           :role :admin})

    (let [out (th/command! {::th/type :delete-team-member
                            ::rpc/profile-id (:id admin1)
                            :team-id (:id team)
                            :member-id (:id admin2)})]
      (t/is (th/success? out)))))

(t/deftest delete-nonexistent-member-returns-not-found
  (let [owner    (th/create-profile* 1 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id owner)})
        fake-id  (uuid/next)]

    (let [out (th/command! {::th/type :delete-team-member
                            ::rpc/profile-id (:id owner)
                            :team-id (:id team)
                            :member-id fake-id})]
      (t/is (not (th/success? out)))
      (t/is (th/ex-of-type? (:error out) :not-found))
      (t/is (th/ex-of-code? (:error out) :member-does-not-exist)))))
