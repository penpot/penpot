;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.errors
  "A errors handling for the http server."
  (:require [io.aviso.exception :as e]))

(defmulti handle-exception #(:type (ex-data %)))

(defmethod handle-exception :validation
  [err]
  (let [response (ex-data err)]
    {:status 400
     :body response}))

(defmethod handle-exception :parse
  [err]
  {:status 400
   :body {:type :parse
          :message (ex-message err)}})

(defmethod handle-exception :default
  [err]
  (e/write-exception err)
  {:status 500
   :body {:type :exception
          :message (ex-message err)}})

(defn- handle-data-access-exception
  [err]
  (let [err (.getCause err)
        state (.getSQLState err)
        message (.getMessage err)]
    (case state
      "P0002" {:status 412 ;; precondition-failed
               :body {:message message
                      :type :occ}}
      (handle-exception err))))

(defn errors-handler
  [error context]
  (cond
    (or (instance? java.util.concurrent.CompletionException error)
        (instance? java.util.concurrent.ExecutionException error))
    (errors-handler context (.getCause error))

    (instance? org.jooq.exception.DataAccessException error)
    (handle-data-access-exception error)

    :else (handle-exception error)))
