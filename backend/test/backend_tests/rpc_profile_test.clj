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
               :password "foobar"}
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
                 :token token
                 :utm_campaign "utma"
                 :mtm_campaign "mtma"}]
      (let [{:keys [result error]} (th/command! data)]
        (t/is (nil? error))))

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

        {:keys [result error] :as out} (th/command! data)]
    (t/is (nil? error))
    (t/is (map? result))
    (t/is (string? (:token result)))

    (let [rtoken (:token result)
          data   {::th/type :register-profile
                  :token rtoken}

          {:keys [result error] :as out} (th/command! data)]
        ;; (th/print-result! out)
      (t/is (nil? error))
      (t/is (map? result))
      (t/is (string? (:invitation-token result))))))

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
