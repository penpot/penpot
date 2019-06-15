(ns uxbox.tests.test-auth
  (:require [clojure.test :as t]
            [promesa.core :as p]
            [clj-http.client :as http]
            [buddy.hashers :as hashers]
            [uxbox.db :as db]
            [uxbox.api :as uapi]
            [uxbox.services.users :as usu]
            [uxbox.services :as usv]
            [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-http-success-auth
  (let [data {:username "user1"
              :fullname "user 1"
              :metadata "1"
              :password  "user1"
              :email "user1@uxbox.io"}
        user (with-open [conn (db/connection)]
               (usu/create-user conn data))]
    (th/with-server {:handler uapi/app}
      (let [data {:username "user1"
                  :password "user1"
                  :metadata "1"
                  :scope "foobar"}
            uri (str th/+base-url+ "/auth/login")
            [status data] (th/http-post uri {:body data})]
        (println "RESPONSE:" status data)
        (t/is (= status 204))))))

(t/deftest test-http-failed-auth
  (let [data {:username "user1"
              :fullname "user 1"
              :metadata "1"
              :password  (hashers/encrypt "user1")
              :email "user1@uxbox.io"}
        user (with-open [conn (db/connection)]
               (usu/create-user conn data))]
    (th/with-server {:handler uapi/app}
      (let [data {:username "user1"
                  :password "user2"
                  :metadata "2"
                  :scope "foobar"}
            uri (str th/+base-url+ "/auth/login")
            [status data] (th/http-post uri {:body data})]
        ;; (prn "RESPONSE:" status data)
        (t/is (= 400 status))
        (t/is (= (:type data) :validation))
        (t/is (= (:code data) :uxbox.services.auth/wrong-credentials))))))

