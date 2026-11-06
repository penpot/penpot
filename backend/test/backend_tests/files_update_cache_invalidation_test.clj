;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.files-update-cache-invalidation-test
  "The file-summary cache entry of a file is invalidated best effort.

  `update-file` only hands the invalidation to the worker executor, so a
  slow, unavailable or failing redis never delays a save and never fails the
  transaction. A lost invalidation leaves a stale summary until the cache TTL
  expires; the log line is the only trace of it."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.redis :as rds]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as files-update]
   [app.worker :as-alias wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   io.lettuce.core.RedisConnectionException
   java.util.concurrent.Executor))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private warn-hint "unable to invalidate file-summary cache")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- capturing-executor
  "An executor that keeps the task instead of running it, so the test can
  check what the caller thread did and did not do."
  [captured]
  (reify Executor
    (execute [_ f]
      (reset! captured f))))

(defn- inline-executor
  "An executor that runs the task in the calling thread. Keeps the tests that
  go through `update-file` deterministic: no sleeps, no waiting."
  []
  (reify Executor
    (execute [_ f]
      (.run ^Runnable f))))

(defn- capture-records
  "Run `f` with every log record collected, newest last. The logging API emits
  asynchronously, so a record may land after `f` returns; callers wait for the
  one they need."
  [f]
  (let [records (atom [])]
    (add-watch l/log-record ::capture
               (fn [_ _ _ record]
                 (swap! records conj record)))
    (try
      (f records)
      (finally
        (remove-watch l/log-record ::capture)))))

(defn- warn-record
  "Wait until the record that reports a failed invalidation is captured."
  [records timeout]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (or (some (fn [record]
                  (when (= warn-hint (get (::l/props record) :hint))
                    record))
                @records)
          (when (<= (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(defn- add-page
  []
  [{:type :add-page
    :name "page"
    :id (uuid/next)}])

(defn- create-file
  []
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    {:profile profile :file file}))

(defn- update-file
  [{:keys [profile file]} id->executor]
  (binding [cf/flags (conj cf/flags :redis-cache)]
    (th/update-file* (assoc th/*system* ::wrk/executor id->executor)
                     {:file-id (:id file)
                      :profile-id (:id profile)
                      :changes (add-page)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest hands-the-invalidation-to-the-executor
  ;; The request thread must not reach redis: it only hands the task over, and
  ;; the task keeps nothing from the request but the pool it needs.
  (let [captured (atom nil)
        calls    (atom [])
        file-id  (uuid/next)
        cfg      {::wrk/executor (capturing-executor captured)
                  ::rds/pool ::pool
                  ::db/conn ::request-connection}]

    (with-redefs [rds/run! (fn [cfg f]
                             (swap! calls conj cfg)
                             (f {::rds/conn ::connection}))
                  rds/del  (constantly nil)]
      (#'files-update/schedule-cache-invalidation cfg file-id)

      (t/is (fn? @captured) "the invalidation must be handed to the executor")
      (t/is (empty? @calls) "nothing may run on the caller thread")

      (@captured)

      (t/is (= 1 (count @calls)))
      (t/is (= [::pool] (mapv ::rds/pool @calls)))
      (t/is (nil? (::db/conn (first @calls)))
            "the task must not keep the connection of the request"))))

(t/deftest logs-and-swallows-errors-from-redis
  ;; A refused connection is not a command timeout, so `rds/del` lets it
  ;; through. The task has to absorb it: an exception here would reach the
  ;; executor and kill its worker thread.
  (let [captured (atom nil)
        file-id  (uuid/next)
        cfg      {::wrk/executor (capturing-executor captured)
                  ::rds/pool ::pool}]

    (capture-records
     (fn [records]
       (with-redefs [rds/run! (fn [_ _]
                                (throw (RedisConnectionException. "connection refused")))]
         (#'files-update/schedule-cache-invalidation cfg file-id)

         (t/is (nil? (try (@captured)
                          nil
                          (catch Throwable _
                            ::escaped)))
               "a failing invalidation must not escape the task")

         (let [record (warn-record records 2000)]
           (t/is (some? record) "a failed invalidation must leave a log line")
           (t/is (= (str file-id) (get (::l/props record) :file-id)))
           (t/is (instance? RedisConnectionException (::l/cause record)))
           (t/is (= :warn (::l/level record)))))))))

(t/deftest update-file-commits-when-redis-is-down
  ;; The whole point: a broken cache can not take the save down with it.
  (let [{:keys [file] :as data} (create-file)]

    (with-redefs [rds/del (fn [_ _]
                            (throw (RedisConnectionException. "connection refused")))]
      (let [result (update-file data (inline-executor))]
        (t/is (map? result))
        (t/is (= 1 (:revn (th/db-get :file {:id (:id file)})))
              "the transaction must commit")))))

(t/deftest update-file-invalidates-the-file-summary-key
  (let [{:keys [file] :as data} (create-file)
        deleted                 (atom [])]

    (with-redefs [rds/del (fn [conn key]
                            (swap! deleted conj [conn key]))]
      (update-file data (inline-executor))

      (t/is (= 1 (count @deleted)))
      (t/is (= (str files/file-summary-cache-key-prefix (:id file))
               (second (first @deleted)))))))
