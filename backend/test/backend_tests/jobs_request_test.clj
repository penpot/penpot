;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.jobs-request-test
  (:require
   [app.common.generic-pool :as gpool]
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as-alias mtx]
   [app.redis :as rds]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   io.lettuce.core.api.sync.RedisCommands
   java.lang.AutoCloseable))

(t/use-fixtures :once th/state-init)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-pool
  []
  (-> {::jobs/request-pool {::rds/client  (get th/*system* :app.redis/client)
                            ::mtx/metrics (get th/*system* :app.metrics/metrics)}}
      (ig/expand)
      (ig/init)
      (get ::jobs/request-pool)))

(defn- queue-key
  [queue]
  (str "penpot.worker.queue:" (cf/get :tenant) ":" (name queue)))

(defn- make-cfg
  [pool]
  {::jobs/request-pool pool})

(defn- clear-queues!
  []
  (let [conn (rds/connect {::rds/client  (get th/*system* :app.redis/client)
                           ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
    (try
      (let [cmd  (.-cmd conn)
            keys (vec (.keys ^RedisCommands cmd
                             (str jobs/reply-key-prefix ":" (cf/get :tenant) ":*")))]
        (when (seq keys)
          (rds/del conn keys))
        (rds/del conn (queue-key :media)))
      (finally
        (rds/close conn)))))

(defn- test-fixture [next]
  (th/database-reset
   (fn []
     (clear-queues!)
     (next))))

(t/use-fixtures :each test-fixture)

(defn- with-responder
  "Start a responder on :media that answers the next request with the
  provided response map (usually {:ok ...} or {:error ...}). Returns a
  future that resolves to the decoded request payload [request-id,
  reply-key, cmd, params]."
  [pool response-fn & {:keys [delay-ms timeout-ms]}]
  (let [timeout-ms (or timeout-ms 10000)
        out        (promise)]
    (let [fut (future
                (let [conn (rds/connect {::rds/client  (get th/*system* :app.redis/client)
                                         ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
                  (try
                    (when delay-ms
                      (Thread/sleep (long delay-ms)))
                    (let [key      (queue-key :media)
                          [_ payload] (rds/blpop conn [key] (ct/duration {:millis timeout-ms}))]
                      (if (nil? payload)
                        (deliver out ::no-request)
                        (let [[request-id reply-key cmd params :as decoded] (json/decode payload)]
                          (deliver out decoded)
                          (jobs/reply! {::rds/pool pool} reply-key (response-fn params)))))
                    (finally
                      (rds/close conn)))))]
      {:future fut
       :payload out})))

(defn- get-ttl
  [key]
  (let [conn (rds/connect {::rds/client  (get th/*system* :app.redis/client)
                           ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
    (try
      (let [cmd (.-cmd conn)]
        (.ttl ^RedisCommands cmd key))
      (finally
        (rds/close conn)))))

(defn- key-exists?
  [key]
  (let [conn (rds/connect {::rds/client  (get th/*system* :app.redis/client)
                           ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
    (try
      (let [cmd (.-cmd conn)]
        (pos? (.exists ^RedisCommands cmd (make-array String 1 key))))
      (finally
        (rds/close conn)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest request-returns-the-ok-reply
  (let [pool    (make-pool)
        cfg     (make-cfg pool)
        payload (with-responder pool (fn [_params] {:ok {:value 42}}))]

    (let [result (jobs/request! cfg {::jobs/queue  :media
                                     ::jobs/cmd    :process
                                     ::jobs/params {:x 1}})]
      (t/is (= {:value 42} result)))

    (t/testing "the worker receives the expected payload shape"
      (let [[request-id reply-key cmd params :as decoded] (deref (:payload payload) 10000 ::timeout)]
        (t/is (string? request-id))
        (t/is (string? reply-key))
        (t/is (str/starts-with? reply-key (str jobs/reply-key-prefix ":" (cf/get :tenant) ":")))
        (t/is (= "process" cmd))
        (t/is (= {"x" 1} params)
              (str "params should be plain JSON: " (pr-str decoded)))))))

(t/deftest request-propagates-the-error-reply
  (let [pool (make-pool)
        cfg  (make-cfg pool)
        _    (with-responder pool
               (fn [_params]
                 {:error {:code :processing-failed
                          :hint "something went wrong"}}))]
    (t/is (thrown-with-msg? Exception #"something went wrong"
                            (jobs/request! cfg {::jobs/queue  :media
                                                ::jobs/cmd    :process
                                                ::jobs/params {:x 1}})))))

(t/deftest request-times-out-and-cleans-the-reply-key
  (let [pool (make-pool)
        cfg  (make-cfg pool)]
    (t/is (thrown-with-msg? Exception #"timeout waiting for the job reply"
                            (jobs/request! cfg {::jobs/queue :media
                                                ::jobs/cmd   :process
                                                ::jobs/params {:x 1}
                                                ::jobs/timeout (ct/duration {:millis 500})})))

    (t/testing "no orphan reply key is left behind"
      ;; there is no way to know the exact reply key (random request-id);
      ;; check no reply keys exist at all after the timeout
      (let [conn (rds/connect {::rds/client  (get th/*system* :app.redis/client)
                               ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
        (try
          (let [cmd (.-cmd conn)
                keys (.keys ^RedisCommands cmd
                            (str jobs/reply-key-prefix ":" (cf/get :tenant) ":*"))]
            (t/is (empty? (vec keys))))
          (finally
            (rds/close conn)))))))

(t/deftest request-pool-returns-connections-on-success-and-failure
  (let [pool    (make-pool)
        cfg     (make-cfg pool)
        default-timeout (-> (cf/get-jobs-request-timeout)
                            (ct/plus (ct/duration {:seconds 30})))]

    (t/testing "successful request does not consume the connection"
      (with-responder pool (fn [_params] {:ok {:a 1}}))
      (jobs/request! cfg {::jobs/queue  :media
                          ::jobs/cmd    :process
                          ::jobs/params {:x 1}})

      (t/testing "and the next request works with a pooled connection"
        (with-responder pool (fn [_params] {:ok {:b 2}}))
        (t/is (= {:b 2} (jobs/request! cfg {::jobs/queue  :media
                                            ::jobs/cmd    :process
                                            ::jobs/params {:x 2}})))

        (t/testing "connection command timeout is restored after use"
          (with-open [^AutoCloseable pooled (gpool/get pool)]
            (let [conn @pooled]
              (t/is (= (.toMillis ^java.time.Duration default-timeout)
                       (.toMillis ^java.time.Duration (rds/get-timeout conn)))))))))))

(t/deftest request-supports-larger-per-call-timeout
  (let [pool (make-pool)
        cfg  (make-cfg pool)
        _    (with-responder pool (fn [_params] {:ok {:slow 1}}))]

    (t/testing "an override bigger than the default works (command timeout raised per call)"
      (t/is (= {:slow 1}
               (jobs/request! cfg {::jobs/queue  :media
                                   ::jobs/cmd    :process
                                   ::jobs/params {:x 1}
                                   ::jobs/timeout (ct/duration {:seconds 130})}))))))

(t/deftest late-reply-is-cleaned-by-the-worker-expire
  (let [pool (make-pool)
        cfg  (make-cfg pool)
        ;; the responder replies 2s after the caller timeout (500ms)
        responder (with-responder pool
                    (fn [_params] {:ok {:late 1}})
                    :delay-ms 2000)]

    (t/is (thrown-with-msg? Exception #"timeout waiting for the job reply"
                            (jobs/request! cfg {::jobs/queue :media
                                                ::jobs/cmd   :process
                                                ::jobs/params {:x 1}
                                                ::jobs/timeout (ct/duration {:millis 500})})))

    (t/testing "the late reply key exists (recreated by the worker) with a short TTL"
      (deref (:future responder) 10000 ::timeout)
      (let [conn (rds/connect {::rds/client  (get th/*system* :app.redis/client)
                               ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
        (try
          (let [cmd  (.-cmd conn)
                keys (vec (.keys ^RedisCommands cmd
                                 (str jobs/reply-key-prefix ":" (cf/get :tenant) ":*")))]
            (t/is (= 1 (count keys)))
            (let [ttl (.ttl ^RedisCommands cmd (first keys))]
              (t/is (pos? ttl))
              (t/is (<= ttl 60))))
          (finally
            (rds/close conn)))))))

(t/deftest request-creates-no-job-row
  (let [pool (make-pool)
        cfg  (make-cfg pool)
        _    (with-responder pool (fn [_params] {:ok {}}))]
    (jobs/request! cfg {::jobs/queue :media ::jobs/cmd :process ::jobs/params {}})
    (t/is (zero? (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job"]))))))
