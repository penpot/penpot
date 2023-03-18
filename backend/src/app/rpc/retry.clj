;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.retry
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.util.services :as sv])
  (:import
   org.postgresql.util.PSQLException))

(defn conflict-exception?
  "Check if exception matches a insertion conflict on postgresql."
  [e]
  (and (instance? PSQLException e)
       (= "23505" (.getSQLState ^PSQLException e))))

(def ^:private always-false (constantly false))

(defn wrap-retry
  [_ f {:keys [::matches ::sv/name] :or {matches always-false} :as mdata}]

  (when (::enabled mdata)
    (l/debug :hint "wrapping retry" :name name))

  (if-let [max-retries (::max-retries mdata)]
    (fn [cfg params]
      ((fn run [retry]
         (try
           (f cfg params)
           (catch Throwable cause
             (if (matches cause)
               (let [current-retry (inc retry)]
                 (l/trace :hint "running retry algorithm" :retry current-retry)
                 (if (<= current-retry max-retries)
                   (run current-retry)
                   (throw cause)))
               (throw cause))))) 1))
    f))

(defmacro with-retry
  [{:keys [::when ::max-retries ::label ::db/conn] :or {max-retries 3}} & body]
  `(let [conn# ~conn]
     (assert (or (nil? conn#) (db/connection? conn#)) "invalid database connection")
     (loop [tnum# 1]
       (let [result# (let [sp# (some-> conn# db/savepoint)]
                       (try
                         (let [result# (do ~@body)]
                           (some->> sp# (db/release! conn#))
                           result#)
                         (catch Throwable cause#
                           (some->> sp# (db/rollback! conn#))
                           (if (and (~when cause#) (<= tnum# ~max-retries))
                             ::retry
                             (throw cause#)))))]
         (if (= ::retry result#)
           (do
             (l/warn :hint "retrying operation" :label ~label :retry tnum#)
             (recur (inc tnum#)))
           result#)))))
