;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.loggers-webhooks-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest process-event-handler-with-no-webhooks
  (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true})
          res  (th/run-task! :process-webhook-event
                             {:type "command"
                              :name "create-project"
                              :props {:team-id (:default-team-id prof)}})]

      (t/is (= 0 (:call-count @submit-mock)))
      (t/is (nil? res)))))

(t/deftest process-event-handler
  (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)})
          res  (th/run-task! :process-webhook-event
                             {:type "command"
                              :name "create-project"
                              :props {:team-id (:default-team-id prof)}})]

      (t/is (= 1 (:call-count @submit-mock)))
      (t/is (nil? res)))))

(t/deftest run-webhook-handler-1
  (with-mocks [http-mock {:target 'app.http.client/req! :return {:status 200}}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)})
          evt  {:type "command"
                :name "create-project"
                :props {:team-id (:default-team-id prof)}}
          res  (th/run-task! :run-webhook
                             {:event evt
                              :config whk})]

      (t/is (= 1 (:call-count @http-mock)))

      (let [rows (th/db-exec! ["select * from webhook_delivery where webhook_id=?"
                               (:id whk)])]
        (t/is (= 1 (count rows)))
        (t/is (nil? (-> rows first :error-code))))

      ;; Refresh webhook
      (let [whk' (th/db-get :webhook {:id (:id whk)})]
        (t/is (nil? (:error-code whk')))))))

(t/deftest run-webhook-handler-2
  (with-mocks [http-mock {:target 'app.http.client/req! :return {:status 400}}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)})
          evt  {:type "command"
                :name "create-project"
                :props {:team-id (:default-team-id prof)}}
          res  (th/run-task! :run-webhook
                             {:event evt
                              :config whk})]

      (t/is (= 1 (:call-count @http-mock)))

      (let [rows (th/db-query :webhook-delivery {:webhook-id (:id whk)})]
        (t/is (= 1 (count rows)))
        (t/is (= "unexpected-status:400" (-> rows first :error-code))))

      ;; Refresh webhook
      (let [whk' (th/db-get :webhook {:id (:id whk)})]
        (t/is (= "unexpected-status:400" (:error-code whk')))
        (t/is (= 1 (:error-count whk'))))


      ;; RUN 2 times more

      (th/run-task! :run-webhook
                    {:event evt
                     :config whk})

      (th/run-task! :run-webhook
                    {:event evt
                     :config whk})


      (let [rows (th/db-query :webhook-delivery {:webhook-id (:id whk)})]
        (t/is (= 3 (count rows)))
        (t/is (= "unexpected-status:400" (-> rows first :error-code))))

      ;; Refresh webhook
      (let [whk' (th/db-get :webhook {:id (:id whk)})]
        (t/is (= "unexpected-status:400" (:error-code whk')))
        (t/is (= 3 (:error-count whk')))
        (t/is (false? (:is-active whk')))))))
