(ns uxbox.tests.test-services-projects
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.core :as sv]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest query-project-list
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sv/type :projects
              :user (:id user)}
        [err rsp] (th/try-on (sv/query data))]
    (t/is (nil? err))
    (t/is (= 1 (count rsp)))
    (t/is (= (:id proj) (get-in rsp [0 :id])))
    (t/is (= (:name proj (get-in rsp [0 :name]))))))

(t/deftest mutation-create-project
  (let [user @(th/create-user db/pool 1)
        data {::sv/type :create-project
              :user (:id user)
              :name "test project"}
        [err rsp] (th/try-on (sv/mutation data))]
    ;; (prn "RESPONSE:" err rsp)
    (t/is (nil? err))
    (t/is (= (:user data) (:user-id rsp)))
    (t/is (= (:name data) (:name rsp)))))

(t/deftest mutation-update-project
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sv/type :update-project
              :id (:id proj)
              :name "test project mod"
              :user (:id user)}
        [err rsp] (th/try-on (sv/mutation data))]
    ;; (prn "RESPONSE:" err rsp)
    (t/is (nil? err))
    (t/is (= (:id data) (:id rsp)))
    (t/is (= (:user data) (:user-id rsp)))
    (t/is (= (:name data) (:name rsp)))))

(t/deftest mutation-delete-project
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sv/type :delete-project
              :id (:id proj)
              :user (:id user)}
        [err rsp] (th/try-on (sv/mutation data))]
    ;; (prn "RESPONSE:" err rsp)
    (t/is (nil? err))
    (t/is (nil? rsp))

    (let [sql "SELECT * FROM projects
                WHERE user_id=$1 AND deleted_at is null"
          res @(db/query db/pool [sql (:id user)])]
      (t/is (empty? res)))))
