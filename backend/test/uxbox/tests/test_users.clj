(ns uxbox.tests.test-users
  (:require
   [clojure.test :as t]
   [clojure.java.io :as io]
   [promesa.core :as p]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   ;; [buddy.hashers :as hashers]
   [vertx.core :as vc]
   [uxbox.db :as db]
   [uxbox.services.core :as sv]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-query-profile
  (let [user @(th/create-user db/pool 1)
        event {::sv/type :profile
               :user (:id user)}
        [err rsp] (th/try-on (sv/query event))]
    ;; (println "RESPONSE:" resp)))
    (t/is (nil? err))
    (t/is (= (:fullname rsp) "User 1"))
    (t/is (= (:username rsp) "user1"))
    (t/is (= (:metadata rsp) {}))
    (t/is (= (:email rsp) "user1.test@uxbox.io"))
    (t/is (not (contains? rsp :password)))))

(t/deftest test-mutation-update-profile
  (let [user  @(th/create-user db/pool 1)
        event (assoc user
                     ::sv/type :update-profile
                     :fullname "Full Name"
                     :username "user222"
                     :metadata {:foo "bar"}
                     :email "user222@uxbox.io")
        [err data] (th/try-on (sv/mutation event))]
    ;; (println "RESPONSE:" err data)
    (t/is (nil? err))
    (t/is (= (:fullname data) "Full Name"))
    (t/is (= (:username data) "user222"))
    (t/is (= (:metadata data) {:foo "bar"}))
    (t/is (= (:email data) "user222@uxbox.io"))
    (t/is (not (contains? data :password)))))

(t/deftest test-mutation-update-profile-photo
  (let [user  @(th/create-user db/pool 1)
        event {::sv/type :update-profile-photo
               :user (:id user)
               :file {:name "sample.jpg"
                      :path (fs/path "test/uxbox/tests/_files/sample.jpg")
                      :size 123123
                      :mtype "image/jpeg"}}
        [err rsp] (th/try-on (sv/mutation event))]
    ;; (prn "RESPONSE:" [err rsp])
    (t/is (nil? err))
    (t/is (= (:id user) (:id rsp)))
    (t/is (str/starts-with? (:photo rsp) "http"))))

;; (t/deftest test-mutation-register-profile
;;   (let[data {:fullname "Full Name"
;;              :username "user222"
;;              :email "user222@uxbox.io"
;;              :password "user222"
;;              ::sv/type :register-profile}
;;        [err rsp] (th/try-on (sv/mutation data))]
;;     (println "RESPONSE:" err rsp)))

;; ;; (t/deftest test-http-validate-recovery-token
;; ;;   (with-open [conn (db/connection)]
;; ;;     (let [user (th/create-user conn 1)]
;; ;;       (with-server {:handler (uft/routes)}
;; ;;         (let [token (#'usu/request-password-recovery conn "user1")
;; ;;               uri1 (str th/+base-url+ "/api/auth/recovery/not-existing")
;; ;;               uri2 (str th/+base-url+ "/api/auth/recovery/" token)
;; ;;               [status1 data1] (th/http-get user uri1)
;; ;;               [status2 data2] (th/http-get user uri2)]
;; ;;           ;; (println "RESPONSE:" status1 data1)
;; ;;           ;; (println "RESPONSE:" status2 data2)
;; ;;           (t/is (= 404 status1))
;; ;;           (t/is (= 204 status2)))))))

;; ;; (t/deftest test-http-request-password-recovery
;; ;;   (with-open [conn (db/connection)]
;; ;;     (let [user (th/create-user conn 1)
;; ;;           sql "select * from user_pswd_recovery"
;; ;;           res (sc/fetch-one conn sql)]

;; ;;       ;; Initially no tokens exists
;; ;;       (t/is (nil? res))

;; ;;       (with-server {:handler (uft/routes)}
;; ;;         (let [uri (str th/+base-url+ "/api/auth/recovery")
;; ;;               data {:username "user1"}
;; ;;               [status data] (th/http-post user uri {:body data})]
;; ;;           ;; (println "RESPONSE:" status data)
;; ;;           (t/is (= 204 status)))

;; ;;         (let [res (sc/fetch-one conn sql)]
;; ;;           (t/is (not (nil? res)))
;; ;;           (t/is (= (:user res) (:id user))))))))

;; ;; (t/deftest test-http-validate-recovery-token
;; ;;   (with-open [conn (db/connection)]
;; ;;     (let [user (th/create-user conn 1)]
;; ;;       (with-server {:handler (uft/routes)}
;; ;;         (let [token (#'usu/request-password-recovery conn (:username user))
;; ;;               uri (str th/+base-url+ "/api/auth/recovery")
;; ;;               data {:token token :password "mytestpassword"}
;; ;;               [status data] (th/http-put user uri {:body data})

;; ;;               user' (usu/find-full-user-by-id conn (:id user))]
;; ;;           (t/is (= status 204))
;; ;;           (t/is (hashers/check "mytestpassword" (:password user'))))))))

