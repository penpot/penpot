;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-webhooks-test
  (:require
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn create-webhook-params [id team]
  {::th/type :create-webhook
   ::rpc/profile-id id
   :team-id team
   :uri (u/uri "http://example.com")
   :mtype "application/json"})

(defn check-webhook-format
  ([result]
   (t/is (contains? result :id))
   (t/is (contains? result :team-id))
   (t/is (contains? result :created-at))
   (t/is (contains? result :profile-id))
   (t/is (contains? result :updated-at))
   (t/is (contains? result :uri))
   (t/is (contains? result :mtype))))

(t/deftest webhook-crud
  (with-mocks [http-mock {:target 'app.http.client/req!
                          :return {:status 200}}]

    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)
          whook   (volatile! nil)]

      (t/testing "create webhook"
        (let [params {::th/type :create-webhook
                      ::rpc/profile-id (:id prof)
                      :team-id team-id
                      :uri (u/uri "http://example.com")
                      :mtype "application/json"}
              out    (th/command! params)]

          (t/is (nil? (:error out)))
          (t/is (= 1 (:call-count @http-mock)))

          (let [result (:result out)]
            (check-webhook-format result)

            (t/is (= (:uri params) (:uri result)))
            (t/is (= (:team-id params) (:team-id result)))
            (t/is (= (:mtype params) (:mtype result)))
            (vreset! whook result))))

      (th/reset-mock! http-mock)

      (t/testing "update webhook 1 (success)"
        (let [params {::th/type :update-webhook
                      ::rpc/profile-id (:id prof)
                      :id (:id @whook)
                      :uri (:uri @whook)
                      :mtype "application/transit+json"
                      :is-active false}
              out    (th/command! params)]

          (t/is (nil? (:error out)))
          (t/is (= 0 (:call-count @http-mock)))

          (let [result (:result out)]
            (check-webhook-format result)

            (t/is (= (:id params)  (:id result)))
            (t/is (= (:id @whook)  (:id result)))
            (t/is (= (:uri params) (:uri result)))
            (t/is (= (:team-id @whook) (:team-id result)))
            (t/is (= (:mtype params) (:mtype result))))))

      (th/reset-mock! http-mock)

      (t/testing "update webhook 2 (change uri)"
        (let [params {::th/type :update-webhook
                      ::rpc/profile-id (:id prof)
                      :id (:id @whook)
                      :uri (str (:uri @whook) "/test")
                      :mtype "application/transit+json"
                      :is-active false}
              out    (th/command! params)]

          (t/is (nil? (:error out)))
          (t/is (map? (:result out)))
          (t/is (= 1 (:call-count @http-mock)))))

      (th/reset-mock! http-mock)

      (t/testing "update webhook 3 (not authorized)"
        (let [params {::th/type :update-webhook
                      ::rpc/profile-id uuid/zero
                      :id (:id @whook)
                      :uri (str (:uri @whook) "/test")
                      :mtype "application/transit+json"
                      :is-active false}
              out    (th/command! params)]

          (t/is (= 0 (:call-count @http-mock)))
          (let [error      (:error out)
                error-data (ex-data error)]
            (t/is (th/ex-info? error))
            (t/is (= (:type error-data) :not-found))
            (t/is (= (:code error-data) :object-not-found)))))

      (th/reset-mock! http-mock)

      (t/testing "delete webhook (success)"
        (let [params {::th/type :delete-webhook
                      ::rpc/profile-id (:id prof)
                      :id (:id @whook)}
              out    (th/command! params)]

          (t/is (= 0 (:call-count @http-mock)))
          (t/is (nil? (:error out)))
          (t/is (nil? (:result out)))

          (let [rows (th/db-exec! ["select * from webhook"])]
            (t/is (= 0 (count rows))))))

      (th/reset-mock! http-mock)

      (t/testing "delete webhook (unauthorized)"
        (let [params {::th/type :delete-webhook
                      ::rpc/profile-id uuid/zero
                      :id (:id @whook)}
              out    (th/command! params)]

          (t/is (= 0 (:call-count @http-mock)))
          (let [error      (:error out)
                error-data (ex-data error)]
            (t/is (th/ex-info? error))
            (t/is (= (:type error-data) :not-found))
            (t/is (= (:code error-data) :object-not-found))))))))

(t/deftest webhooks-permissions-crud-viewer-only
  (with-mocks [http-mock {:target 'app.http.client/req!
                          :return {:status 200}}]
    (let [owner       (th/create-profile* 1 {:is-active true})
          viewer      (th/create-profile* 2 {:is-active true})
          team        (th/create-team* 1 {:profile-id (:id owner)})
          whook       (volatile! nil)]
      (th/create-team-role* {:team-id (:id team)
                             :profile-id (:id viewer)
                             :role :viewer})
      ;; Assert all roles for team
      (let [roles (th/db-query :team-profile-rel {:team-id (:id team)})]
        (t/is (= 2 (count roles))))

      (t/testing "viewer creates a webhook"
        (let [viewers-webhook (create-webhook-params (:id viewer) (:id team))
              out             (th/command! viewers-webhook)]
          (t/is (nil? (:error out)))
          (t/is (= 1 (:call-count @http-mock)))

          (let [result (:result out)]
            (check-webhook-format result)
            (t/is (= (:uri viewers-webhook) (:uri result)))
            (t/is (= (:team-id viewers-webhook) (:team-id result)))
            (t/is (= (::rpc/profile-id viewers-webhook) (:profile-id result)))
            (t/is (= (:mtype viewers-webhook) (:mtype result)))
            (vreset! whook result))))

      (th/reset-mock! http-mock)

      (t/testing "viewer updates it's own webhook (success)"
        (let [params {::th/type :update-webhook
                      ::rpc/profile-id (:id viewer)
                      :id (:id @whook)
                      :uri (:uri @whook)
                      :mtype "application/transit+json"
                      :is-active false}
              out    (th/command! params)
              result (:result out)]

          (t/is (nil? (:error out)))
          (t/is (= 0 (:call-count @http-mock)))
          (check-webhook-format result)
          (t/is (= (:is-active params) (:is-active result)))
          (t/is (= (:team-id @whook) (:team-id result)))
          (t/is (= (:mtype params) (:mtype result)))
          (vreset! whook result)))

      (th/reset-mock! http-mock)

      (t/testing "viewer deletes it's own webhook (success)"
        (let [params {::th/type :delete-webhook
                      ::rpc/profile-id (:id viewer)
                      :id (:id @whook)}
              out    (th/command! params)]
          (t/is (= 0 (:call-count @http-mock)))
          (t/is (nil? (:error out)))
          (t/is (nil? (:result out)))
          (let [rows (th/db-exec! ["select * from webhook"])]
            (t/is (= 0 (count rows))))))

      (th/reset-mock! http-mock))))

(t/deftest webhooks-permissions-crud-viewer-owner
  (with-mocks [http-mock {:target 'app.http.client/req!
                          :return {:status 200}}]
    (let [owner       (th/create-profile* 1 {:is-active true})
          viewer      (th/create-profile* 2 {:is-active true})
          team        (th/create-team* 1 {:profile-id (:id owner)})
          whook       (volatile! nil)]
      (th/create-team-role* {:team-id (:id team)
                             :profile-id (:id viewer)
                             :role :viewer})
      (t/testing "owner creates a wehbook"
        (let [owners-webhook  (create-webhook-params (:id owner) (:id team))
              out    (th/command! owners-webhook)
              result (:result out)]
          (t/is (nil? (:error out)))
          (t/is (= 1 (:call-count @http-mock)))
          (check-webhook-format result)
          (t/is (= (:uri owners-webhook) (:uri result)))
          (t/is (= (:team-id owners-webhook) (:team-id result)))
          (t/is (= (:mtype owners-webhook) (:mtype result)))
          (vreset! whook result)))

      (th/reset-mock! http-mock)

      (t/testing "viewer updates owner's webhook (unauthorized)"
        (let [params {::th/type :update-webhook
                      ::rpc/profile-id (:id viewer)
                      :id (:id @whook)
                      :uri (str (:uri @whook) "/test")
                      :mtype "application/transit+json"
                      :is-active false}
              out    (th/command! params)]

          (t/is (= 0 (:call-count @http-mock)))

          (let [error      (:error out)
                error-data (ex-data error)]
            (t/is (th/ex-info? error))
            (t/is (= (:type error-data) :not-found))
            (t/is (= (:code error-data) :object-not-found)))))

      (th/reset-mock! http-mock)

      (t/testing "viewer deletes owner's webhook (unauthorized)"
        (let [params {::th/type :delete-webhook
                      ::rpc/profile-id (:id viewer)
                      :id (:id @whook)}
              out    (th/command! params)
              error      (:error out)
              error-data (ex-data error)]
          (t/is (= 0 (:call-count @http-mock)))
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found))
          (t/is (= (:code error-data) :object-not-found)))))))

(t/deftest webhooks-quotes
  (with-mocks [http-mock {:target 'app.http.client/req!
                          :return {:status 200}}]

    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          params  {::th/type :create-webhook
                   ::rpc/profile-id (:id prof)
                   :team-id team-id
                   :uri "http://example.com"
                   :mtype "application/json"}
          out1    (th/command! params)
          out2    (th/command! params)
          out3    (th/command! params)
          out4    (th/command! params)
          out5    (th/command! params)
          out6    (th/command! params)
          out7    (th/command! params)
          out8    (th/command! params)
          out9    (th/command! params)]

      (t/is (= 8 (:call-count @http-mock)))
      (t/is (nil? (:error out1)))
      (t/is (nil? (:error out2)))
      (t/is (nil? (:error out3)))
      (t/is (nil? (:error out4)))
      (t/is (nil? (:error out5)))
      (t/is (nil? (:error out6)))
      (t/is (nil? (:error out7)))
      (t/is (nil? (:error out8)))

      (let [error      (:error out9)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= (:type error-data) :restriction))
        (t/is (= (:code error-data) :webhooks-quote-reached))))))
