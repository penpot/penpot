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

(t/deftest query-projects
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
        out (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (:name data) (get-in out [:result :name])))))

(t/deftest mutation-update-project
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sm/type :update-project
              :id (:id proj)
              :name "test project mod"
              :user (:id user)}
        out  (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (:id data) (get-in out [:result :id])))
    (t/is (= (:user data) (get-in out [:result :user-id])))
    (t/is (= (:name data) (get-in out [:result :name])))))

(t/deftest mutation-delete-project
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sm/type :delete-project
              :id (:id proj)
              :user (:id user)}
        out  (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (nil? (:result out)))

    (let [sql "select * from projects where user_id=$1 and deleted_at is null"
          res @(db/query db/pool [sql (:id user)])]
      (t/is (empty? res)))))

;; TODO: add permisions related tests
