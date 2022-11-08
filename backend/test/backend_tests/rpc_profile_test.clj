;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-profile-test
  (:require
   [backend-tests.helpers :as th]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc.commands.auth :as cauth]
   [app.rpc.mutations.profile :as profile]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [mockery.core :refer [with-mocks]]))

;; TODO: profile deletion with teams
;; TODO: profile deletion with owner teams

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

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
        data    {::th/type :login
                 :email "profile1.test@nodomain.com"
                 :password "123123"}
        out     (th/mutation! data)]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (:id profile) (get-in out [:result :id])))))

(t/deftest profile-query-and-manipulation
  (let [profile (th/create-profile* 1)]
    (t/testing "query profile"
      (let [data {::th/type :profile
                  :profile-id (:id profile)}
            out  (th/query! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= "Profile 1" (:fullname result)))
          (t/is (= "profile1.test@nodomain.com" (:email result)))
          (t/is (not (contains? result :password))))))

    (t/testing "update profile"
      (let [data (assoc profile
                        ::th/type :update-profile
                        :profile-id (:id profile)
                        :fullname "Full Name"
                        :lang "en"
                        :theme "dark")
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (map? (:result out)))))

    (t/testing "query profile after update"
      (let [data {::th/type :profile
                  :profile-id (:id profile)}
            out  (th/query! data)]

        #_(th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= "Full Name" (:fullname result)))
          (t/is (= "en" (:lang result)))
          (t/is (= "dark" (:theme result))))))

    (t/testing "update photo"
      (let [data {::th/type :update-profile-photo
                  :profile-id (:id profile)
                  :file {:filename "sample.jpg"
                         :size 123123
                         :path (th/tempfile "backend_tests/test_files/sample.jpg")
                         :mtype "image/jpeg"}}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))))
    ))

(t/deftest profile-deletion-simple
  (let [prof (th/create-profile* 1)
        file (th/create-file* 1 {:profile-id (:id prof)
                                 :project-id (:default-project-id prof)
                                 :is-shared false})]

    ;; profile is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration 0)})]
      (t/is (= 0 (:processed result))))

    ;; Request profile to be deleted
    (let [params {::th/type :delete-profile
                  :profile-id (:id prof)}
          out    (th/mutation! params)]
      (t/is (nil? (:error out))))

    ;; query files after profile soft deletion
    (let [params {::th/type :project-files
                  :project-id (:default-project-id prof)
                  :profile-id (:id prof)}
          out    (th/query! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (= 1 (count (:result out)))))

    ;; execute permanent deletion task
    (let [result (th/run-task! :objects-gc {:min-age (dt/duration "-1m")})]
      (t/is (= 1 (:processed result))))

    ;; query profile after delete
    (let [params {::th/type :profile
                  :profile-id (:id prof)}
          out    (th/query! params)]
      ;; (th/print-result! out)
      (let [result (:result out)]
        (t/is (= uuid/zero (:id result)))))))

(t/deftest registration-domain-whitelist
  (let [whitelist #{"gmail.com" "hey.com" "ya.ru"}]
    (t/testing "allowed email domain"
      (t/is (true? (cauth/email-domain-in-whitelist? whitelist "username@ya.ru")))
      (t/is (true? (cauth/email-domain-in-whitelist? #{} "username@somedomain.com"))))

    (t/testing "not allowed email domain"
      (t/is (false? (cauth/email-domain-in-whitelist? whitelist "username@somedomain.com"))))))

(t/deftest prepare-register-and-register-profile-1
  (let [data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :password "foobar"}
        out   (th/mutation! data)
        token (get-in out [:result :token])]
    (t/is (string? token))


    ;; try register without token
    (let [data  {::th/type :register-profile
                 :fullname "foobar"
                 :accept-terms-and-privacy true}
          out   (th/mutation! data)]
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :spec-validation))))

    ;; try correct register
    (let [data  {::th/type :register-profile
                 :token token
                 :fullname "foobar"
                 :accept-terms-and-privacy true
                 :accept-newsletter-subscription true}]
      (let [{:keys [result error]} (th/mutation! data)]
        (t/is (nil? error))))
    ))

(t/deftest prepare-register-and-register-profile-1
  (let [data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :password "foobar"}
        out   (th/mutation! data)
        token (get-in out [:result :token])]
    (t/is (string? token))


    ;; try register without token
    (let [data  {::th/type :register-profile
                 :fullname "foobar"
                 :accept-terms-and-privacy true}
          out   (th/mutation! data)]
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :spec-validation))))

    ;; try correct register
    (let [data  {::th/type :register-profile
                 :token token
                 :fullname "foobar"
                 :accept-terms-and-privacy true
                 :accept-newsletter-subscription true}]
      (let [{:keys [result error]} (th/mutation! data)]
        (t/is (nil? error))))
    ))

(t/deftest prepare-register-and-register-profile-2
  (with-redefs [app.rpc.commands.auth/register-retry-threshold (dt/duration 500)]
    (with-mocks [mock {:target 'app.emails/send! :return nil}]
      (let [current-token (atom nil)]

        ;; PREPARE REGISTER
        (let [data  {::th/type :prepare-register-profile
                     :email "hello@example.com"
                     :password "foobar"}
              out   (th/command! data)
              token (get-in out [:result :token])]
          (t/is (string? token))
          (reset! current-token token))

        ;; DO REGISTRATION: try correct register attempt 1
        (let [data  {::th/type :register-profile
                     :token @current-token
                     :fullname "foobar"
                     :accept-terms-and-privacy true
                     :accept-newsletter-subscription true}
              out   (th/command! data)]
          (t/is (nil? (:error out)))
          (t/is (= 1 (:call-count @mock))))

        (th/reset-mock! mock)

        ;; PREPARE REGISTER without waiting for threshold
        (let [data  {::th/type :prepare-register-profile
                     :email "hello@example.com"
                     :password "foobar"}
              out   (th/command! data)]
          (t/is (not (th/success? out)))
          (t/is (= :validation (-> out :error th/ex-type)))
          (t/is (= :email-already-exists (-> out :error th/ex-code))))

        (th/sleep {:millis 500})
        (th/reset-mock! mock)

        ;; PREPARE REGISTER waiting the threshold
        (let [data  {::th/type :prepare-register-profile
                     :email "hello@example.com"
                     :password "foobar"}
              out   (th/command! data)]

          (t/is (th/success? out))
          (t/is (= 0 (:call-count @mock)))

          (let [result (:result out)]
            (t/is (contains? result :token))
            (reset! current-token (:token result))))

        ;; DO REGISTRATION: try correct register attempt 1
        (let [data  {::th/type :register-profile
                     :token @current-token
                     :fullname "foobar"
                     :accept-terms-and-privacy true
                     :accept-newsletter-subscription true}
              out   (th/command! data)]
          (t/is (th/success? out))
          (t/is (= 1 (:call-count @mock))))

        ))
    ))


(t/deftest prepare-and-register-with-invitation-and-disabled-registration-1
  (with-redefs [app.config/flags [:disable-registration]]
    (let [sprops (:app.setup/props th/*system*)
          itoken (tokens/generate sprops
                                  {:iss :team-invitation
                                   :exp (dt/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "user@example.com"})
          data  {::th/type :prepare-register-profile
                 :invitation-token itoken
                 :email "user@example.com"
                 :password "foobar"}

          {:keys [result error] :as out} (th/mutation! data)]
      (t/is (nil? error))
      (t/is (map? result))
      (t/is (string? (:token result)))

      (let [rtoken (:token result)
            data   {::th/type :register-profile
                    :token rtoken
                    :fullname "foobar"}

            {:keys [result error] :as out} (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (map? result))
        (t/is (string? (:invitation-token result)))))))

(t/deftest prepare-and-register-with-invitation-and-disabled-registration-2
  (with-redefs [app.config/flags [:disable-registration]]
    (let [sprops (:app.setup/props th/*system*)
          itoken (tokens/generate sprops
                                  {:iss :team-invitation
                                   :exp (dt/in-future "48h")
                                   :role :editor
                                   :team-id uuid/zero
                                   :member-email "user2@example.com"})

          data  {::th/type :prepare-register-profile
                 :invitation-token itoken
                 :email "user@example.com"
                 :password "foobar"}
          out   (th/command! data)]

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :restriction (:type edata)))
        (t/is (= :email-does-not-match-invitation (:code edata))))
      )))

(t/deftest prepare-register-with-registration-disabled
  (with-redefs [app.config/flags #{}]
    (let [data  {::th/type :prepare-register-profile
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
                 :email (:email profile)
                 :password "foobar"}
        out     (th/command! data)]

      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :validation (:type edata)))
        (t/is (= :email-already-exists (:code edata))))))

(t/deftest register-profile-with-bounced-email
  (let [pool  (:app.db/pool th/*system*)
        data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :password "foobar"}]

    (th/create-global-complaint-for pool {:type :bounce :email "user@example.com"})

    (let [out (th/command! data)]
      (t/is (not (th/success? out)))
      (let [edata (-> out :error ex-data)]
        (t/is (= :validation (:type edata)))
        (t/is (= :email-has-permanent-bounces (:code edata)))))))

(t/deftest register-profile-with-complained-email
  (let [pool  (:app.db/pool th/*system*)
        data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :password "foobar"}]

    (th/create-global-complaint-for pool {:type :complaint :email "user@example.com"})

    (let [out (th/command! data)]
      (t/is (th/success? out))
      (let [result (:result out)]
        (t/is (contains? result :token))))))

(t/deftest register-profile-with-email-as-password
  (let [data {::th/type :prepare-register-profile
              :email "user@example.com"
              :password "USER@example.com"}
        out  (th/command! data)]

    (t/is (not (th/success? out)))
    (let [edata (-> out :error ex-data)]
      (t/is (= :validation (:type edata)))
      (t/is (= :email-as-password (:code edata))))))

(t/deftest email-change-request
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (let [profile (th/create-profile* 1)
          pool    (:app.db/pool th/*system*)
          data    {::th/type :request-email-change
                   :profile-id (:id profile)
                   :email "user1@example.com"}]

      ;; without complaints
      (let [out (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (let [mock @mock]
          (t/is (= 1 (:call-count mock)))
          (t/is (true? (:called? mock)))))

      ;; with complaints
      (th/create-global-complaint-for pool {:type :complaint :email (:email data)})
      (let [out (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (t/is (= 2 (:call-count @mock))))

      ;; with bounces
      (th/create-global-complaint-for pool {:type :bounce :email (:email data)})
      (let [out   (th/mutation! data)
            error (:error out)]
        ;; (th/print-result! out)
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :email-has-permanent-bounces))
        (t/is (= 2 (:call-count @mock)))))))


(t/deftest email-change-request-without-smtp
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (with-redefs [app.config/flags #{}]
      (let [profile (th/create-profile* 1)
            pool    (:app.db/pool th/*system*)
            data    {::th/type :request-email-change
                     :profile-id (:id profile)
                     :email "user1@example.com"}
            out     (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (false? (:called? @mock)))
        (let [res (:result out)]
          (t/is (= {:changed true} res)))))))


(t/deftest request-profile-recovery
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (let [profile1 (th/create-profile* 1)
          profile2 (th/create-profile* 2 {:is-active true})
          pool  (:app.db/pool th/*system*)
          data  {::th/type :request-profile-recovery}]

      ;; with invalid email
      (let [data (assoc data :email "foo@bar.com")
            out  (th/mutation! data)]
        (t/is (nil? (:result out)))
        (t/is (= 0 (:call-count @mock))))

      ;; with valid email inactive user
      (let [data  (assoc data :email (:email profile1))
            out   (th/mutation! data)
            error (:error out)]
        (t/is (= 0 (:call-count @mock)))
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :profile-not-verified)))

      ;; with valid email and active user
      (let [data  (assoc data :email (:email profile2))
            out   (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (t/is (= 1 (:call-count @mock))))

      ;; with valid email and active user with global complaints
      (th/create-global-complaint-for pool {:type :complaint :email (:email profile2)})
      (let [data  (assoc data :email (:email profile2))
            out   (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (t/is (= 2 (:call-count @mock))))

      ;; with valid email and active user with global bounce
      (th/create-global-complaint-for pool {:type :bounce :email (:email profile2)})
      (let [data  (assoc data :email (:email profile2))
            out   (th/mutation! data)
            error (:error out)]
        ;; (th/print-result! out)
        (t/is (= 2 (:call-count @mock)))
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :email-has-permanent-bounces)))

      )))


(t/deftest update-profile-password
  (let [profile (th/create-profile* 1)
        data  {::th/type :update-profile-password
               :profile-id (:id profile)
               :old-password "123123"
               :password "foobarfoobar"}
        out   (th/mutation! data)]
    (t/is (nil? (:error out)))
    (t/is (nil? (:result out)))
  ))


(t/deftest update-profile-password-bad-old-password
  (let [profile (th/create-profile* 1)
        data  {::th/type :update-profile-password
               :profile-id (:id profile)
               :old-password "badpassword"
               :password "foobarfoobar"}
        {:keys [result error] :as out} (th/mutation! data)]
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :validation))
    (t/is (th/ex-of-code? error :old-password-not-match))))


(t/deftest update-profile-password-email-as-password
  (let [profile (th/create-profile* 1)
        data  {::th/type :update-profile-password
               :profile-id (:id profile)
               :old-password "123123"
               :password "profile1.test@nodomain.com"}
        {:keys [result error] :as out} (th/mutation! data)]
    (t/is (th/ex-info? error))
    (t/is (th/ex-of-type? error :validation))
    (t/is (th/ex-of-code? error :email-as-password))))
