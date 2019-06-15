(ns uxbox.tests.test-users
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [promesa.core :as p]
            [buddy.hashers :as hashers]
            [clj-http.client :as http]
            [suricatta.core :as sc]
            [uxbox.db :as db]
            [uxbox.api :as uapi]
            [uxbox.services.users :as usu]
            [uxbox.services :as usv]
            [uxbox.tests.helpers :as th]))

(t/use-fixtures :each th/database-reset)

(t/deftest test-http-retrieve-profile
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/profile/me")
              [status data] (th/http-get user uri)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= (:fullname data) "User 1"))
          (t/is (= (:username data) "user1"))
          (t/is (= (:metadata data) "1"))
          (t/is (= (:email data) "user1@uxbox.io"))
          (t/is (not (contains? data :password))))))))

(t/deftest test-http-update-profile
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/profile/me")
              data (assoc user
                          :fullname "Full Name"
                          :username "user222"
                          :metadata "222"
                          :email "user222@uxbox.io")
              [status data] (th/http-put user uri {:body data})]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= (:fullname data) "Full Name"))
          (t/is (= (:username data) "user222"))
          (t/is (= (:metadata data) "222"))
          (t/is (= (:email data) "user222@uxbox.io"))
          (t/is (not (contains? data :password))))))))

(t/deftest test-http-update-profile-photo
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/profile/me/photo")
              params [{:name "sample.jpg"
                       :part-name "file"
                       :content (io/input-stream
                                 (io/resource "uxbox/tests/_files/sample.jpg"))}]
              [status data] (th/http-multipart user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 204 status)))))))

;; (t/deftest test-http-register-user
;;   (with-server {:handler (uft/routes)}
;;     (let [uri (str th/+base-url+ "/api/auth/register")
;;           data {:fullname "Full Name"
;;                 :username "user222"
;;                 :email "user222@uxbox.io"
;;                 :password "user222"}
;;           [status data] (th/http-post uri {:body data})]
;;       ;; (println "RESPONSE:" status data)
;;       (t/is (= 200 status)))))

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

