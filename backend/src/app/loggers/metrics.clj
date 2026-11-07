;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.loggers.metrics
  "A reporter that counts the log records of this application, by logger and
  level, and publishes them as a counter."
  (:require
   [app.common.logging :as l]
   [app.metrics :as mtx]
   [integrant.core :as ig]))

(def ^:private counted-levels
  "The levels worth counting: `info` is emitted often and says nothing about
  the health of the application."
  #{:warn :error :fatal})

(defmethod ig/assert-key ::reporter
  [_ params]
  (assert (mtx/metrics? (::mtx/metrics params)) "expect valid metrics instance"))

(defmethod ig/init-key ::reporter
  [_ {::mtx/keys [metrics]}]
  (add-watch l/log-record ::reporter
             (fn [_ _ _ record]
               ;; This runs inside the logging path: the watcher fires from
               ;; the `swap!` of the log record, so an exception here would
               ;; reach whoever was logging on the synchronous path, and on
               ;; the async one it would die in the executor's uncaught
               ;; handler, taking that log line out of Loki. Swallowed on
               ;; purpose either way.
               (try
                 (let [level (::l/level record)]
                   (when (contains? counted-levels level)
                     (mtx/run! metrics
                               :id :log-events
                               :inc 1
                               :labels (into-array String [(::l/logger record) (name level)]))))
                 (catch Throwable _ nil))))
  {::mtx/metrics metrics})

(defmethod ig/halt-key! ::reporter
  [_ _]
  (remove-watch l/log-record ::reporter))
