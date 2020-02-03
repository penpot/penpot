(ns uxbox.tests.test-services-users
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

(t/deftest test-query-profile
  (let [user @(th/create-user db/pool 1)
        data {::sq/type :profile
              :user (:id user)}

        out (th/try-on! (sq/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= "User 1" (get-in out [:result :fullname])))
    (t/is (= "user1" (get-in out [:result :username])))
    (t/is (= "user1.test@uxbox.io" (get-in out [:result :email])))
    (t/is (not (contains? (:result out) :password)))))

(t/deftest test-mutation-update-profile
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
    (t/is (= (:username data) (get-in out [:result :username])))
    (t/is (= (:email data) (get-in out [:result :email])))
    (t/is (= (:metadata data) (get-in out [:result :metadata])))
    (t/is (not (contains? (:result out) :password)))))

;; (t/deftest test-mutation-update-profile-photo
;;   (let [user  @(th/create-user db/pool 1)
;;         data {::sm/type :update-profile-photo
;;               :user (:id user)
;;               :file {:name "sample.jpg"
;;                      :path (fs/path "test/uxbox/tests/_files/sample.jpg")
;;                      :size 123123
;;                      :mtype "image/jpeg"}}

;;         out (th/try-on! (sm/handle data))]
;;     ;; (th/print-result! out)
;;     (t/is (nil? (:error out)))
;;     (t/is (= (:id user) (get-in out [:result :id])))
;;     (t/is (str/starts-with? (get-in out [:result :photo]) "http"))))

;; (t/deftest test-mutation-register-profile
;;   (let[data {:fullname "Full Name"
;;              :username "user222"
;;              :email "user222@uxbox.io"
;;              :password "user222"
;;              ::sv/type :register-profile}
;;        [err rsp] (th/try-on (sm/handle data))]
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

