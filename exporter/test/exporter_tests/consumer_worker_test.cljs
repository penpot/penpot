;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.consumer-worker-test
  "The queue loop of the new tree: the payload it reads, the claim it
  makes, and the handoff to the runner, over a fake redis connection
  and a fake management http."
  (:require
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [app.consumer.api :as api]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as cstr]
   [exporter.consumer :as consumer]
   [exporter.consumer.worker :as worker]))

;; ---- THE MANAGEMENT FAKE

(def ^:private calls
  "One entry per management call: [method, body]."
  (atom []))

(defn- fake-fetch
  [respond]
  (reset! calls [])
  (let [original api/fetch]
    (set! api/fetch
          (fn [uri request]
            (let [method (last (cstr/split (str uri) "/"))
                  body   (transit/decode-str (.-body request))]
              (swap! calls conj [method body])
              (js/Promise.resolve #js {:status 200
                                       :text   (constantly
                                                (js/Promise.resolve
                                                 (transit/encode-str
                                                  (respond method body))))}))))
    (fn [] (set! api/fetch original))))

;; ---- THE QUEUE FAKE

(defn- fake-conn
  [queue]
  #js {:blpop (fn [_key _timeout-s]
                (if-let [payload (first @queue)]
                  (do
                    (swap! queue rest)
                    (js/Promise.resolve #js ["the.queue.key" payload]))
                  (js/Promise.resolve nil)))
       :quit  (fn [] (js/Promise.resolve nil))})

(defn- encoded-claim
  [job-id scheduled-at]
  (js/JSON.stringify #js [(str job-id) scheduled-at]))

(defn- test-cfg
  [extra]
  (merge {:concurrency 1
          :queue-key   "the.queue.key"
          :render      {}}
         extra))

(t/deftest read-decodes-the-dispatcher-payload
  (t/testing "a payload in shape names the job and when it was pushed"
    (let [job-id (uuid/next)]
      (t/is (= {:job-id       (str job-id)
                :scheduled-at "2026-10-07T09:00:00Z"}
               (worker/read-payload (encoded-claim job-id
                                                   "2026-10-07T09:00:00Z"))))))
  (t/testing "a corrupt payload is dropped, not thrown at the loop"
    (t/is (nil? (worker/read-payload "{not a payload")))))

(t/deftest ^:async poll-once-awaits-what-it-claims
  (try
    (let [queue   (atom [(encoded-claim (uuid/next) "2026-10-07T09:00:00Z")])
          conn    (fake-conn queue)
          handled (atom [])]
      (await (worker/poll-once conn
                               "the.queue.key"
                               (fn [claim]
                                 (swap! handled conj claim)
                                 (js/Promise.resolve nil))))
      (t/is (= 1 (count @handled))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async poll-once-drops-empty-pops-and-corrupt-payloads
  (try
    (let [queue (atom ["{corrupt"])
          conn  (fake-conn queue)]
      (await (worker/poll-once conn
                               "the.queue.key"
                               (fn [_claim]
                                 (t/is false "corrupt must not be handled"))))
      (await (worker/poll-once conn
                               "the.queue.key"
                               (fn [_claim]
                                 (t/is false "a corrupt pop has nothing to handle"))))
      (t/is (empty? @queue)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async process-drops-a-claim-the-worker-never-won
  (let [restore-fetch (fake-fetch
                       (fn [method _body]
                         (case method
                           "claim-job" {:action :skip :status "cancelled"}
                           {:action :run})))]
    (try
      (await (worker/process (test-cfg {})
                             {:job-id      (uuid/next)
                              :scheduled-at "2026-10-07T09:00:00Z"}))
      (t/testing "only the claim traveled: no run, no render"
        (t/is (= ["claim-job"] (mapv first @calls))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async process-hands-the-claim-to-the-runner
  (let [restore-fetch (fake-fetch
                       (fn [method _body]
                         (case method
                           "claim-job" {:action :run
                                        :name   "export-assets"
                                        :params {:exports [] :name "x"}}
                           {:action :run})))
        ran           (atom nil)
        restore-run   (let [original consumer/run-export]
                        (set! consumer/run-export
                              (fn [cfg claim params]
                                (reset! ran [cfg claim params])
                                (js/Promise.resolve nil)))
                        (fn [] (set! consumer/run-export original)))
        render        {:some "render-cfg"}]
    (try
      (let [job-id (uuid/next)]
        (await (worker/process (test-cfg {:render render})
                               {:job-id      (str job-id)
                                :scheduled-at "2026-10-07T09:00:00Z"}))
        (t/testing "the runner got the render view, the job and the params"
          (t/is (= render (nth @ran 0)))
          (t/is (= {:job-id (str job-id)} (nth @ran 1)))
          (t/is (= {:exports [] :name "x"} (nth @ran 2)))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)
        (restore-run)))))

(t/deftest ^:async start-opens-one-connection-per-poller-and-stop-quits-them
  (try
    (let [opened (atom [])
          quit   (atom [])
          conn   (fn []
                   (let [c (fake-conn (atom []))]
                     (swap! opened conj c)
                     (set! (.-quit c) (fn [] (swap! quit conj c) (js/Promise.resolve nil)))
                     ;; the first pop never answers: the pollers sit in
                     ;; it, so the test never races their loop
                     (set! (.-blpop c) (fn [_k _t] (js/Promise. (fn [_res _rej]))))
                     c))
          handle (await (worker/start (test-cfg {:concurrency 2 :connect conn})))]
      (t/is (= 2 (count @opened)))
      (await (worker/stop handle))
      (t/is (= 2 (count @quit)))
      (t/testing "a second stop is a no-op"
        (await (worker/stop handle))
        (t/is (= 2 (count @quit)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
