;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.redis-test
  "The redis wrapper swallows a command timeout and returns nil, so the log
  line is the only trace of the command that did not reach the server. These
  tests pin what that line has to carry."
  (:require
   [app.common.logging :as l]
   [app.redis :as rds]
   [clojure.test :as t])
  (:import
   io.lettuce.core.RedisCommandTimeoutException))

(def ^:private timeout-hint "timeout on del redis key")

(defn- timeout-connection
  "A connection whose DEL always exceeds the command timeout, the way Lettuce
  answers when the response does not arrive in time."
  []
  (reify rds/IDefaultConnection
    (-del [_ _keys]
      (throw (RedisCommandTimeoutException. "DEL. Command timed out after 2 second(s)")))))

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

(defn- timeout-record
  "Wait until the record that reports a timed out DEL is captured."
  [records timeout]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (or (some (fn [record]
                  (when (= timeout-hint (get (::l/props record) :hint))
                    record))
                @records)
          (when (<= (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(t/deftest del-logs-the-keys-that-timed-out
  ;; The server never saw the command, so the operator has no other way to know
  ;; which key stayed behind.
  (capture-records
   (fn [records]
     (t/is (nil? (rds/del (timeout-connection) ["file-summary:1" "file-summary:2"])))
     (let [record (timeout-record records 2000)]
       (t/is (some? record) "a timed out DEL must leave a log line")
       (t/is (= ["file-summary:1" "file-summary:2"]
                (get (::l/props record) :keys)))
       (t/is (instance? RedisCommandTimeoutException (::l/cause record)))
       (t/is (= :error (::l/level record)))
       (t/is (= "app.redis" (::l/logger record)))))))

(t/deftest del-logs-a-single-key-as-a-collection
  ;; `del` accepts one key or a collection and always hands a collection to the
  ;; driver; the log keeps the same shape so it does not depend on the call.
  (capture-records
   (fn [records]
     (rds/del (timeout-connection) "file-summary:1")
     (t/is (= ["file-summary:1"]
              (get (::l/props (timeout-record records 2000)) :keys))))))
