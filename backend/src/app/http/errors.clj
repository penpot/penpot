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
   [app.common.schema :as-alias sm]
   [app.config :as cf]
   [app.http :as-alias http]
   [app.http.access-token :as-alias actoken]
   [app.http.session :as-alias session]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [ring.request :as rreq]
   [ring.response :as rres]))

(defn- parse-client-ip
  [request]
  (or (some-> (rreq/get-header request "x-forwarded-for") (str/split ",") first)
      (rreq/get-header request "x-real-ip")
      (rreq/remote-addr request)))

(defn request->context
  "Extracts error report relevant context data from request."
  [request]
  (let [claims (-> {}
                   (into (::session/token-claims request))
                   (into (::actoken/token-claims request)))]
    {:request/path       (:path request)
     :request/method     (:method request)
     :request/params     (:params request)
     :request/user-agent (rreq/get-header request "user-agent")
     :request/ip-addr    (parse-client-ip request)
     :request/profile-id (:uid claims)
     :version/frontend   (or (rreq/get-header request "x-frontend-version") "unknown")
     :version/backend    (:full cf/version)}))

(defmulti handle-error
  (fn [cause _ _]
    (-> cause ex-data :type)))

(defmulti handle-exception
  (fn [cause _ _]
    (class cause)))

(defmethod handle-error :authentication
  [err _ _]
  {::rres/status 401
   ::rres/body (ex-data err)})

(defmethod handle-error :authorization
  [err _ _]
  {::rres/status 403
   ::rres/body (ex-data err)})

(defmethod handle-error :restriction
  [err _ _]
  {::rres/status 400
   ::rres/body (ex-data err)})

(defmethod handle-error :rate-limit
  [err _ _]
  (let [headers (-> err ex-data ::http/headers)]
    {::rres/status 429
     ::rres/headers headers}))

(defmethod handle-error :concurrency-limit
  [err _ _]
  (let [headers (-> err ex-data ::http/headers)]
    {::rres/status 429
     ::rres/headers headers}))

(defmethod handle-error :validation
  [err request parent-cause]
  (let [{:keys [code] :as data} (ex-data err)]
    (cond
      (or (= code :spec-validation)
          (= code :params-validation)
          (= code :data-validation))
      (let [explain (ex/explain data)]
        {::rres/status 400
         ::rres/body   (-> data
                           (dissoc ::s/problems ::s/value ::s/spec ::sm/explain)
                           (cond-> explain (assoc :explain explain)))})

      (= code :request-body-too-large)
      {::rres/status 413 ::rres/body data}

      (= code :invalid-image)
      (binding [l/*context* (request->context request)]
        (let [cause (or parent-cause err)]
          (l/error :hint "unexpected error on processing image" :cause cause)
          {::rres/status 400 ::rres/body data}))

      :else
      {::rres/status 400 ::rres/body data})))

(defmethod handle-error :assertion
  [error request parent-cause]
  (binding [l/*context* (request->context request)]
    (let [{:keys [code] :as data} (ex-data error)
          cause (or parent-cause error)]
      (cond
        (= code :data-validation)
        (let [explain (ex/explain data)]
          (l/error :hint "data assertion error" :cause cause)
          {::rres/status 500
           ::rres/body   {:type :server-error
                          :code :assertion
                          :data (-> data
                                    (dissoc ::sm/explain)
                                    (cond-> explain (assoc :explain explain)))}})

        (= code :spec-validation)
        (let [explain (ex/explain data)]
          (l/error :hint "spec assertion error" :cause cause)
          {::rres/status 500
           ::rres/body   {:type :server-error
                          :code :assertion
                          :data (-> data
                                    (dissoc ::s/problems ::s/value ::s/spec)
                                    (cond-> explain (assoc :explain explain)))}})

        :else
        (do
          (l/error :hint "assertion error" :cause cause)
          {::rres/status 500
           ::rres/body   {:type :server-error
                          :code :assertion
                          :data data}})))))

(defmethod handle-error :not-found
  [err _ _]
  {::rres/status 404
   ::rres/body (ex-data err)})

(defmethod handle-error :internal
  [error request parent-cause]
  (binding [l/*context* (request->context request)]
    (let [cause (or parent-cause error)]
      (l/error :hint "internal error" :cause cause)
      {::rres/status 500
       ::rres/body {:type :server-error
                    :code :unhandled
                    :hint (ex-message error)
                    :data (ex-data error)}})))

(defmethod handle-error :default
  [error request parent-cause]
  (let [edata (ex-data error)]
    ;; This is a special case for the idle-in-transaction error;
    ;; when it happens, the connection is automatically closed and
    ;; next-jdbc combines the two errors in a single ex-info. We
    ;; only need the :handling error, because the :rollback error
    ;; will be always "connection closed".
    (if (and (ex/exception? (:rollback edata))
             (ex/exception? (:handling edata)))
      (handle-exception (:handling edata) request error)
      (handle-exception error request parent-cause))))

(defmethod handle-exception org.postgresql.util.PSQLException
  [error request parent-cause]
  (let [state (.getSQLState ^java.sql.SQLException error)
        cause (or parent-cause error)]
    (binding [l/*context* (request->context request)]
      (l/error :hint "PSQL error"
               :cause cause)
      (cond
        (= state "57014")
        {::rres/status 504
         ::rres/body {:type :server-error
                      :code :statement-timeout
                      :hint (ex-message error)}}

        (= state "25P03")
        {::rres/status 504
         ::rres/body {:type :server-error
                      :code :idle-in-transaction-timeout
                      :hint (ex-message error)}}

        :else
        {::rres/status 500
         ::rres/body {:type :server-error
                      :code :unexpected
                      :hint (ex-message error)
                      :state state}}))))

(defmethod handle-exception :default
  [error request parent-cause]
  (let [edata (ex-data error)
        cause (or parent-cause error)]
    (cond
      ;; This means that exception is not a controlled exception.
      (nil? edata)
      (binding [l/*context* (request->context request)]
        (l/error :hint "unexpected error" :cause cause)
        {::rres/status 500
         ::rres/body {:type :server-error
                      :code :unexpected
                      :hint (ex-message error)}})

      :else
      (binding [l/*context* (request->context request)]
        (l/error :hint "unhandled error" :cause cause)
        {::rres/status 500
         ::rres/body {:type :server-error
                      :code :unhandled
                      :hint (ex-message error)
                      :data edata}}))))

(defmethod handle-exception java.util.concurrent.CompletionException
  [cause request _]
  (let [cause' (ex-cause cause)]
    (if (ex/error? cause')
      (handle-error cause' request cause)
      (handle-exception cause' request cause))))

(defmethod handle-exception java.util.concurrent.ExecutionException
  [cause request _]
  (let [cause' (ex-cause cause)]
    (if (ex/error? cause')
      (handle-error cause' request cause)
      (handle-exception cause' request cause))))

(defn handle
  [cause request]
  (if (ex/error? cause)
    (handle-error cause request nil)
    (handle-exception cause request nil)))

(defn handle'
  [cause request]
  (::rres/body (handle cause request)))
