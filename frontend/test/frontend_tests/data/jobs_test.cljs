;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.jobs-test
  "Following a job: the progress the websocket publishes and the outcome
  the row holds."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.jobs :as dj]
   [app.main.repo :as rp]
   [app.util.http :as http]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.async :as hva]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private job-id
  (uuid/next))

(defn- job
  "The row of a job, as `get-job` answers it."
  [status & {:as extra}]
  (merge {:id job-id :status status :name "export-binfile"} extra))

(defn- event
  ([kind payload] (event job-id kind payload))
  ([id kind payload]
   {:type   :job-event
    :job-id id
    :kind   kind
    :payload payload}))

(defn- message
  [payload]
  {:type :message :payload payload})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FOLLOWING A JOB
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest ^:async progress-events-are-emitted-and-the-outcome-ends-the-stream
  (let [ws-stream (rx/subject)
        row       (atom (job "running"))
        seen      (atom [])]

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _] (rx/of @row)))
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done (hva/observe (dj/watch-job nil job-id)
                               {:on-next #(swap! seen conj %)})]

         (rx/push! ws-stream
                   (message (event :progress {:stage    :pages
                                              :counters {:pages {:current 1 :total 2}}})))

         (t/testing "an event of another job is not part of this stream"
           (rx/push! ws-stream
                     (message (event (uuid/next) :progress {:stage :media})))
           (await (hva/settle))
           (t/is (= [{:kind :progress
                      :payload {:stage    :pages
                                :counters {:pages {:current 1 :total 2}}}}]
                    @seen)))

         (t/testing "the closing event makes the stream read the row and end"
           (reset! row (job "completed" :result {:resource-uri "uri"}))
           (rx/push! ws-stream (message (event :end {:outcome "completed"})))
           (await done)

           (t/is (= [{:kind :progress
                      :payload {:stage    :pages
                                :counters {:pages {:current 1 :total 2}}}}
                     {:status "completed" :result {:resource-uri "uri"}}]
                    @seen))))))))

(t/deftest ^:async a-job-that-already-ended-needs-no-event
  (let [ws-stream (rx/subject)
        seen      (atom [])]

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _]
                             (rx/of (job "completed" :result {:resource-uri "uri"}))))
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done (hva/observe (dj/watch-job nil job-id)
                               {:on-next #(swap! seen conj %)})]
         (await done)

         (t/testing "the first read of the row is the outcome"
           (t/is (= [{:status "completed" :result {:resource-uri "uri"}}]
                    @seen))))))))

(t/deftest ^:async a-reconnect-reads-the-row-again
  (let [ws-stream (rx/subject)
        row       (atom (job "running"))
        seen      (atom [])]

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _] (rx/of @row)))
        ws/get-rcv-stream (mock/stub (fn [_] ws-stream))}

       (let [done (hva/observe (dj/watch-job nil job-id)
                               {:on-next #(swap! seen conj %)})]
         (await (hva/settle))

         (t/testing "a job that is still running reports nothing"
           (t/is (= [] @seen)))

         (t/testing "a reconnect answers for a job that failed while away"
           (reset! row (job "failed" :error {:type :internal
                                             :code :boom
                                             :hint "boom"}))
           (rx/push! ws-stream {:type :opened})
           (await done)

           (t/is (= [{:status "failed"
                      :error {:type :internal :code :boom :hint "boom"}}]
                    @seen))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE COMMANDS THAT FEED IT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- capture-requests!
  [requests]
  (fn [request]
    (swap! requests conj request)
    (rx/of {:status  200
            :uri     (:uri request)
            :headers {"content-type" "application/json"}
            :body    "{}"})))

(t/deftest ^:async the-commands-point-at-the-jobs-api
  (let [requests (atom [])]

    (await
     (mock/with-mocks*
       {http/fetch (mock/stub (capture-requests! requests))
        http/response->map (mock/stub identity)
        http/process-response-type (mock/stub (fn [_ response] (rx/of response)))}

       (await (hva/->promise (rp/cmd! :create-export-binfile-job
                                      {:file-ids #{job-id} :export-type :detach-libraries})))
       (await (hva/->promise (rp/cmd! :create-import-binfile-job {:project-id (str job-id)})))
       (await (hva/->promise (rp/cmd! :get-job {:id (str job-id)})))
       (await (hva/->promise (rp/cmd! :create-export-job {})))

       (let [[export import read exporter] @requests]
         (t/testing "the binfile export asks the backend, not the exporter service"
           (t/is (= :post (:method export)))
           (t/is (str/ends-with? (:path (:uri export))
                                 "/api/main/methods/create-export-binfile-job")))

         (t/testing "the import job needs no override: its name is the method"
           (t/is (= :post (:method import)))
           (t/is (str/ends-with? (:path (:uri import))
                                 "/api/main/methods/create-import-binfile-job")))

         (t/testing "the read is a get with the id of the job"
           (t/is (= :get (:method read)))
           (t/is (str/ends-with? (:path (:uri read))
                                 "/api/main/methods/get-job"))
           (t/is (= (str job-id) (:id (:query read)))))

         (t/testing "and the exporter keeps its own name"
           (t/is (= :post (:method exporter)))
           (t/is (str/ends-with? (:path (:uri exporter)) "/api/export/jobs"))))))))
