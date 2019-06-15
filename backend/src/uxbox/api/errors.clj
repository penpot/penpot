;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 20162019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.errors
  "A errors handling for api.")

(defmulti handle-exception #(:type (ex-data %)))

(defmethod handle-exception :validation
  [err]
  (let [response (ex-data err)]
    {:status 400
     :body response}))

(defmethod handle-exception :default
  [err]
  (let [response (ex-data err)]
    {:status 500
     :body response}))

;; --- Entry Point

(defn- handle-data-access-exception
  [err]
  (let [err (.getCause err)
        state (.getSQLState err)
        message (.getMessage err)]
    (case state
      "P0002"
      {:status 412 ;; precondition-failed
       :body {:message message
              :payload nil
              :type :occ}}

      (do
        {:status 500
         :message {:message message
                   :type :unexpected
                   :payload nil}}))))

(defn- handle-unexpected-exception
  [err]
  (let [message (.getMessage err)]
    {:status 500
     :body {:message message
            :type :unexpected
            :payload nil}}))

(defn errors-handler
  [error context]
  (cond
    (instance? clojure.lang.ExceptionInfo error)
    (handle-exception error)

    (instance? java.util.concurrent.CompletionException error)
    (errors-handler context (.getCause error))

    java.util.concurrent.ExecutionException
    (errors-handler context (.getCause error))

    (instance? org.jooq.exception.DataAccessException error)
    (handle-data-access-exception error)

    :else
    (handle-unexpected-exception error)))

(defn wrap-print-errors
  [handler error request]
  (println "\n*********** stack trace ***********")
  (.printStackTrace error)
  (println "\n********* end stack trace *********")
  (handler error request))
