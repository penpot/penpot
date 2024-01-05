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

(def ^:private always-false
  (constantly false))

(defn wrap-retry
  [_ f {:keys [::sv/name] :as mdata}]

  (if (::enabled mdata)
    (let [max-retries (get mdata ::max-retries 3)
          matches?    (get mdata ::when always-false)]
      (l/dbg :hint "wrapping retry" :name name :max-retries max-retries)
      (fn [cfg params]
        ((fn recursive-invoke [retry]
           (try
             (f cfg params)
             (catch Throwable cause
               (if (matches? cause)
                 (let [current-retry (inc retry)]
                   (l/wrn :hint "retrying operation" :retry current-retry :service name)
                   (if (<= current-retry max-retries)
                     (recursive-invoke current-retry)
                     (throw cause)))
                 (throw cause))))) 1)))
    f))

(defn invoke
  [{:keys [::db/conn ::max-retries] :or {max-retries 3} :as cfg} f & args]
  (assert (db/connection? conn) "invalid database connection")
  (loop [rnum 1]
    (let [match? (get cfg ::when always-false)
          result (let [spoint (db/savepoint conn)]
                   (try
                     (let [result (apply f cfg args)]
                       (db/release! conn spoint)
                       result)
                     (catch Throwable cause
                       (db/rollback! conn spoint)
                       (if (and (match? cause) (<= rnum max-retries))
                         ::retry
                         (throw cause)))))]
      (if (= ::retry result)
        (let [label (get cfg ::label "anonymous")]
          (l/warn :hint "retrying operation" :label label :retry rnum)
          (recur (inc rnum)))
        result))))
