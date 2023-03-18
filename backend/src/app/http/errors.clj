;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.errors
  "A errors handling for the http server."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.http :as-alias http]
   [app.http.access-token :as-alias actoken]
   [app.http.session :as-alias session]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

(defn- parse-client-ip
  [request]
  (or (some-> (yrq/get-header request "x-forwarded-for") (str/split ",") first)
      (yrq/get-header request "x-real-ip")
      (yrq/remote-addr request)))

(defn request->context
  "Extracts error report relevant context data from request."
  [request]
  (let [claims (-> {}
                   (into (::session/token-claims request))
                   (into (::actoken/token-claims request)))]
    {:path       (:path request)
     :method     (:method request)
     :params     (:params request)
     :ip-addr    (parse-client-ip request)
     :user-agent (yrq/get-header request "user-agent")
     :profile-id (:uid claims)
     :version    (or (yrq/get-header request "x-frontend-version")
                     "unknown")}))

(defmulti handle-exception
  (fn [err & _rest]
    (let [edata (ex-data err)]
      (or (:type edata)
          (class err)))))

(defmethod handle-exception :authentication
  [err _]
  {::yrs/status 401
   ::yrs/body (ex-data err)})

(defmethod handle-exception :authorization
  [err _]
  {::yrs/status 403
   ::yrs/body (ex-data err)})

(defmethod handle-exception :restriction
  [err _]
  {::yrs/status 400
   ::yrs/body (ex-data err)})

(defmethod handle-exception :rate-limit
  [err _]
  (let [headers (-> err ex-data ::http/headers)]
    {::yrs/status 429
     ::yrs/headers headers}))

(defmethod handle-exception :concurrency-limit
  [err _]
  (let [headers (-> err ex-data ::http/headers)]
    {::yrs/status 429
     ::yrs/headers headers}))

(defmethod handle-exception :validation
  [err _]
  (let [{:keys [code] :as data} (ex-data err)]
    (cond
      (= code :spec-validation)
      (let [explain (ex/explain data)]
        {::yrs/status 400
         ::yrs/body   (-> data
                          (dissoc ::s/problems ::s/value)
                          (cond-> explain (assoc :explain explain)))})

      (= code :params-validation)
      (let [explain (::sm/explain data)
            payload (sm/humanize-data explain)]
        {::yrs/status 400
         ::yrs/body   (-> data
                          (dissoc ::sm/explain)
                          (assoc :data payload))})

      (= code :request-body-too-large)
      {::yrs/status 413 ::yrs/body data}

      :else
      {::yrs/status 400 ::yrs/body data})))

(defmethod handle-exception :assertion
  [error request]
  (binding [l/*context* (request->context request)]
    (let [{:keys [code] :as data} (ex-data error)]
      (cond
        (= code :data-validation)
        (let [explain (::sm/explain data)
              payload (sm/humanize-data explain)]

          (l/error :hint "Data assertion error" :message (ex-message error) :cause error)
          {::yrs/status 500
           ::yrs/body   {:type :server-error
                         :code :assertion
                         :data (-> data
                                   (dissoc ::sm/explain)
                                   (assoc :data payload))}})

        (= code :spec-validation)
        (let [explain (ex/explain data)]
          (l/error :hint "Spec assertion error" :message (ex-message error) :cause error)
          {::yrs/status 500
           ::yrs/body   {:type :server-error
                         :code :assertion
                         :data (-> data
                                   (dissoc ::s/problems ::s/value ::s/spec)
                                   (cond-> explain (assoc :explain explain)))}})

        :else
        (do
          (l/error :hint "Assertion error" :message (ex-message error) :cause error)
          {::yrs/status 500
           ::yrs/body   {:type :server-error
                         :code :assertion
                         :data data}})))))


(defmethod handle-exception :not-found
  [err _]
  {::yrs/status 404
   ::yrs/body (ex-data err)})

(defmethod handle-exception :internal
  [error request]
  (binding [l/*context* (request->context request)]
    (l/error :hint "Internal error" :message (ex-message error) :cause error)
    {::yrs/status 500
     ::yrs/body {:type :server-error
                 :code :unhandled
                 :hint (ex-message error)
                 :data (ex-data error)}}))

(defmethod handle-exception org.postgresql.util.PSQLException
  [error request]
  (let [state (.getSQLState ^java.sql.SQLException error)]
    (binding [l/*context* (request->context request)]
      (l/error :hint "PSQL error" :message (ex-message error) :cause error)
      (cond
        (= state "57014")
        {::yrs/status 504
         ::yrs/body {:type :server-error
                     :code :statement-timeout
                     :hint (ex-message error)}}

        (= state "25P03")
        {::yrs/status 504
         ::yrs/body {:type :server-error
                     :code :idle-in-transaction-timeout
                     :hint (ex-message error)}}

        :else
        {::yrs/status 500
         ::yrs/body {:type :server-error
                     :code :unexpected
                     :hint (ex-message error)
                     :state state}}))))

(defmethod handle-exception :default
  [error request]
  (let [edata (ex-data error)]
    (cond
      ;; This means that exception is not a controlled exception.
      (nil? edata)
      (binding [l/*context* (request->context request)]
        (l/error :hint "Unexpected error" :message (ex-message error) :cause error)
        {::yrs/status 500
         ::yrs/body {:type :server-error
                     :code :unexpected
                     :hint (ex-message error)}})

      ;; This is a special case for the idle-in-transaction error;
      ;; when it happens, the connection is automatically closed and
      ;; next-jdbc combines the two errors in a single ex-info. We
      ;; only need the :handling error, because the :rollback error
      ;; will be always "connection closed".
      (and (ex/exception? (:rollback edata))
           (ex/exception? (:handling edata)))
      (handle-exception (:handling edata) request)

      :else
      (binding [l/*context* (request->context request)]
        (l/error :hint "Unhandled error" :message (ex-message error) :cause error)
        {::yrs/status 500
         ::yrs/body {:type :server-error
                     :code :unhandled
                     :hint (ex-message error)
                     :data edata}}))))

(defn handle
  [cause request]
  (if (or (instance? java.util.concurrent.CompletionException cause)
          (instance? java.util.concurrent.ExecutionException cause))
    (handle-exception (ex-cause cause) request)
    (handle-exception cause request)))
