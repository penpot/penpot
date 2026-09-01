;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-webhooks-test
  (:require
   [app.common.time :as ct]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.jobs :as jobs]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest process-event-handler-with-no-webhooks
  (with-mocks [submit-mock {:target 'app.jobs/submit! :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true})
          res  (th/run-task! :process-webhook-event
                             {:event-blob
                              (transit/encode-str
                               {:type "command"
                                :name "create-project"
                                :props {:team-id (:default-team-id prof)}})})]

      (t/is (= 0 (:call-count @submit-mock)))
      (t/is (nil? res)))))

(t/deftest process-event-handler
  (with-mocks [submit-mock {:target 'app.jobs/submit! :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)})
          res  (th/run-task! :process-webhook-event
                             {:event-blob
                              (transit/encode-str
                               {:type "command"
                                :name "create-project"
                                :props {:team-id (:default-team-id prof)}})})]

      (t/is (= 1 (:call-count @submit-mock)))
      (t/is (nil? res)))))

(t/deftest process-event-submits-consumed-config-subset
  (with-mocks [submit-mock {:target 'app.jobs/submit! :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)})]
      (th/run-task! :process-webhook-event
                    {:event-blob
                     (transit/encode-str
                      {:type "command"
                       :name "create-project"
                       :props {:team-id (:default-team-id prof)}})})
      (t/is (= 1 (:call-count @submit-mock)))
      (let [options (second (:call-args @submit-mock))
            config  (get-in options [::jobs/params :config])]
        (t/is (= #{:id :uri :mtype} (set (keys config))))
        (t/is (= (:id whk) (:id config)))
        (t/is (= (:uri whk) (:uri config)))
        (t/is (= (:mtype whk) (:mtype config)))))))

(t/deftest run-webhook-handler-1
  (with-mocks [http-mock {:target 'app.http.client/req :return {:status 200}}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)})
          evt  {:type "command"
                :name "create-project"
                :props {:team-id (:default-team-id prof)}}
          res  (th/run-task! :run-webhook
                             {:event (transit/encode-str evt)
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
                             {:event (transit/encode-str evt)
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
                    {:event (transit/encode-str evt)
                     :config whk})

      (th/run-task! :run-webhook
                    {:event (transit/encode-str evt)
                     :config whk})


      (let [rows (th/db-query :webhook-delivery {:webhook-id (:id whk)})]
        (t/is (= 3 (count rows)))
        (t/is (= "unexpected-status:400" (-> rows first :error-code))))

      ;; Refresh webhook
      (let [whk' (th/db-get :webhook {:id (:id whk)})]
        (t/is (= "unexpected-status:400" (:error-code whk')))
        (t/is (= 3 (:error-count whk')))
        (t/is (false? (:is-active whk')))))))

(t/deftest webhook-json-round-trip-preserves-uuid-types
  "Integration test: drives process-webhook-event through the real pipeline
  (submit → decode → handler) to verify uuid types survive: the JSON hop
  carries only the opaque transit blob, and the handler decodes the typed
  event from it."
  (with-mocks [http-mock {:target 'app.http.client/req :return {:status 200}}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)})
          evt  {:type "command"
                :name "create-project"
                :props {:team-id (:default-team-id prof)}}]

      ;; Submit the job through the real pipeline (this creates a job row)
      (th/run-task! :process-webhook-event
                    {:event-blob (transit/encode-str evt)})

      ;; Now run the pending jobs (simulates dispatcher + runner)
      ;; This will decode the params from JSON and invoke the handler
      (th/run-pending-jobs!)

      ;; The handler should have been invoked and created a webhook_delivery row
      ;; If uuid types were lost, the lookup would fail with a type error
      (let [rows (th/db-query :webhook-delivery {:webhook-id (:id whk)})]
        (t/is (= 1 (count rows)))
        (t/is (nil? (-> rows first :error-code))))

      ;; Refresh webhook
      (let [whk' (th/db-get :webhook {:id (:id whk)})]
        (t/is (nil? (:error-code whk')))))))

(t/deftest webhook-transit-round-trip-preserves-uuid-types
  "The run-webhook event travels as the original transit blob inside the
  JSON job props, so transit deliveries keep instant/UUID types (no wire
  change vs the legacy worker), whatever shape the event has."
  (with-mocks [http-mock {:target 'app.http.client/req :return {:status 200}}]
    (let [prof (th/create-profile* 1 {:is-active true})
          whk  (th/create-webhook* {:team-id (:default-team-id prof)
                                    :mtype "application/transit+json"})
          evt  {:type "command"
                :name "create-project"
                :created-at (ct/now)
                :tracked-at (ct/now)
                :props {:team-id (:default-team-id prof)
                        :comment-id (uuid/next)}}]

      ;; through the real pipeline: process submits run-webhook, the
      ;; runner decodes it from JSON and delivers it
      (th/run-task! :process-webhook-event
                    {:event-blob (transit/encode-str evt)})
      (th/run-pending-jobs!)

      (t/is (= 1 (:call-count @http-mock)))
      (let [req   (second (:call-args @http-mock))
            event (transit/decode-str (:body req))]
        (t/is (= "application/transit+json" (get-in req [:headers "content-type"])))
        (t/is (uuid? (get-in event [:props :team-id])))
        (t/is (uuid? (get-in event [:props :comment-id])))
        (t/is (inst? (:created-at event)))
        (t/is (inst? (:tracked-at event)))))))

