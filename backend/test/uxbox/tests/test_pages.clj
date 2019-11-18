(ns uxbox.tests.test-pages
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.core :as sv]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-mutation-create-page
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        data {::sv/type :create-page
              :data {:shapes []}
              :metadata {}
              :project-id (:id proj)
              :name "test page"
              :user (:id user)}
        [err rsp] (th/try-on (sv/mutation data))]
    (t/is (nil? err))
    (t/is (uuid? (:id rsp)))
    (t/is (= (:user data) (:user-id rsp)))
    (t/is (= (:name data) (:name rsp)))
    (t/is (= (:data data) (:data rsp)))
    (t/is (= (:metadata data) (:metadata rsp)))))

(t/deftest test-mutation-update-page
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        page @(th/create-page db/pool (:id user) (:id proj) 1)
        data {::sv/type :update-page
              :id (:id page)
              :data {:shapes [1 2 3]}
              :metadata {:foo 2}
              :project-id (:id proj)
              :name "test page"
              :user (:id user)}
        res (th/try-on! (sv/mutation data))]

    ;; (th/print-result! res)

    (t/is (nil? (:error res)))
    (t/is (= (:id data) (get-in res [:result :id])))
    (t/is (= (:user data) (get-in res [:result :user-id])))
    (t/is (= (:name data) (get-in res [:result :name])))
    (t/is (= (:data data) (get-in res [:result :data])))
    (t/is (= (:metadata data) (get-in res [:result :metadata])))))

(t/deftest test-mutation-update-page-metadata
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        page @(th/create-page db/pool (:id user) (:id proj) 1)
        data {::sv/type :update-page
              :id (:id page)
              :metadata {:foo 2}
              :project-id (:id proj)
              :name "test page"
              :user (:id user)}
        res (th/try-on! (sv/mutation data))]

    ;; (th/print-result! res)
    (t/is (nil? (:error res)))
    (t/is (= (:id data) (get-in res [:result :id])))
    (t/is (= (:user data) (get-in res [:result :user-id])))
    (t/is (= (:name data) (get-in res [:result :name])))
    (t/is (= (:metadata data) (get-in res [:result :metadata])))))

(t/deftest test-mutation-delete-page
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        page @(th/create-page db/pool (:id user) (:id proj) 1)
        data {::sv/type :delete-page
              :id (:id page)
              :user (:id user)}
        res (th/try-on! (sv/mutation data))]

    ;; (th/print-result! res)
    (t/is (nil? (:error res)))
    (t/is (nil? (:result res)))))

(t/deftest test-query-pages-by-project
  (let [user @(th/create-user db/pool 1)
        proj @(th/create-project db/pool (:id user) 1)
        page @(th/create-page db/pool (:id user) (:id proj) 1)
        data {::sv/type :pages-by-project
              :project-id (:id proj)
              :user (:id user)}
        res (th/try-on! (sv/query data))]

    (th/print-result! res)
    (t/is (nil? (:error res)))
    (t/is (vector? (:result res)))
    (t/is (= 1 (count (:result res))))
    (t/is (= "page1" (get-in res [:result 0 :name])))
    (t/is (:id proj) (get-in res [:result 0 :project-id]))))

;; (t/deftest test-http-page-history-update
;;   (with-open [conn (db/connection)]
;;     (let [user (th/create-user conn 1)
;;           proj (uspr/create-project conn {:user (:id user) :name "proj1"})
;;           data {:id (uuid/random)
;;                 :user (:id user)
;;                 :project (:id proj)
;;                 :version 0
;;                 :data "1"
;;                 :metadata "2"
;;                 :name "page1"
;;                 :width 200
;;                 :height 200
;;                 :layout "mobil"}
;;           page (uspg/create-page conn data)]

;;       (dotimes [i 10]
;;         (let [page (uspg/get-page-by-id conn (:id data))]
;;           (uspg/update-page conn (assoc page :data (str i)))))

;;       ;; Check inserted history
;;       (let [sql (str "SELECT * FROM pages_history "
;;                      " WHERE page=? ORDER BY created_at DESC")
;;             result (sc/fetch conn [sql (:id data)])
;;             item (first result)]

;;         (th/with-server {:handler @http/app}
;;           (let [uri (str th/+base-url+
;;                          "/api/pages/" (:id page)
;;                          "/history/" (:id item))
;;                 params {:body {:label "test" :pinned true}}
;;                 [status data] (th/http-put user uri params)]
;;             ;; (println "RESPONSE:" status data)
;;             (t/is (= 200 status))
;;             (t/is (= (:id data) (:id item))))))

;;       (let [sql (str "SELECT * FROM pages_history "
;;                      " WHERE page=? AND pinned = true "
;;                      " ORDER BY created_at DESC")
;;             result (sc/fetch-one conn [sql (:id data)])]
;;         (t/is (= "test" (:label result)))
;;         (t/is (= true (:pinned result)))))))
