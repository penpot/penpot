;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.auth.login-lockout
  "Brute-force protection: per-account failed login counter backed by Redis.
   Uses an atomic Lua script for the increment operation to prevent
   concurrent login bypass. Stores count:window_start in the value;
   time is passed from Clojure (ct/now) via ARGV, keeping it injectable
   for tests. When the counter reaches the configured threshold within
   the time window, the account is locked out until the window expires."
  (:require
   [app.common.generic-pool :as gpool]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.redis :as rds]
   [app.redis.script :as-alias rscript]
   [clojure.string :as str])
  (:import
   java.lang.AutoCloseable))

(def ^:private key-prefix "penpot.login-lockout.")

(def ^:private lockout-script
  {::rscript/name ::login-lockout
   ::rscript/path "app/auth/login_lockout.lua"})

(defn- get-pool
  [cfg]
  (:app.redis/pool cfg))

(defn- with-conn
  [cfg f]
  (let [pool (get-pool cfg)
        conn (gpool/get pool)]
    (try
      (f @conn)
      (finally
        (.close ^AutoCloseable conn)))))

(defn- parse-value
  "Parse stored \"count:window_start\" string. Returns nil if missing
   or expired. Throws NumberFormatException for malformed values
   (caught by caller → fail-open)."
  [value window-ms now-ms]
  (when value
    (let [sep (str/index-of value ":")]
      (when sep
        (let [count (Long/parseLong (subs value 0 sep))
              start (Long/parseLong (subs value (inc sep)))]
          (when (> (+ start window-ms) now-ms)
            {:count count :window-start start}))))))

(defn record-failed-attempt!
  "Increment the failed-login counter for a profile-id (atomic via Lua).
   Returns nil when the flag is disabled or on Redis error (fail-open).
   Otherwise returns a map with :count (int), :ttl (int, seconds),
   and :locked? (boolean)."
  [cfg profile-id]
  (when (contains? cf/flags :account-lockout)
    (try
      (let [threshold (cf/get :login-lockout-max-attempts)
            window    (cf/get :login-lockout-window)
            window-ms (if (integer? window) window (.toMillis window))
            _         (assert (>= threshold 1) "login-lockout-max-attempts must be >= 1")
            _         (assert (>= window-ms 60000) "login-lockout-window must be >= 60000ms (1 minute)")
            key       (str key-prefix profile-id)
            now-ms    (inst-ms (ct/now))
            result    (with-conn cfg
                        (fn [conn]
                          (rds/eval conn
                                    (assoc lockout-script
                                           ::rscript/keys [key]
                                           ::rscript/vals [threshold window-ms now-ms]))))
            [count locked ttl] result]
        {:count count
         :locked? (= 1 locked)
         :ttl (max 0 ttl)})
      (catch Exception cause
        (l/warn :hint "redis unavailable, failing open on login lockout"
                :profile-id (str profile-id)
                :cause cause)
        nil))))

(defn clear-attempts!
  "Clear the failed-login counter (on successful login or password reset).
   No-op when the flag is disabled."
  [cfg profile-id]
  (when (contains? cf/flags :account-lockout)
    (try
      (with-conn cfg
        (fn [conn]
          (rds/del conn (str key-prefix profile-id))))
      (catch Exception cause
        (l/warn :hint "redis unavailable, failed to clear login lockout"
                :profile-id (str profile-id)
                :cause cause)))))

(defn locked?
  "Check whether the account is currently locked out. Does not increment
   the counter. Returns {:locked? false} when the flag is disabled or on
   Redis error (fail-open). When locked, includes :ttl (seconds remaining)."
  [cfg profile-id]
  (if (contains? cf/flags :account-lockout)
    (try
      (let [threshold  (cf/get :login-lockout-max-attempts)
            window    (cf/get :login-lockout-window)
            window-ms (if (integer? window) window (.toMillis window))
            _         (assert (>= threshold 1) "login-lockout-max-attempts must be >= 1")
            _         (assert (>= window-ms 60000) "login-lockout-window must be >= 60000ms (1 minute)")
            key       (str key-prefix profile-id)
            now-ms    (inst-ms (ct/now))
            result    (with-conn cfg
                        (fn [conn]
                          (if-let [current (parse-value (rds/get conn key) window-ms now-ms)]
                            (let [elapsed (- now-ms (:window-start current))
                                  ttl-ms  (- window-ms elapsed)
                                  locked? (>= (:count current) threshold)]
                              (cond-> {:locked? locked?}
                                locked?
                                (assoc :ttl (int (Math/ceil (/ ttl-ms 1000.0))))))
                            {:locked? false})))]
        result)
      (catch Exception cause
        (l/warn :hint "redis unavailable, failing open on lockout check"
                :profile-id (str profile-id)
                :cause cause)
        {:locked? false}))
    {:locked? false}))
