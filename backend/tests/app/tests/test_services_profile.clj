;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-services-profile
  (:require
   [clojure.test :as t]
   [clojure.java.io :as io]
   [mockery.core :refer [with-mocks]]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [app.db :as db]
   [app.rpc.mutations.profile :as profile]
   [app.tests.helpers :as th]))

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
                         :tempfile "tests/app/tests/_files/sample.jpg"
                         :content-type "image/jpeg"}}
            out  (th/mutation! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))))
    ))

(t/deftest profile-deletion-simple
  (let [task (:app.tasks.delete-profile/handler th/*system*)
        prof (th/create-profile* 1)
        file (th/create-file* 1 {:profile-id (:id prof)
                                 :project-id (:default-project-id prof)
                                 :is-shared false})]

    ;; profile is not deleted because it does not meet all
    ;; conditions to be deleted.
    (let [result (task {:props {:profile-id (:id prof)}})]
      (t/is (nil? result)))

    ;; Request profile to be deleted
    (with-mocks [mock {:target 'app.tasks/submit! :return nil}]
      (let [params {::th/type :delete-profile
                    :profile-id (:id prof)}
            out    (th/mutation! params)]
        (t/is (nil? (:error out)))

        ;; check the mock
        (let [mock (deref mock)
              mock-params (second (:call-args mock))]
          (t/is (:called? mock))
          (t/is (= 1 (:call-count mock)))
          (t/is (= "delete-profile" (:name mock-params)))
          (t/is (= (:id prof) (get-in mock-params [:props :profile-id]))))))

    ;; query files after profile soft deletion
    (let [params {::th/type :files
                  :project-id (:default-project-id prof)
                  :profile-id (:id prof)}
          out    (th/query! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (= 1 (count (:result out)))))

    ;; execute permanent deletion task
    (let [result (task {:props {:profile-id (:id prof)}})]
      (t/is (true? result)))

    ;; query profile after delete
    (let [params {::th/type :profile
                  :profile-id (:id prof)}
          out    (th/query! params)]
      ;; (th/print-result! out)
      (let [error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :not-found))))

    ;; query files after profile soft deletion
    (let [params {::th/type :files
                  :project-id (:default-project-id prof)
                  :profile-id (:id prof)}
          out    (th/query! params)]
        ;; (th/print-result! out)
        (let [error (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found))))
    ))

(t/deftest registration-domain-whitelist
  (let [whitelist "gmail.com, hey.com, ya.ru"]
    (t/testing "allowed email domain"
      (t/is (true? (profile/email-domain-in-whitelist? whitelist "username@ya.ru")))
      (t/is (true? (profile/email-domain-in-whitelist? "" "username@somedomain.com"))))

    (t/testing "not allowed email domain"
      (t/is (false? (profile/email-domain-in-whitelist? whitelist "username@somedomain.com"))))))

;; TODO: profile deletion with teams
;; TODO: profile deletion with owner teams
;; TODO: profile registration
;; TODO: profile password recovery
