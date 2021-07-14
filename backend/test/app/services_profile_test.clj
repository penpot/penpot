;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.services-profile-test
  (:require
   [app.db :as db]
   [app.rpc.mutations.profile :as profile]
   [app.test-helpers :as th]
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
        data    {::th/type :login
                 :email "profile1.test@nodomain.com"
                 :password "foobar"
                 :scope "foobar"}
        out     (th/mutation! data)]

    #_(th/print-result! out)
    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :wrong-credentials)))))

;; Test with good credentials but profile not activated.
(t/deftest profile-login-failed-2
  (let [profile (th/create-profile* 1)
        data    {::th/type :login
                 :email "profile1.test@nodomain.com"
                 :password "123123"
                 :scope "foobar"}
        out     (th/mutation! data)]
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
                 :password "123123"
                 :scope "foobar"}
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
        (t/is (nil? (:result out)))))

    (t/testing "query profile after update"
      (let [data {::th/type :profile
                  :profile-id (:id profile)}
            out  (th/query! data)]

        ;; (th/print-result! out)
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
                         :tempfile (th/tempfile "app/test_files/sample.jpg")
                         :content-type "image/jpeg"}}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))))
    ))

(t/deftest profile-deletion-simple
  (let [task (:app.tasks.objects-gc/handler th/*system*)
        prof (th/create-profile* 1)
        file (th/create-file* 1 {:profile-id (:id prof)
                                 :project-id (:default-project-id prof)
                                 :is-shared false})]

    ;; profile is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (task {:max-age (dt/duration 0)})]
      (t/is (nil? result)))

    ;; Request profile to be deleted
    (let [params {::th/type :delete-profile
                  :profile-id (:id prof)}
          out    (th/mutation! params)]
      (t/is (nil? (:error out))))

    ;; query files after profile soft deletion
    (let [params {::th/type :files
                  :project-id (:default-project-id prof)
                  :profile-id (:id prof)}
          out    (th/query! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (= 1 (count (:result out)))))

    ;; execute permanent deletion task
    (let [result (task {:max-age (dt/duration "-1m")})]
      (t/is (nil? result)))

    ;; query profile after delete
    (let [params {::th/type :profile
                  :profile-id (:id prof)}
          out    (th/query! params)]
      ;; (th/print-result! out)
      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))
    ))

(t/deftest registration-domain-whitelist
  (let [whitelist #{"gmail.com" "hey.com" "ya.ru"}]
    (t/testing "allowed email domain"
      (t/is (true? (profile/email-domain-in-whitelist? whitelist "username@ya.ru")))
      (t/is (true? (profile/email-domain-in-whitelist? #{} "username@somedomain.com"))))

    (t/testing "not allowed email domain"
      (t/is (false? (profile/email-domain-in-whitelist? whitelist "username@somedomain.com"))))))

(t/deftest prepare-register-and-register-profile
  (let [data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :password "foobar"}
        out   (th/mutation! data)
        token (get-in out [:result :token])]
    (t/is (string? token))


    ;; try register without accepting terms
    (let [data  {::th/type :register-profile
                 :token token
                 :fullname "foobar"
                 :accept-terms-and-privacy false}
          out   (th/mutation! data)]
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :invalid-terms-and-privacy))))

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
        (t/is (nil? error))
        (t/is (true? (get-in result [:props :accept-newsletter-subscription])))
        (t/is (true? (get-in result [:props :accept-terms-and-privacy])))))
    ))

(t/deftest prepare-register-with-registration-disabled
  (with-mocks [mock {:target 'app.config/get
                     :return (th/mock-config-get-with
                              {:registration-enabled false})}]

    (let [data  {::th/type :prepare-register-profile
                 :email "user@example.com"
                 :password "foobar"}]
      (let [{:keys [result error] :as out} (th/mutation! data)]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :restriction))
        (t/is (th/ex-of-code? error :registration-disabled))))))

(t/deftest prepare-register-with-existing-user
  (let [profile (th/create-profile* 1)
        data    {::th/type :prepare-register-profile
                 :email (:email profile)
                 :password "foobar"}]
    (let [{:keys [result error] :as out} (th/mutation! data)]
      ;; (th/print-result! out)
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :email-already-exists)))))

(t/deftest test-register-profile-with-bounced-email
  (let [pool  (:app.db/pool th/*system*)
        data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :password "foobar"}]

    (th/create-global-complaint-for pool {:type :bounce :email "user@example.com"})

    (let [{:keys [result error] :as out} (th/mutation! data)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :email-has-permanent-bounces)))))

(t/deftest test-register-profile-with-complained-email
  (let [pool  (:app.db/pool th/*system*)
        data  {::th/type :prepare-register-profile
               :email "user@example.com"
               :password "foobar"}]

    (th/create-global-complaint-for pool {:type :complaint :email "user@example.com"})
    (let [{:keys [result error] :as out} (th/mutation! data)]
      (t/is (nil? error))
      (t/is (string? (:token result))))))

(t/deftest test-email-change-request
  (with-mocks [email-send-mock {:target 'app.emails/send! :return nil}
               cfg-get-mock    {:target 'app.config/get
                                :return (th/mock-config-get-with
                                         {:smtp-enabled true})}]
    (let [profile (th/create-profile* 1)
          pool    (:app.db/pool th/*system*)
          data    {::th/type :request-email-change
                   :profile-id (:id profile)
                   :email "user1@example.com"}]

      ;; without complaints
      (let [out (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (let [mock (deref email-send-mock)]
          (t/is (= 1 (:call-count mock)))
          (t/is (true? (:called? mock)))))

      ;; with complaints
      (th/create-global-complaint-for pool {:type :complaint :email (:email data)})
      (let [out (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (t/is (= 2 (:call-count (deref email-send-mock)))))

      ;; with bounces
      (th/create-global-complaint-for pool {:type :bounce :email (:email data)})
      (let [out   (th/mutation! data)
            error (:error out)]
        ;; (th/print-result! out)
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :email-has-permanent-bounces))
        (t/is (= 2 (:call-count (deref email-send-mock))))))))


(t/deftest test-email-change-request-without-smtp
  (with-mocks [email-send-mock {:target 'app.emails/send! :return nil}
               cfg-get-mock    {:target 'app.config/get
                                :return (th/mock-config-get-with
                                         {:smtp-enabled false})}]
    (let [profile (th/create-profile* 1)
          pool    (:app.db/pool th/*system*)
          data    {::th/type :request-email-change
                   :profile-id (:id profile)
                   :email "user1@example.com"}]

      ;; without complaints
      (let [out (th/mutation! data)
            res (:result out)]
        (t/is (= {:changed true} res))
        (let [mock (deref email-send-mock)]
          (t/is (false? (:called? mock))))))))


(t/deftest test-request-profile-recovery
  (with-mocks [mock {:target 'app.emails/send! :return nil}]
    (let [profile1 (th/create-profile* 1)
          profile2 (th/create-profile* 2 {:is-active true})
          pool  (:app.db/pool th/*system*)
          data  {::th/type :request-profile-recovery}]

      ;; with invalid email
      (let [data (assoc data :email "foo@bar.com")
            out  (th/mutation! data)]
        (t/is (nil? (:result out)))
        (t/is (= 0 (:call-count (deref mock)))))

      ;; with valid email inactive user
      (let [data  (assoc data :email (:email profile1))
            out   (th/mutation! data)
            error (:error out)]
        (t/is (= 0 (:call-count (deref mock))))
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :profile-not-verified)))

      ;; with valid email and active user
      (let [data  (assoc data :email (:email profile2))
            out   (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (t/is (= 1 (:call-count (deref mock)))))

      ;; with valid email and active user with global complaints
      (th/create-global-complaint-for pool {:type :complaint :email (:email profile2)})
      (let [data  (assoc data :email (:email profile2))
            out   (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:result out)))
        (t/is (= 2 (:call-count (deref mock)))))

      ;; with valid email and active user with global bounce
      (th/create-global-complaint-for pool {:type :bounce :email (:email profile2)})
      (let [data  (assoc data :email (:email profile2))
            out   (th/mutation! data)
            error (:error out)]
        ;; (th/print-result! out)
        (t/is (= 2 (:call-count (deref mock))))
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :email-has-permanent-bounces)))

      )))
