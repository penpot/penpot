(ns uxbox.tests.test-icons
  (:require [clojure.test :as t]
            [promesa.core :as p]
            [suricatta.core :as sc]
            [uxbox.db :as db]
            [uxbox.sql :as sql]
            [uxbox.api :as uapi]
            [uxbox.services.icons :as icons]
            [uxbox.services :as usv]
            [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-http-list-icon-collections
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "coll1"}
          coll (icons/create-collection conn data)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icon-collections")
              [status data] (th/http-get user uri)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= 1 (count data))))))))

(t/deftest test-http-create-icon-collection
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icon-collections")
              data {:user (:id user)
                    :name "coll1"}
              params {:body data}
              [status data] (th/http-post user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 201 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "coll1")))))))

(t/deftest test-http-update-icon-collection
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "coll1"}
          coll (icons/create-collection conn data)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icon-collections/" (:id coll))
              params {:body (assoc coll :name "coll2")}
              [status data] (th/http-put user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "coll2")))))))

(t/deftest test-http-icon-collection-delete
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "coll1"
                :data #{1}}
          coll (icons/create-collection conn data)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icon-collections/" (:id coll))
              [status data] (th/http-delete user uri)]
          (t/is (= 204 status))
          (let [sqlv (sql/get-icon-collections {:user (:id user)})
                result (sc/fetch conn sqlv)]
            (t/is (empty? result))))))))

(t/deftest test-http-create-icon
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icons")
              data {:name "sample.jpg"
                    :content "<g></g>"
                    :metadata {:width 200
                               :height 200
                               :view-box [0 0 200 200]}
                    :collection nil}
              params {:body data}
              [status data] (th/http-post user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 201 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "sample.jpg"))
          (t/is (= (:metadata data) {:width 200
                                     :height 200
                                     :view-box [0 0 200 200]})))))))

(t/deftest test-http-update-icon
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "test.svg"
                :content "<g></g>"
                :metadata {}
                :collection nil}
          icon (icons/create-icon conn data)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icons/" (:id icon))
              params {:body (assoc icon :name "my stuff")}
              [status data] (th/http-put user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= (:user data) (:id user)))
          (t/is (= (:name data) "my stuff")))))))

(t/deftest test-http-copy-icon
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "test.svg"
                :content "<g></g>"
                :metadata {}
                :collection nil}
          icon (icons/create-icon conn data)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icons/" (:id icon) "/copy")
              body {:collection nil}
              params {:body body}
              [status data] (th/http-put user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= status 200))
          (let [sqlv (sql/get-icons {:user (:id user) :collection nil})
                result (sc/fetch conn sqlv)]
            (t/is (= 2 (count result)))))))))

(t/deftest test-http-delete-icon
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "test.svg"
                :content "<g></g>"
                :metadata {}
                :collection nil}
          icon (icons/create-icon conn data)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icons/" (:id icon))
              [status data] (th/http-delete user uri)]
          (t/is (= 204 status))
          (let [sqlv (sql/get-icons {:user (:id user) :collection nil})
                result (sc/fetch conn sqlv)]
            (t/is (empty? result))))))))

(t/deftest test-http-list-icons
  (with-open [conn (db/connection)]
    (let [user (th/create-user conn 1)
          data {:user (:id user)
                :name "test.png"
                :content "<g></g>"
                :metadata {}
                :collection nil}
          icon (icons/create-icon conn data)]
      (th/with-server {:handler uapi/app}
        (let [uri (str th/+base-url+ "/api/library/icons")
              [status data] (th/http-get user uri)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= 1 (count data))))))))
