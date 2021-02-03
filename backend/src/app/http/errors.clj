;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.errors
  "A errors handling for the http server."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.util.log4j :refer [update-thread-context!]]
   [clojure.pprint :refer [pprint]]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [expound.alpha :as expound])
  (:import
   org.apache.logging.log4j.ThreadContext))

(defn- explain-error
  [error]
  (with-out-str
    (expound/printer (:data error))))

(defn get-error-context
  [request error]
  (let [edata (ex-data error)]
    (merge
     {:id      (uuid/next)
      :path    (:uri request)
      :method  (:request-method request)
      :params  (:params request)
      :version (:full cfg/version)
      :host    (:public-uri cfg/config)
      :class   (.getCanonicalName ^java.lang.Class (class error))
      :hint    (ex-message error)
      :data    edata}


     (let [headers (:headers request)]
       {:user-agent (get headers "user-agent")
        :frontend-version (get headers "x-frontend-version" "unknown")})

     (when (and (map? edata) (:data edata))
       {:explain (explain-error edata)}))))

(defmulti handle-exception
  (fn [err & _rest]
    (let [edata (ex-data err)]
      (or (:type edata)
          (class err)))))

(defmethod handle-exception :authentication
  [err _]
  {:status 401 :body (ex-data err)})

(defmethod handle-exception :validation
  [err req]
  (let [header (get-in req [:headers "accept"])
        edata  (ex-data err)]
    (if (and (= :spec-validation (:code edata))
             (str/starts-with? header "text/html"))
      {:status 400
       :headers {"content-type" "text/html"}
       :body (str "<pre style='font-size:16px'>"
                  (explain-error edata)
                  "</pre>\n")}
      {:status 400
       :body   (cond-> edata
                 (map? (:data edata))
                 (-> (assoc :explain (explain-error edata))
                     (dissoc :data)))})))

(defmethod handle-exception :assertion
  [error request]
  (let [edata (ex-data error)
        cdata (get-error-context request error)]
    (update-thread-context! cdata)
    (log/errorf error "Internal error: assertion (id: %s)" (str (:id cdata)))
    {:status 500
     :body {:type :server-error
            :data (-> edata
                      (assoc :explain (explain-error edata))
                      (dissoc :data))}}))

(defmethod handle-exception :not-found
  [err _]
  {:status 404 :body (ex-data err)})

(defmethod handle-exception :default
  [error request]
  (let [cdata (get-error-context request error)]
    (update-thread-context! cdata)
    (log/errorf error "Internal error: %s (id: %s)"
                (ex-message error)
                (str (:id cdata)))
    {:status 500
     :body {:type :server-error
            :hint (ex-message error)
            :data (ex-data error)}}))

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause ^Throwable error) req)
    (handle-exception error req)))
