;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-webhooks-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as http]
   [app.loggers.audit :as audit]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn decode-row
  [{:keys [props context] :as row}]
  (cond-> row
    (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props))
    (db/pgobject? context) (assoc :context (db/decode-transit-pgobject context))))

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

(t/deftest process-event-handler-persists-origin-event-id
  ;; End-to-end regression test for the `webhook/trigger` row: its props
  ;; `:event-id` must hold the origin event row id. It used to be nil
  ;; because `process-event` never forwarded the id to the worker task.
  (with-mocks [submit-mock {:target 'app.worker/submit! :return nil}]
    (with-redefs [cf/flags #{:audit-log :webhooks}]
      (let [prof  (th/create-profile* 1 {:is-active true})
            event {:type "action"
                   :name "delete-project"
                   :profile-id (:id prof)
                   :props {:team-id (:default-team-id prof)}
                   :context {:version "2.0.0" :initiator "app"}
                   :tracked-at (ct/now)
                   :created-at (ct/now)
                   :source "backend"
                   :app.loggers.webhooks/event? true}]
        (audit/submit* th/*system* event)
        (let [params (-> @submit-mock :call-args-list ffirst :app.worker/params)]
          (t/is (some? (:id params)))
          ;; Run the handler the way the worker would.
          (th/run-task! :process-webhook-event params)
          (let [[origin trigger :as rows] (->> (th/db-exec! ["select * from audit_log order by created_at"])
                                               (mapv decode-row))]
            (t/is (= 2 (count rows)))
            (t/is (= "delete-project" (:name origin)))
            (t/is (= "webhook" (:name trigger)))
            (t/is (= "trigger" (:type trigger)))
            (t/is (= "delete-project" (get-in trigger [:props :name])))
            (t/is (= (:id origin) (get-in trigger [:props :event-id])))
            (t/is (= 0 (get-in trigger [:props :total-affected])))))))))

(t/deftest run-webhook-handler-1
  (with-mocks [http-mock {:target 'app.http.client/req :return {:status 200}}]
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
  (with-mocks [http-mock {:target 'app.http.client/req :return {:status 400}}]
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
