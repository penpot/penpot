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
   ;; [app.services.mutations.profile :as profile]
   [app.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest profile-login
  (let [profile (th/create-profile th/*pool* 1)]
    (t/testing "failed"
      (let [data {::th/type :login
                   :email "profile1.test@nodomain.com"
                   :password "foobar"
                   :scope "foobar"}
            out  (th/mutation! data)]

        #_(th/print-result! out)
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :validation))
          (t/is (th/ex-of-code? error :wrong-credentials)))))

    (t/testing "success"
      (let [data {::th/type :login
                   :email "profile1.test@nodomain.com"
                   :password "123123"
                   :scope "foobar"}
            out  (th/mutation! data)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (= (:id profile) (get-in out [:result :id])))))))


(t/deftest profile-query-and-manipulation
  (let [profile (th/create-profile th/*pool* 1)]
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
                        :profile-id (:id profile)
                        ::th/type :update-profile
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


#_(t/deftest profile-deletion
  (let [prof (th/create-profile th/*pool* 1)
        team (:default-team prof)
        proj (:default-project prof)
        file (th/create-file th/*pool* (:id prof) (:id proj) 1)
        page (th/create-page th/*pool* (:id prof) (:id file) 1)]

    ;; (t/testing "try to delete profile not marked for deletion"
    ;;   (let [params {:props {:profile-id (:id prof)}}
    ;;         out (th/try-on! (app.tasks.delete-profile/handler params))]

    ;;     ;; (th/print-result! out)
    ;;     (t/is (nil? (:error out)))
    ;;     (t/is (nil? (:result out)))))

    ;; (t/testing "query profile after delete"
    ;;   (let [data {::sq/type :profile
    ;;               :profile-id (:id prof)}
    ;;         out (th/try-on! (sq/handle data))]

    ;;     ;; (th/print-result! out)
    ;;     (t/is (nil? (:error out)))

    ;;     (let [result (:result out)]
    ;;       (t/is (= (:fullname prof) (:fullname result))))))

    ;; (t/testing "mark profile for deletion"
    ;;   (with-mocks
    ;;     [mock {:target 'app.tasks/schedule! :return nil}]

    ;;     (let [data {::sm/type :delete-profile
    ;;                 :profile-id (:id prof)}
    ;;           out  (th/try-on! (sm/handle data))]
    ;;       ;; (th/print-result! out)
    ;;       (t/is (nil? (:error out)))
    ;;       (t/is (nil? (:result out))))

    ;;     ;; check the mock
    ;;     (let [mock (deref mock)
    ;;           mock-params (second (:call-args mock))]
    ;;       (t/is (true? (:called? mock)))
    ;;       (t/is (= 1 (:call-count mock)))
    ;;       (t/is (= "delete-profile" (:name mock-params)))
    ;;       (t/is (= (:id prof) (get-in mock-params [:props :profile-id]))))))

    ;; (t/testing "query files after profile soft deletion"
    ;;   (let [data {::sq/type :files
    ;;               :project-id (:id proj)
    ;;               :profile-id (:id prof)}
    ;;         out  (th/try-on! (sq/handle data))]
    ;;     ;; (th/print-result! out)
    ;;     (t/is (nil? (:error out)))
    ;;     (t/is (= 1 (count (:result out))))))

    ;; (t/testing "try to delete profile marked for deletion"
    ;;   (let [params {:props {:profile-id (:id prof)}}
    ;;         out (th/try-on! (app.tasks.delete-profile/handler params))]

    ;;     ;; (th/print-result! out)
    ;;     (t/is (nil? (:error out)))
    ;;     (t/is (= (:id prof) (:result out)))))

    ;; (t/testing "query profile after delete"
    ;;   (let [data {::sq/type :profile
    ;;               :profile-id (:id prof)}
    ;;         out (th/try-on! (sq/handle data))]

    ;;     ;; (th/print-result! out)

    ;;     (let [error (:error out)
    ;;           error-data (ex-data error)]
    ;;       (t/is (th/ex-info? error))
    ;;       (t/is (= (:type error-data) :service-error))
    ;;       (t/is (= (:name error-data) :app.services.queries.profile/profile)))

    ;;     (let [error (ex-cause (:error out))
    ;;           error-data (ex-data error)]
    ;;       (t/is (th/ex-info? error))
    ;;       (t/is (= (:type error-data) :not-found)))))

    ;; (t/testing "query files after profile permanent deletion"
    ;;   (let [data {::sq/type :files
    ;;               :project-id (:id proj)
    ;;               :profile-id (:id prof)}
    ;;         out  (th/try-on! (sq/handle data))]
    ;;     ;; (th/print-result! out)
    ;;     (t/is (nil? (:error out)))
    ;;     (t/is (= 0 (count (:result out))))))
    ))


;; (t/deftest registration-domain-whitelist
;;   (let [whitelist "gmail.com, hey.com, ya.ru"]
;;     (t/testing "allowed email domain"
;;       (t/is (true? (profile/email-domain-in-whitelist? whitelist "username@ya.ru")))
;;       (t/is (true? (profile/email-domain-in-whitelist? "" "username@somedomain.com"))))

;;     (t/testing "not allowed email domain"
;;       (t/is (false? (profile/email-domain-in-whitelist? whitelist "username@somedomain.com"))))))

;; TODO: profile deletion with teams
;; TODO: profile deletion with owner teams
;; TODO: profile registration
;; TODO: profile password recovery
