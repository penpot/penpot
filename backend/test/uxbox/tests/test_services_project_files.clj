(ns uxbox.tests.test-services-project-files
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

(t/deftest query-project-files
  (let [user @(th/create-user db/pool 2)
        proj @(th/create-project db/pool (:id user) 1)
        pf   @(th/create-project-file db/pool (:id user) (:id proj) 1)
        pp   @(th/create-project-page db/pool (:id user) (:id pf) 1)
        data {::sq/type :project-files
              :user (:id user)
              :project-id (:id proj)}
        out (th/try-on! (sq/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= 1 (count (:result out))))
    (t/is (= (:id pf)   (get-in out [:result 0 :id])))
    (t/is (= (:id proj) (get-in out [:result 0 :project-id])))
    (t/is (= (:name pf) (get-in out [:result 0 :name])))
    (t/is (= [(:id pp)] (get-in out [:result 0 :pages])))))

(t/deftest mutation-create-project-file
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sm/type :create-project-file
              :user (:id user)
              :name "test file"
              :project-id (:id proj)}
        out (th/try-on! (sm/handle data))
        ]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= (:name data) (get-in out [:result :name])))
    #_(t/is (= (:project-id data) (get-in out [:result :project-id])))))

(t/deftest mutation-update-project-file
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        pf   @(th/create-project-file db/pool (:id user) (:id proj) 1)
        data {::sm/type :update-project-file
              :id (:id pf)
              :name "new file name"
              :user (:id user)}
        out  (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    ;; TODO: check the result
    (t/is (nil? (:error out)))
    (t/is (nil? (:result out)))))

(t/deftest mutation-delete-project-file
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        pf   @(th/create-project-file db/pool (:id user) (:id proj) 1)
        data {::sm/type :delete-project-file
              :id (:id pf)
              :user (:id user)}
        out  (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (nil? (:result out)))

    (let [sql "select * from project_files
                where project_id=$1 and deleted_at is null"
          res @(db/query db/pool [sql (:id proj)])]
      (t/is (empty? res)))))

;; ;; TODO: add permisions related tests
