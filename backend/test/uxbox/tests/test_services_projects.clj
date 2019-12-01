(ns uxbox.tests.test-services-projects
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; TODO: migrate from try-on to try-on!

(t/deftest query-project-list
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sq/type :projects
              :user (:id user)}
        out (th/try-on! (sq/handle data))]

    ;; (th/print-result! out)

    (t/is (nil? (:error out)))
    (t/is (= 1 (count (:result out))))
    (t/is (= (:id proj) (get-in out [:result 0 :id])))
    (t/is (= (:name proj) (get-in out [:result 0 :name])))))

(t/deftest mutation-create-project
  (let [user @(th/create-user db/pool 1)
        data {::sm/type :create-project
              :user (:id user)
              :name "test project"}
        [err rsp] (th/try-on (sm/handle data))]
    ;; (prn "RESPONSE:" err rsp)
    (t/is (nil? err))
    (t/is (= (:user data) (:user-id rsp)))
    (t/is (= (:name data) (:name rsp)))))

(t/deftest mutation-update-project
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sm/type :update-project
              :id (:id proj)
              :name "test project mod"
              :user (:id user)}
        [err rsp] (th/try-on (sm/handle data))]
    ;; (prn "RESPONSE:" err rsp)
    (t/is (nil? err))
    (t/is (= (:id data) (:id rsp)))
    (t/is (= (:user data) (:user-id rsp)))
    (t/is (= (:name data) (:name rsp)))))

(t/deftest mutation-delete-project
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sm/type :delete-project
              :id (:id proj)
              :user (:id user)}
        [err rsp] (th/try-on (sm/handle data))]
    ;; (prn "RESPONSE:" err rsp)
    (t/is (nil? err))
    (t/is (nil? rsp))

    (let [sql "SELECT * FROM projects
                WHERE user_id=$1 AND deleted_at is null"
          res @(db/query db/pool [sql (:id user)])]
      (t/is (empty? res)))))
