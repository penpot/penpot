;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.errors
  "A errors handling for the http server."
  (:require
   [clojure.tools.logging :as log]
   [io.aviso.exception :as e]))

(defmulti handle-exception #(:type (ex-data %)))

(defmethod handle-exception :validation
  [err]
  (let [response (ex-data err)]
    {:status 400
     :body response}))

(defmethod handle-exception :not-found
  [err]
  (let [response (ex-data err)]
    {:status 404
     :body response}))

(defmethod handle-exception :parse
  [err]
  {:status 400
   :body {:type :parse
          :message (ex-message err)}})

(defmethod handle-exception :default
  [err]
  (log/error err "Unhandled exception on request:")
  {:status 500
   :body {:type :exception
          :message (ex-message err)}})

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause error))
    (handle-exception error)))
