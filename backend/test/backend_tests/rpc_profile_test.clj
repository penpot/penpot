;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-profile-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email.blacklist :as email.blacklist]
   [app.email.whitelist :as email.whitelist]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [mockery.core :refer [with-mocks]]))

;; TODO: profile deletion with teams
;; TODO: profile deletion with owner teams

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)


(t/deftest clean-email
  (t/is "foo@example.com" (profile/clean-email "mailto:foo@example.com"))
  (t/is "foo@example.com" (profile/clean-email "mailto:<foo@example.com>"))
  (t/is "foo@example.com" (profile/clean-email "<foo@example.com>"))
  (t/is "foo@example.com" (profile/clean-email "foo@example.com>"))
  (t/is "foo@example.com" (profile/clean-email "<foo@example.com")))

;; Test with wrong credentials
(t/deftest profile-login-failed-1
  (let [profile (th/create-profile* 1)
        data    {::th/type :login-with-password
                 :email "profile1.test@nodomain.com"
                 :password "foobar"}
        out     (th/command! data)]

    #_(th/print-result! out)
    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :wrong-credentials)))))

;; Test with good credentials but profile not activated.
(t/deftest profile-login-failed-2
  (let [profile (th/create-profile* 1)
        data    {::th/type :login-with-password
                 :email "profile1.test@nodomain.com"
                 :password "123123"}
        out     (th/command! data)]
    ;; (th/print-result! out)
    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :wrong-credentials)))))

;; Test with good credentials but profile already activated
(t/deftest profile-login-success
  (let [profile (th/create-profile* 1 {:is-active true})
        data    {::th/type :login-with-password
                 :email "profile1.test@nodomain.com"
                 :password "123123"}
        out     (th/command! data)]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (:id profile) (get-in out [:result :id])))))

(t/deftest profile-query-and-manipulation
  (let [profile (th/create-profile* 1)]
    (t/testing "query profile"
      (let [data {::th/type :get-profile
                  ::rpc/profile-id (:id profile)}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= "Profile 1" (:fullname result)))
          (t/is (= "profile1.test@nodomain.com" (:email result)))
          (t/is (not (contains? result :password))))))

    (t/testing "update profile"
      (let [data (assoc profile
                        ::th/type :update-profile
                        ::rpc/profile-id (:id profile)
                        :fullname "Full Name"
                        :lang "en"
                        :theme "dark")
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (map? (:result out)))))

    (t/testing "query profile after update"
      (let [data {::th/type :get-profile
                  ::rpc/profile-id (:id profile)}
            out  (th/command! data)]

        #_(th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= "Full Name" (:fullname result)))
          (t/is (= "en" (:lang result)))
          (t/is (= "dark" (:theme result))))))

    (t/testing "update photo"
      (let [data {::th/type :update-profile-photo
                  ::rpc/profile-id (:id profile)
                  :file {:filename "sample.jpg"
                         :size 123123
                         :path (th/tempfile "backend_tests/test_files/sample.jpg")
                         :mtype "image/jpeg"}}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))))))

(t/deftest profile-deletion-1
  (let [prof (th/create-profile* 1)
        file (th/create-file* 1 {:profile-id (:id prof)
                                 :project-id (:default-project-id prof)
                                 :is-shared false})]

    ;; profile is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (th/run-task! :objects-gc {:min-age 0})]
      (t/is (= 0 (:processed result))))

    ;; Request profile to be deleted
    (let [params {::th/type :delete-profile
                  ::rpc/profile-id (:id prof)}
          out    (th/command! params)]
      (t/is (nil? (:error out))))

    ;; query files after profile soft deletion
    (let [params {::th/type :get-project-files
                  ::rpc/profile-id (:id prof)
                  :project-id (:default-project-id prof)}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (= 1 (count (:result out)))))

    (th/run-pending-tasks!)

    (let [row (th/db-get :team
                         {:id (:default-team-id prof)}
                         {::db/remove-deleted false})]
      (t/is (ct/inst? (:deleted-at row))))

    ;; execute permanent deletion task
    (let [result (th/run-task! :objects-gc {:min-age 0})]
      (t/is (= 6 (:processed result))))

    (let [row (th/db-get :team
                         {:id (:default-team-id prof)}
                         {::db/remove-deleted false})]
      (t/is (nil? row)))

    ;; query profile after delete
    (let [params {::th/type :get-profile
                  ::rpc/profile-id (:id prof)}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (let [result (:result out)]
        (t/is (= uuid/zero (:id result)))))))

(t/deftest profile-deletion-2
  (let [prof1 (th/create-profile* 1)
        prof2 (th/create-profile* 2)
        file1 (th/create-file* 1 {:profile-id (:id prof1)
                                  :project-id (:default-project-id prof1)
                                  :is-shared false})
        team1 (th/create-team* 1 {:profile-id (:id prof1)})

        role1 (th/create-team-role* {:team-id (:id team1)
                                     :profile-id (:id prof2)

                                     :role :editor})]
    ;; Assert all roles for team
    (let [roles (th/db-query :team-profile-rel {:team-id (:id team1)})]
      (t/is (= 2 (count roles))))

    ;; Request profile to be deleted
    (let [params {::th/type :delete-profile
                  ::rpc/profile-id (:id prof1)}
          out    (th/command! params)]
      ;; (th/print-result! out)

      (let [error (:error out)
            edata (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type edata) :validation))
        (t/is (= (:code edata) :owner-teams-with-people)))

      (let [params {::th/type :delete-team
                    ::rpc/profile-id (:id prof1)
                    :id (:id team1)}
            out    (th/command! params)]
        ;; (th/print-result! out)

        (let [team (th/db-get :team {:id (:id team1)} {::db/remove-deleted false})]
          (t/is (ct/inst? (:deleted-at team)))))

      ;; Request profile to be deleted
      (let [params {::th/type :delete-profile
                    ::rpc/profile-id (:id prof1)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (t/is (nil? (:error out)))))))

(t/deftest profile-deletion-3
  (let [prof1 (th/create-profile* 1)
        prof2 (th/create-profile* 2)
        prof3 (th/create-profile* 3)
        file1 (th/create-file* 1 {:profile-id (:id prof1)
                                  :project-id (:default-project-id prof1)
                                  :is-shared false})
        team1 (th/create-team* 1 {:profile-id (:id prof1)})

        role1 (th/create-team-role* {:team-id (:id team1)
                                     :profile-id (:id prof2)
                                     :role :editor})
        role2 (th/create-team-role* {:team-id (:id team1)
                                     :profile-id (:id prof3)
                                     :role :editor})]

    ;; Assert all roles for team
    (let [roles (th/db-query :team-profile-rel {:team-id (:id team1)})]
      (t/is (= 3 (count roles))))

    ;; Request profile to be deleted (it should fail)
    (let [params {::th/type :delete-profile
                  ::rpc/profile-id (:id prof1)}
          out    (th/command! params)]
      ;; (th/print-result! out)

      (let [error (:error out)
            edata (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type edata) :validation))
        (t/is (= (:code edata) :owner-teams-with-people))))

    ;; Leave team by role 1
    (let [params {::th/type :leave-team
                  ::rpc/profile-id (:id prof2)
                  :id (:id team1)}
          out    (th/command! params)]

      ;; (th/print-result! out)
      (t/is (nil? (:result out)))
      (t/is (nil? (:error out))))

    ;; Request profile to be deleted (it should fail)
    (let [params {::th/type :delete-profile
                  ::rpc/profile-id (:id prof1)}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (let [error (:error out)
            edata (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type edata) :validation))
        (t/is (= (:code edata) :owner-teams-with-people))))

    ;; Leave team by role 0 (the default) and reassing owner to role 3
    ;; without reassinging it (should fail)
    (let [params {::th/type :leave-team
                  ::rpc/profile-id (:id prof1)
                  ;; :reassign-to (:id prof3)
                  :id (:id team1)}
          out    (th/command! params)]

      ;; (th/print-result! out)

      (let [error (:error out)
            edata (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type edata) :validation))
        (t/is (= (:code edata) :owner-cant-leave-team))))

    ;; Leave team by role 0 (the default) and reassing owner to role 3
    (let [params {::th/type :leave-team
                  ::rpc/profile-id (:id prof1)
                  :reassign-to (:id prof3)
                  :id (:id team1)}
          out    (th/command! params)]

      ;; (th/print-result! out)
      (t/is (nil? (:result out)))
      (t/is (nil? (:error out))))

    ;; Request profile to be deleted
    (let [params {::th/type :delete-profile
                  ::rpc/profile-id (:id prof1)}
          out    (th/command! params)]
      ;; (th/print-result! out)

      (t/is (nil? (:result out)))
      (t/is (nil? (:error out))))

    ;; query files after profile soft deletion
    (let [params {::th/type :get-project-files
                  ::rpc/profile-id (:id prof1)
                  :project-id (:default-project-id prof1)}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (= 1 (count (:result out)))))

    (th/run-pending-tasks!)

    ;; execute permanent deletion task
    (let [result (th/run-task! :objects-gc {:min-age 0})]
      (t/is (= 6 (:processed result))))

    (let [row (th/db-get :team
                         {:id (:default-team-id prof1)}
                         {::db/remove-deleted false})]
      (t/is (nil? row)))

    ;; query profile after delete
    (let [params {::th/type :get-profile
                  ::rpc/profile-id (:id prof1)}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (let [result (:result out)]
        (t/is (= uuid/zero (:id result)))))))


(t/deftest profile-deletion-4
  (let [prof1 (th/create-profile* 1)
        file1 (th/create-file* 1 {:profile-id (:id prof1)
                                  :project-id (:default-project-id prof1)
                                  :is-shared false})
        team1 (th/create-team* 1 {:profile-id (:id prof1)})
        team2 (th/create-team* 2 {:profile-id (:id prof1)})]

    ;; Request profile to be deleted
    (let [params {::th/type :delete-profile
                  ::rpc/profile-id (:id prof1)}
          out    (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:result out)))
      (t/is (nil? (:error out))))

    (th/run-pending-tasks!)

    (let [rows (th/db-exec! ["select id,name,deleted_at from team where deleted_at is not null"])]
      (t/is (= 3 (count rows))))

    ;; execute permanent deletion task
    (let [result (th/run-task! :objects-gc {:min-age 0})]
      (t/is (= 10 (:processed result))))))


(t/deftest email-blacklist-1
  (t/is (false? (email.blacklist/enabled? th/*system*)))
  (t/is (true? (email.blacklist/enabled? (assoc th/*system* :app.email/blacklist []))))
  (t/is (true? (email.blacklist/contains? (assoc th/*system* :app.email/blacklist #{"foo.com"}) "AA@FOO.COM"))))

(t/deftest email-whitelist-1
  (t/is (false? (email.whitelist/enabled? th/*system*)))
  (t/is (true? (email.whitelist/enabled? (assoc th/*system* :app.email/whitelist []))))
  (t/is (true? (email.whitelist/contains? (assoc th/*system* :app.email/whitelist #{"foo.com"}) "AA@FOO.COM"))))

(t/deftest prepare-register-and-register-profile-1
  (let [data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :fullname "foobar"
               :password "foobar"
               :utm_campaign "utma"
               :mtm_campaign "mtma"}
        out   (th/command! data)
        token (get-in out [:result :token])]
    (t/is (string? token))

    ;; try register without token
    (let [data  {::th/type :register-profile}
          out   (th/command! data)]
      ;; (th/print-result! out)
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :params-validation))))

    ;; try correct register
    (let [data  {::th/type :register-profile
                 :token token}
          out   (th/command! data)]
      (t/is (nil? (:error out))))

    (let [profile (some-> (th/db-get :profile {:email "user@example.com"})
                          (profile/decode-row))]
      (t/is (= "penpot" (:auth-backend profile)))
      (t/is (= "foobar" (:fullname profile)))
      (t/is (false? (:is-active profile)))
      (t/is (uuid? (:default-team-id profile)))
      (t/is (uuid? (:default-project-id profile)))

      (let [props (:props profile)]
        (t/is (= "utma" (:penpot/utm-campaign props)))
        (t/is (= "mtma" (:penpot/mtm-campaign props)))))))

(t/deftest prepare-register-and-register-profile-2
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [current-token (atom nil)]
      ;; PREPARE REGISTER
      (let [data  {::th/type :prepare-register-profile
                   :email "hello@example.com"
                   :fullname "foobar"
                   :password "foobar"}
            out   (th/command! data)
            token (get-in out [:result :token])]
        (t/is (th/success? out))
        (reset! current-token token))

      ;; DO REGISTRATION
      (let [data  {::th/type :register-profile
                   :token @current-token}
            out   (th/command! data)]
        (t/is (nil? (:error out)))
        (t/is (= 1 (:call-count @mock))))

      (th/reset-mock! mock)

      ;; PREPARE REGISTER: second attempt
      (let [data  {::th/type :prepare-register-profile
                   :email "hello@example.com"
                   :fullname "foobar"
                   :password "foobar"}
            out   (th/command! data)
            token (get-in out [:result :token])]
        (t/is (th/success? out))
        (reset! current-token token))

      ;; DO REGISTRATION: second attempt
      (let [data  {::th/type :register-profile
                   :token @current-token
                   :fullname "foobar"
                   :accept-terms-and-privacy true
                   :accept-newsletter-subscription true}
            out   (th/command! data)]
        (t/is (nil? (:error out)))
        (t/is (= 0 (:call-count @mock))))

      (with-mocks [_ {:target 'app.rpc.commands.auth/elapsed-verify-threshold?
                      :return true}]
        ;; DO REGISTRATION: third attempt
        (let [data  {::th/type :register-profile
                     :token @current-token
                     :fullname "foobar"
                     :accept-terms-and-privacy true
                     :accept-newsletter-subscription true}
              out   (th/command! data)]
          (t/is (nil? (:error out)))
          (t/is (= 1 (:call-count @mock))))))))

(t/deftest prepare-register-and-register-profile-3
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [current-token (atom nil)]
      ;; PREPARE REGISTER
      (let [data  {::th/type :prepare-register-profile
                   :email "hello@example.com"
                   :fullname "foobar"
                   :password "foobar"}
            out   (th/command! data)
            token (get-in out [:result :token])]
        (t/is (th/success? out))
        (reset! current-token token))

      ;; DO REGISTRATION
      (let [data  {::th/type :register-profile
                   :token @current-token}
            out   (th/command! data)]
        (t/is (nil? (:error out)))
        (t/is (= 1 (:call-count @mock))))

      (th/reset-mock! mock)

      (th/db-update! :profile
                     {:is-blocked true}
                     {:email "hello@example.com"})

      ;; PREPARE REGISTER: second attempt
      (let [data  {::th/type :prepare-register-profile
                   :email "hello@example.com"
                   :fullname "foobar"
                   :password "foobar"}
            out   (th/command! data)
            token (get-in out [:result :token])]
        (t/is (th/success? out))
        (reset! current-token token))

      (with-mocks [_ {:target 'app.rpc.commands.auth/elapsed-verify-threshold?
                      :return true}]
        ;; DO REGISTRATION: second attempt
        (let [data  {::th/type :register-profile
                     :token @current-token}
              out   (th/command! data)]
          (t/is (nil? (:error out)))
          (t/is (= 0 (:call-count @mock))))))))

(t/deftest prepare-and-register-with-invitation-and-enabled-registration-1
  ;; With email-verification ENABLED (the default), a brand-new
  ;; profile created via the invitation flow is NOT active yet, so
  ;; `register-profile` must NOT mint a session and must NOT echo
  ;; back the invitation token. Instead it must dispatch the
  ;; verify-email mail with the invitation token EMBEDDED into the
  ;; verify-email JWE (so the team-invitation flow can resume after
  ;; the user clicks the email link).
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [itoken (tokens/generate th/*system*
                                  {:iss :team-invitation
                                   :exp (ct/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "user@example.com"})
          prep-data {::th/type :prepare-register-profile
                     :invitation-token itoken
                     :fullname "foobar"
                     :email "user@example.com"
                     :password "foobar"}

          {prep-result :result prep-error :error} (th/command! prep-data)]
      (t/is (nil? prep-error))
      (t/is (map? prep-result))
      (t/is (string? (:token prep-result)))

      (let [reg-data {::th/type :register-profile
                      :token (:token prep-result)}

            {reg-result :result reg-error :error} (th/command! reg-data)
            mdata    (meta reg-result)]
        (t/is (nil? reg-error))
        (t/is (map? reg-result))

        ;; No invitation token echoed back, no session minted.
        (t/is (nil? (:invitation-token reg-result)))
        (t/is (empty? (:app.rpc/response-transform-fns mdata)))

        ;; The verify-email mail was dispatched, and its token claims
        ;; carry the invitation-token through to the verification step.
        (t/is (= 1 (:call-count @mock)))
        (let [send-args   (-> @mock :call-args)
              email-token (->> send-args (some (fn [m] (when (map? m) (:token m)))))
              vclaims     (tokens/decode th/*system* email-token)]
          (t/is (= :verify-email (:iss vclaims)))
          (t/is (= itoken (:invitation-token vclaims))))))))

(t/deftest prepare-and-register-with-invitation-and-enabled-registration-1b
  ;; With email-verification DISABLED, the brand-new profile is
  ;; immediately active, so `register-profile` mints a session and
  ;; returns the regenerated invitation token in the body — the
  ;; frontend then redirects to :auth-verify-token to complete the
  ;; team-invitation flow.
  (with-redefs [app.config/flags #{:registration :login-with-password}]
    (let [itoken (tokens/generate th/*system*
                                  {:iss :team-invitation
                                   :exp (ct/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "user@example.com"})
          prep-data {::th/type :prepare-register-profile
                     :invitation-token itoken
                     :fullname "foobar"
                     :email "user@example.com"
                     :password "foobar"}

          {prep-result :result prep-error :error} (th/command! prep-data)]
      (t/is (nil? prep-error))
      (t/is (string? (:token prep-result)))

      (let [reg-data {::th/type :register-profile
                      :token (:token prep-result)}

            {reg-result :result reg-error :error} (th/command! reg-data)
            mdata    (meta reg-result)]
        (t/is (nil? reg-error))
        (t/is (map? reg-result))

        ;; Active branch: invitation-token is echoed back and a session
        ;; is minted via `session/create-fn`.
        (t/is (string? (:invitation-token reg-result)))
        (t/is (seq (:app.rpc/response-transform-fns mdata)))
        (t/is (= "accept-invitation"
                 (get-in mdata [:app.loggers.audit/context :action])))))))

(t/deftest prepare-and-register-with-invitation-and-enabled-registration-2
  (let [itoken (tokens/generate th/*system*
                                {:iss :team-invitation
                                 :exp (ct/in-future "48h")
                                 :role :editor
                                 :team-id uuid/zero
                                 :member-email "user2@example.com"})

        data  {::th/type :prepare-register-profile
               :invitation-token itoken
               :email "user@example.com"
               :fullname "foobar"
               :password "foobar"}
        out   (th/command! data)]

    (t/is (not (th/success? out)))
    (let [edata (-> out :error ex-data)]
      (t/is (= :restriction (:type edata)))
      (t/is (= :email-does-not-match-invitation (:code edata))))))

(t/deftest prepare-and-register-with-invitation-and-disabled-registration-1
  (with-redefs [app.config/flags [:disable-registration]]
    (let [itoken (tokens/generate th/*system*
                                  {:iss :team-invitation
                                   :exp (ct/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "user@example.com"})
          data  {::th/type :prepare-register-profile
                 :invitation-token itoken
                 :fullname "foobar"
                 :email "user@example.com"
                 :password "foobar"}
          out (th/command! data)]

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :restriction (:type edata)))
        (t/is (= :registration-disabled (:code edata)))))))

(t/deftest prepare-and-register-with-invitation-and-disabled-registration-2
  (with-redefs [app.config/flags [:disable-registration]]
    (let [itoken (tokens/generate th/*system*
                                  {:iss :team-invitation
                                   :exp (ct/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "user2@example.com"})

          data  {::th/type :prepare-register-profile
                 :invitation-token itoken
                 :email "user@example.com"
                 :fullname "foobar"
                 :password "foobar"}
          out   (th/command! data)]

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :restriction (:type edata)))
        (t/is (= :registration-disabled (:code edata)))))))

(t/deftest prepare-and-register-with-invitation-and-disabled-login-with-password
  (with-redefs [app.config/flags [:disable-login-with-password]]
    (let [itoken (tokens/generate th/*system*
                                  {:iss :team-invitation
                                   :exp (ct/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "user2@example.com"})

          data  {::th/type :prepare-register-profile
                 :invitation-token itoken
                 :fullname "foobar"
                 :email "user@example.com"
                 :password "foobar"}
          out   (th/command! data)]

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :restriction (:type edata)))
        (t/is (= :registration-disabled (:code edata)))))))

(t/deftest prepare-register-with-registration-disabled
  (with-redefs [app.config/flags #{}]
    (let [data  {::th/type :prepare-register-profile
                 :fullname "foobar"
                 :email "user@example.com"
                 :password "foobar"}
          out  (th/command! data)]

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :restriction (:type edata)))
        (t/is (= :registration-disabled (:code edata)))))))

(t/deftest prepare-register-with-existing-user
  (let [profile (th/create-profile* 1)
        data    {::th/type :prepare-register-profile
                 :fullname "foobar"
                 :email (:email profile)
                 :password "foobar"}
        out     (th/command! data)]
    ;; (th/print-result! out)
    (t/is (th/success? out))
    (let [result (:result out)]
      (t/is (contains? result :token)))))

(t/deftest prepare-register-profile-with-bounced-email

  (let [pool  (:app.db/pool th/*system*)
        data  {::th/type :prepare-register-profile
               :fullname "foobar"
               :email "user@example.com"
               :password "foobar"}]

    (th/create-global-complaint-for pool {:type :bounce :email "user@example.com"})

    (let [out (th/command! data)]
      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :restriction (:type edata)))
        (t/is (= :email-has-permanent-bounces (:code edata)))))))

(t/deftest register-profile-with-complained-email
  (let [pool  (:app.db/pool th/*system*)
        data  {::th/type :prepare-register-profile
               :fullname "foobar"
               :email "user@example.com"
               :password "foobar"}]

    (th/create-global-complaint-for pool {:type :complaint :email "user@example.com"})

    (let [out (th/command! data)]
      (t/is (not (th/success? out)))

      (let [edata (-> out :error ex-data)]
        (t/is (= :restriction (:type edata)))
        (t/is (= :email-has-complaints (:code edata)))))))

(t/deftest register-profile-with-email-as-password
  (let [data {::th/type :prepare-register-profile
              :fullname "foobar"
              :email "user@example.com"
              :password "USER@example.com"}
        out  (th/command! data)]

    (t/is (not (th/success? out)))
    (let [edata (-> out :error ex-data)]
      (t/is (= :validation (:type edata)))
      (t/is (= :email-as-password (:code edata))))))

(t/deftest prepare-register-rejects-active-profile-email
  ;; SECURITY: `prepare-register` must reject any attempt to prepare a
  ;; registration for an email that already belongs to an *active*
  ;; profile, regardless of whether an invitation token is supplied.
  ;; Active profiles must use the standard login flow.
  (let [_victim (th/create-profile* 1 {:is-active true
                                       :email "victim@corp.tld"})]

    ;; Without invitation token.
    (let [out (th/command! {::th/type :prepare-register-profile
                            :fullname "Mallory"
                            :email "victim@corp.tld"
                            :password "Whatever1!"})]
      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :validation (:type edata)))
        (t/is (= :email-already-exists (:code edata)))))

    ;; With invitation token (the GHSA-4937-35vc-hqjj exploit shape).
    (let [itoken (tokens/generate th/*system*
                                  {:iss :team-invitation
                                   :exp (ct/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "victim@corp.tld"})
          out    (th/command! {::th/type :prepare-register-profile
                               :invitation-token itoken
                               :fullname "Mallory"
                               :email "victim@corp.tld"
                               :password "Whatever1!"})]
      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :validation (:type edata)))
        (t/is (= :email-already-exists (:code edata)))))))

(t/deftest prepare-register-must-not-leak-existing-profile-id
  ;; Victim is a pre-existing profile that has not yet activated (e.g.
  ;; freshly registered, has not clicked the email verification link).
  ;; `prepare-register` allows the call (no active profile exists), but
  ;; the issued JWE must NOT carry the existing profile's id.
  (let [_victim (th/create-profile* 1 {:is-active false
                                       :email "victim@corp.tld"})

        ;; Attacker holds a cryptographically valid `:team-invitation` JWE
        ;; for the victim's email. (In a real exploit this is obtained
        ;; from `create-team-invitations` or `get-team-invitation-token`
        ;; on a team the attacker owns.)
        itoken (tokens/generate th/*system*
                                {:iss :team-invitation
                                 :exp (ct/in-future "48h")
                                 :role :editor
                                 :team-id uuid/zero
                                 :member-email "victim@corp.tld"})

        ;; Anonymous request — no ::rpc/profile-id.
        data   {::th/type :prepare-register-profile
                :invitation-token itoken
                :fullname "Mallory"
                :email "victim@corp.tld"
                :password "Whatever1!"}

        out    (th/command! data)]

    ;; The current behaviour either returns a token or rejects the request;
    ;; what MUST hold is that the issued prepared-register JWE does not
    ;; carry the victim's profile id.
    (t/is (th/success? out))

    (let [token  (-> out :result :token)
          claims (tokens/decode th/*system* token)]
      (t/is (= :prepared-register (:iss claims)))
      ;; This is the root-cause assertion: an anonymous prepare-register
      ;; call must NEVER embed an existing profile's id.
      (t/is (nil? (:profile-id claims))
            "prepare-register must not embed existing profile id of an anonymous caller"))))

(t/deftest register-profile-with-invitation-must-not-take-over-existing-account
  (with-mocks [_mock {:target 'app.email/send! :return nil}]
    (let [;; Victim profile exists but is not yet active (e.g. registered
          ;; but has not clicked the verification link). This is the
          ;; remaining attack surface after fix 1b: `prepare-register`
          ;; will not reject this case, so the `register-profile` path
          ;; must enforce the security invariants on its own.
          victim   (th/create-profile* 1 {:is-active false
                                          :email "victim@corp.tld"})

          ;; Attacker mints a valid `:team-invitation` JWE for the victim's
          ;; email. No member-id is included (matches what an attacker
          ;; obtains via `create-team-invitations` against their own team
          ;; before the victim has joined).
          itoken   (tokens/generate th/*system*
                                    {:iss :team-invitation
                                     :exp (ct/in-future "48h")
                                     :role :editor
                                     :team-id uuid/zero
                                     :member-email "victim@corp.tld"})

          ;; Step 1 (anonymous): prepare-register-profile with the victim's
          ;; email + the invitation token.
          prep-out (th/command! {::th/type :prepare-register-profile
                                 :invitation-token itoken
                                 :fullname "Mallory"
                                 :email "victim@corp.tld"
                                 :password "Whatever1!"})

          rtoken   (-> prep-out :result :token)

          ;; Step 2 (anonymous): register-profile with the prepared token.
          reg-out  (th/command! {::th/type :register-profile
                                 :token rtoken})

          result   (:result reg-out)
          mdata    (meta result)]

      ;; The first call may succeed; the issue is what the second call
      ;; produces. We assert the security invariants on its result.
      (t/is (th/success? prep-out))

      ;; INVARIANT 1: register-profile must NOT install a session for the
      ;; victim. `session/create-fn` is wired via
      ;; `rph/with-transform`, which appends to
      ;; `:app.rpc/response-transform-fns`. If that vector is non-empty
      ;; for an anonymous register that targets an EXISTING profile, the
      ;; server is about to mint an `auth-token` cookie bound to the
      ;; victim — i.e. account takeover.
      (t/is (empty? (:app.rpc/response-transform-fns mdata))
            "register-profile must not create a session for an existing victim profile")

      ;; INVARIANT 2: register-profile must NOT echo back an invitation
      ;; token that authenticates as the victim. When the response
      ;; contains both `:id` matching the victim and `:invitation-token`,
      ;; the frontend treats the user as logged-in for that profile.
      (when (and (map? result)
                 (= (:id victim) (:id result)))
        (t/is (not (contains? result :invitation-token))
              "register-profile must not return an invitation-token bound to an existing victim profile"))

      ;; INVARIANT 3: the server must NOT have taken the
      ;; "accept-invitation" branch (which is the one that mints a
      ;; session). For an existing victim profile, the operation
      ;; should fall through to the harmless "repeated registry" path.
      (t/is (not= "accept-invitation"
                  (get-in mdata [:app.loggers.audit/context :action]))
            "register-profile must not run the accept-invitation branch for an existing victim profile")
      ;; The victim must remain inactive: nothing in this anonymous
      ;; flow should have flipped `is-active` to true.
      (let [reloaded (th/db-get :profile {:id (:id victim)})]
        (t/is (false? (:is-active reloaded))
              "register-profile must not activate the victim profile")))))

(t/deftest verify-email-with-invitation-token-propagates-it
  ;; A `:verify-email` JWE that carries `:invitation-token` (as
  ;; produced by `register-profile` for the not-active+invitation
  ;; case) must propagate that token through the verify-token RPC
  ;; result so the frontend can resume the team-invitation flow.
  (let [profile (th/create-profile* 1 {:is-active false})
        itoken  (tokens/generate th/*system*
                                 {:iss :team-invitation
                                  :exp (ct/in-future "48h")
                                  :role :editor
                                  :team-id uuid/zero
                                  :member-email (:email profile)})
        vtoken  (tokens/generate th/*system*
                                 {:iss :verify-email
                                  :exp (ct/in-future "72h")
                                  :profile-id (:id profile)
                                  :email (:email profile)
                                  :invitation-token itoken})

        out     (th/command! {::th/type :verify-token
                              :token vtoken})
        result  (:result out)]

    (t/is (th/success? out))
    (t/is (= :verify-email (:iss result)))
    (t/is (= itoken (:invitation-token result))
          "verify-token must echo back the invitation-token from the verify-email JWE")

    ;; And the profile must now be active.
    (let [reloaded (th/db-get :profile {:id (:id profile)})]
      (t/is (true? (:is-active reloaded))))))

(t/deftest email-change-request
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile (th/create-profile* 1)
          pool    (:app.db/pool th/*system*)
          data    {::th/type :request-email-change
                   ::rpc/profile-id (:id profile)
                   :email "user1@example.com"}]

      ;; without complaints
      (let [out (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (let [mock @mock]
          (t/is (= 1 (:call-count mock)))
          (t/is (true? (:called? mock)))))

      ;; with complaints
      (th/create-global-complaint-for pool {:type :complaint :email (:email data)})
      (let [out   (th/command! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))

        (let [edata (-> out :error ex-data)]
          (t/is (= :restriction (:type edata)))
          (t/is (= :email-has-complaints (:code edata))))

        (t/is (= 1 (:call-count @mock))))

      ;; with bounces
      (th/create-global-complaint-for pool {:type :bounce :email (:email data)})
      (let [out   (th/command! data)]
        ;; (th/print-result! out)

        (let [edata (-> out :error ex-data)]
          (t/is (= :restriction (:type edata)))
          (t/is (= :email-has-permanent-bounces (:code edata))))

        (t/is (= 1 (:call-count @mock)))))))


(t/deftest email-change-request-without-smtp
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (with-redefs [app.config/flags #{}]
      (let [profile (th/create-profile* 1)
            pool    (:app.db/pool th/*system*)
            data    {::th/type :request-email-change
                     ::rpc/profile-id (:id profile)
                     :email "user1@example.com"}
            out     (th/command! data)]

        ;; (th/print-result! out)
        (t/is (false? (:called? @mock)))
        (let [res (:result out)]
          (t/is (= {:changed true} res)))))))


(t/deftest request-profile-recovery
  (with-mocks [mock {:target 'app.email/send! :return nil}]
    (let [profile1 (th/create-profile* 1 {:is-active false})
          profile2 (th/create-profile* 2 {:is-active true})
          pool  (:app.db/pool th/*system*)
          data  {::th/type :request-profile-recovery}]

      ;; with invalid email
      (let [data (assoc data :email "foo@bar.com")
            out  (th/command! data)]
        (t/is (nil? (:result out)))
        (t/is (= 0 (:call-count @mock))))

      ;; with valid email inactive user
      (let [data  (assoc data :email (:email profile1))
            out   (th/command! data)]
        (t/is (= 0 (:call-count @mock)))
        (t/is (nil? (:result out)))
        (t/is (nil? (:error out))))

      (with-mocks [_ {:target 'app.rpc.commands.auth/elapsed-verify-threshold?
                      :return true}]
        ;; with valid email inactive user
        (let [data  (assoc data :email (:email profile1))
              out   (th/command! data)]
          (t/is (= 1 (:call-count @mock)))
          (t/is (nil? (:result out)))
          (t/is (nil? (:error out)))))

      (th/reset-mock! mock)

      ;; with valid email and active user
      (with-mocks [_ {:target 'app.rpc.commands.auth/elapsed-verify-threshold?
                      :return true}]
        (let [data  (assoc data :email (:email profile2))
              out   (th/command! data)]
          ;; (th/print-result! out)
          (t/is (nil? (:result out)))
          (t/is (= 1 (:call-count @mock))))

        ;; with valid email and active user with global complaints
        (th/create-global-complaint-for pool {:type :complaint :email (:email profile2)})
        (let [data  (assoc data :email (:email profile2))
              out   (th/command! data)]
          ;; (th/print-result! out)
          (t/is (nil? (:result out)))
          (t/is (= 1 (:call-count @mock))))

        ;; with valid email and active user with global bounce
        (th/create-global-complaint-for pool {:type :bounce :email (:email profile2)})
        (let [data  (assoc data :email (:email profile2))
              out   (th/command! data)]
          (t/is (nil? (:result out)))
          (t/is (nil? (:error out)))
          ;; (th/print-result! out)
          (t/is (= 1 (:call-count @mock))))))))


(t/deftest update-profile-password
  (let [profile (th/create-profile* 1)
        data  {::th/type :update-profile-password
               ::rpc/profile-id (:id profile)
               :old-password "123123"
               :password "foobarfoobar"}
        out   (th/command! data)]
    (t/is (nil? (:error out)))
    (t/is (nil? (:result out)))))


(t/deftest update-profile-password-bad-old-password
  (let [profile (th/create-profile* 1)
        data  {::th/type :update-profile-password
               ::rpc/profile-id (:id profile)
               :old-password "badpassword"
               :password "foobarfoobar"}
        {:keys [result error] :as out} (th/command! data)]
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :validation))
    (t/is (th/ex-of-code? error :old-password-not-match))))


(t/deftest update-profile-password-email-as-password
  (let [profile (th/create-profile* 1)
        data  {::th/type :update-profile-password
               ::rpc/profile-id (:id profile)
               :old-password "123123"
               :password "profile1.test@nodomain.com"}
        {:keys [result error] :as out} (th/command! data)]
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :validation))
    (t/is (th/ex-of-code? error :email-as-password))))
