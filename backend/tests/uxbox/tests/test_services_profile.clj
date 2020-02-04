;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tests.test-services-profile
  (:require
   [clojure.test :as t]
   [clojure.java.io :as io]
   [promesa.core :as p]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest login-with-failed-auth
  (let [user @(th/create-user db/pool 1)
        event {::sm/type :login
               :email "user1.test@nodomain.com"
               :password "foobar"
               :scope "foobar"}
        out (th/try-on! (sm/handle event))]

    ;; (th/print-result! out)
    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :service-error)))

    (let [error (ex-cause (:error out))]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :uxbox.services.mutations.profile/wrong-credentials)))))

(t/deftest login-with-success-auth
  (let [user @(th/create-user db/pool 1)
        event {::sm/type :login
               :email "user1.test@nodomain.com"
               :password "123123"
               :scope "foobar"}
        out (th/try-on! (sm/handle event))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (get-in out [:result :id]) (:id user)))))

(t/deftest query-profile
  (let [user @(th/create-user db/pool 1)
        data {::sq/type :profile
              :user (:id user)}

        out (th/try-on! (sq/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= "User 1" (get-in out [:result :fullname])))
    (t/is (= "user1.test@nodomain.com" (get-in out [:result :email])))
    (t/is (not (contains? (:result out) :password)))))

(t/deftest mutation-update-profile
  (let [user @(th/create-user db/pool 1)
        data (assoc user
                    ::sm/type :update-profile
                    :fullname "Full Name"
                    :username "user222"
                    :lang "en")
        out (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (:fullname data) (get-in out [:result :fullname])))
    (t/is (= (:email data) (get-in out [:result :email])))
    (t/is (= (:metadata data) (get-in out [:result :metadata])))
    (t/is (not (contains? (:result out) :password)))))

(t/deftest mutation-update-profile-photo
  (let [user  @(th/create-user db/pool 1)
        data {::sm/type :update-profile-photo
              :user (:id user)
              :file {:name "sample.jpg"
                     :path "tests/uxbox/tests/_files/sample.jpg"
                     :size 123123
                     :mtype "image/jpeg"}}
        out (th/try-on! (sm/handle data))]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (:id user) (get-in out [:result :id])))))

;; (t/deftest test-mutation-register-profile
;;   (let[data {:fullname "Full Name"
;;              :username "user222"
;;              :email "user222@uxbox.io"
;;              :password "user222"
;;              ::sv/type :register-profile}
;;        [err rsp] (th/try-on (sm/handle data))]
;;     (println "RESPONSE:" err rsp)))

;; (t/deftest test-http-validate-recovery-token
;;   (with-open [conn (db/connection)]
;;     (let [user (th/create-user conn 1)]
;;       (with-server {:handler (uft/routes)}
;;         (let [token (#'usu/request-password-recovery conn "user1")
;;               uri1 (str th/+base-url+ "/api/auth/recovery/not-existing")
;;               uri2 (str th/+base-url+ "/api/auth/recovery/" token)
;;               [status1 data1] (th/http-get user uri1)
;;               [status2 data2] (th/http-get user uri2)]
;;           ;; (println "RESPONSE:" status1 data1)
;;           ;; (println "RESPONSE:" status2 data2)
;;           (t/is (= 404 status1))
;;           (t/is (= 204 status2)))))))

;; (t/deftest test-http-request-password-recovery
;;   (with-open [conn (db/connection)]
;;     (let [user (th/create-user conn 1)
;;           sql "select * from user_pswd_recovery"
;;           res (sc/fetch-one conn sql)]

;;       ;; Initially no tokens exists
;;       (t/is (nil? res))

;;       (with-server {:handler (uft/routes)}
;;         (let [uri (str th/+base-url+ "/api/auth/recovery")
;;               data {:username "user1"}
;;               [status data] (th/http-post user uri {:body data})]
;;           ;; (println "RESPONSE:" status data)
;;           (t/is (= 204 status)))

;;         (let [res (sc/fetch-one conn sql)]
;;           (t/is (not (nil? res)))
;;           (t/is (= (:user res) (:id user))))))))

;; (t/deftest test-http-validate-recovery-token
;;   (with-open [conn (db/connection)]
;;     (let [user (th/create-user conn 1)]
;;       (with-server {:handler (uft/routes)}
;;         (let [token (#'usu/request-password-recovery conn (:username user))
;;               uri (str th/+base-url+ "/api/auth/recovery")
;;               data {:token token :password "mytestpassword"}
;;               [status data] (th/http-put user uri {:body data})

;;               user' (usu/find-full-user-by-id conn (:id user))]
;;           (t/is (= status 204))
;;           (t/is (hashers/check "mytestpassword" (:password user'))))))))


