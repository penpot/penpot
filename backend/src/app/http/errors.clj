;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.http.errors
  "A errors handling for the http server."
  (:require
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [app.metrics :as mtx]
   [io.aviso.exception :as e]))

(defmulti handle-exception
  (fn [err & rest]
    (:type (ex-data err))))

(defmethod handle-exception :validation
  [err req]
  (let [header (get-in req [:headers "accept"])
        response (ex-data err)]
    (cond
      (and (str/starts-with? header "text/html")
           (= :spec-validation (:code response)))
      {:status 400
       :headers {"content-type" "text/html"}
       :body (str "<pre style='font-size:16px'>" (:explain response) "</pre>\n")}

      :else
      {:status 400
       :body response})))

(defmethod handle-exception :ratelimit
  [err req]
  {:status 429
   :headers {"retry-after" 1000}
   :body ""})

(defmethod handle-exception :not-found
  [err req]
  (let [response (ex-data err)]
    {:status 404
     :body response}))

(defmethod handle-exception :service-error
  [err req]
  (handle-exception (.getCause ^Throwable err) req))

(defmethod handle-exception :parse
  [err req]
  {:status 400
   :body {:type :parse
          :message (ex-message err)}})

(defmethod handle-exception :default
  [err req]
  (log/error "Unhandled exception on request:" (:path req) "\n"
             (with-out-str
                (.printStackTrace ^Throwable err (java.io.PrintWriter. *out*))))
  {:status 500
   :body {:type :internal-error
          :message (ex-message err)
          :data (ex-data err)}})

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause ^Throwable error) req)
    (handle-exception error req)))
