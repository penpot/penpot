(ns uxbox.tests.test-services-project-pages
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.uuid :as uuid]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest query-project-pages
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        file @(th/create-project-file db/pool (:id user) (:id proj) 1)
        page @(th/create-project-page db/pool (:id user) (:id file) 1)
        data {::sq/type :project-pages
              :file-id (:id file)
              :user (:id user)}
        out (th/try-on! (sq/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (vector? (:result out)))
    (t/is (= 1 (count (:result out))))
    (t/is (= "page1" (get-in out [:result 0 :name])))
    (t/is (:id file) (get-in out [:result 0 :file-id]))))

(t/deftest mutation-create-project-page
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        pf   @(th/create-project-file db/pool (:id user) (:id proj) 1)

        data {::sm/type :create-project-page
              :data {:canvas []
                     :shapes []
                     :shapes-by-id {}}
              :metadata {}
              :file-id (:id pf)
              :ordering 1
              :name "test page"
              :user (:id user)}
        out (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (uuid? (get-in out [:result :id])))
    (t/is (= (:user data) (get-in out [:result :user-id])))
    (t/is (= (:name data) (get-in out [:result :name])))
    (t/is (= (:data data) (get-in out [:result :data])))
    (t/is (= (:metadata data) (get-in out [:result :metadata])))
    (t/is (= 0 (get-in out [:result :version])))))

(t/deftest mutation-update-project-page-data
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        file @(th/create-project-file db/pool (:id user) (:id proj) 1)
        page @(th/create-project-page db/pool (:id user) (:id file) 1)
        data {::sm/type :update-project-page-data
              :id (:id page)
              :data {:shapes [(uuid/next)]
                     :canvas []
                     :shapes-by-id {}}
              :file-id (:id file)
              :user (:id user)}
        out (th/try-on! (sm/handle data))]

    ;; (th/print-result! out)
    ;; TODO: check history creation
    ;; TODO: check correct page data update operation

    (t/is (nil? (:error out)))
    (t/is (= (:id data) (get-in out [:result :id])))
    (t/is (= 1 (get-in out [:result :version])))))

(t/deftest mutation-update-project-page-1
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        file @(th/create-project-file db/pool (:id user) (:id proj) 1)
        page @(th/create-project-page db/pool (:id user) (:id file) 1)

        data {::sm/type :update-project-page
              :id (:id page)
              :version 99
              :user (:id user)
              :operations []}

        out (th/try-on! (sm/handle data))]

    ;; (th/print-result! out)

    (let [error (:error out)]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :service-error)))

    (let [error (ex-cause (:error out))]
      (t/is (th/ex-info? error))
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :version-conflict)))))

(t/deftest mutation-update-project-page-2
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        file @(th/create-project-file db/pool (:id user) (:id proj) 1)
        page @(th/create-project-page db/pool (:id user) (:id file) 1)

        sid  (uuid/next)
        data {::sm/type :update-project-page
              :id (:id page)
              :version 0
              :user (:id user)
              :operations [[:add-shape sid {:id sid :type :rect}]]}

        out (th/try-on! (sm/handle data))]

    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (= 0 (count (:result out))))
    ;; (t/is (= 1 (count (:result out))))
    ;; (t/is (= (:id data) (get-in out [:result 0 :page-id])))
    ;; (t/is (= 1 (count (get-in out [:result 0 :operations]))))
    ;; (t/is (= :add-shape (get-in out [:result 0 :operations 0 0])))
    ;; (t/is (= sid (get-in out [:result 0 :operations 0 1])))
    ))

(t/deftest mutation-update-project-page-3
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        file @(th/create-project-file db/pool (:id user) (:id proj) 1)
        page @(th/create-project-page db/pool (:id user) (:id file) 1)

        sid  (uuid/next)
        data {::sm/type :update-project-page
              :id (:id page)
              :version 0
              :user (:id user)
              :operations [[:add-shape sid {:id sid :type :rect}]]}

        out1 (th/try-on! (sm/handle data))
        out2 (th/try-on! (sm/handle data))]

    ;; (th/print-result! out1)
    ;; (th/print-result! out2)

    (t/is (nil? (:error out1)))
    (t/is (nil? (:error out2)))
    (t/is (= 0 (count (:result out1))))
    (t/is (= 1 (count (:result out2))))
    (t/is (= (:id data) (get-in out2 [:result 0 :page-id])))
    (t/is (= 1 (count (get-in out2 [:result 0 :operations]))))
    (t/is (= :add-shape (get-in out2 [:result 0 :operations 0 0])))
    (t/is (= sid (get-in out2 [:result 0 :operations 0 1])))
    ))

(t/deftest mutation-delete-project-page
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        file @(th/create-project-file db/pool (:id user) (:id proj) 1)
        page @(th/create-project-page db/pool (:id user) (:id file) 1)
        data {::sm/type :delete-project-page
              :id (:id page)
              :user (:id user)}
        out (th/try-on! (sm/handle data))]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (t/is (nil? (:result out)))))
