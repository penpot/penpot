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
   [app.util.inet :as inet]
   [clojure.spec.alpha :as s]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(defn request->context
  "Extracts error report relevant context data from request."
  [request]
  (let [claims (-> {}
                   (into (::session/token-claims request))
                   (into (::actoken/token-claims request)))]

    {:request/path       (:path request)
     :request/method     (:method request)
     :request/params     (:params request)
     :request/user-agent (yreq/get-header request "user-agent")
     :request/ip-addr    (inet/parse-request request)
     :request/profile-id (:uid claims)
     :version/frontend   (or (yreq/get-header request "x-frontend-version") "unknown")
     :version/backend    (:full cf/version)}))


(defmulti handle-error
  (fn [cause _ _]
    (-> cause ex-data :type)))

(defmulti handle-exception
  (fn [cause _ _]
    (class cause)))

(defmethod handle-error :authentication
  [err _ _]
  {::yres/status 401
   ::yres/body (ex-data err)})

(defmethod handle-error :authorization
  [err _ _]
  {::yres/status 403
   ::yres/body (ex-data err)})

(defmethod handle-error :restriction
  [err request _]
  (let [{:keys [code] :as data} (ex-data err)]
    (if (= code :method-not-allowed)
      {::yres/status 405
       ::yres/body data}

      (binding [l/*context* (request->context request)]
        (l/err :hint "restriction error" :data data)
        {::yres/status 400
         ::yres/body data}))))

(defmethod handle-error :rate-limit
  [err _ _]
  (let [headers (-> err ex-data ::http/headers)]
    {::yres/status 429
     ::yres/headers headers}))

(defmethod handle-error :concurrency-limit
  [err _ _]
  (let [headers (-> err ex-data ::http/headers)]
    {::yres/status 429
     ::yres/headers headers}))

(defmethod handle-error :validation
  [err request parent-cause]
  (let [{:keys [code] :as data} (ex-data err)]
    (cond
      (or (= code :spec-validation)
          (= code :params-validation)
          (= code :schema-validation)
          (= code :data-validation))
      (let [explain (ex/explain data)]
        {::yres/status 400
         ::yres/body   (-> data
                           (dissoc ::s/problems ::s/value ::s/spec ::sm/explain)
                           (cond-> explain (assoc :explain explain)))})

      (= code :vern-conflict)
      {::yres/status 409 ;; 409 - Conflict
       ::yres/body data}

      (= code :request-body-too-large)
      {::yres/status 413 ::yres/body data}

      (= code :invalid-image)
      (binding [l/*context* (request->context request)]
        (let [cause (or parent-cause err)]
          (l/warn :hint "unexpected error on processing image" :cause cause)
          {::yres/status 400 ::yres/body data}))

      :else
      {::yres/status 400 ::yres/body data})))

(defmethod handle-error :assertion
  [error request parent-cause]
  (binding [l/*context* (request->context request)]
    (let [{:keys [code] :as data} (ex-data error)
          cause (or parent-cause error)]
      (cond
        (= code :data-validation)
        (let [explain (ex/explain data)]
          (l/error :hint "data assertion error" :cause cause)
          {::yres/status 500
           ::yres/body   (-> data
                             (dissoc ::sm/explain)
                             (cond-> explain (assoc :explain explain))
                             (assoc :type :server-error)
                             (assoc :code :assertion))})

        (= code :spec-validation)
        (let [explain (ex/explain data)]
          (l/error :hint "spec assertion error" :cause cause)
          {::yres/status 500
           ::yres/body   (-> data
                             (dissoc ::s/problems ::s/value ::s/spec)
                             (cond-> explain (assoc :explain explain))
                             (assoc :type :server-error)
                             (assoc :code :assertion))})

        :else
        (do
          (l/error :hint "assertion error" :cause cause)
          {::yres/status 500
           ::yres/body   (-> data
                             (assoc :type :server-error)
                             (assoc :code :assertion))})))))

(defmethod handle-error :not-found
  [err _ _]
  {::yres/status 404
   ::yres/body (ex-data err)})

(defmethod handle-error :internal
  [error request parent-cause]
  (binding [l/*context* (request->context request)]
    (let [cause (or parent-cause error)
          data  (ex-data error)]
      (l/error :hint "internal error" :cause cause)
      {::yres/status 500
       ::yres/body (-> data
                       (assoc :type :server-error)
                       (update :code #(or % :unhandled))
                       (assoc :hint (ex-message error)))})))

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
        {::yres/status 504
         ::yres/body {:type :server-error
                      :code :statement-timeout
                      :hint (ex-message error)}}

        (= state "25P03")
        {::yres/status 504
         ::yres/body {:type :server-error
                      :code :idle-in-transaction-timeout
                      :hint (ex-message error)}}

        :else
        {::yres/status 500
         ::yres/body {:type :server-error
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
        {::yres/status 500
         ::yres/body {:type :server-error
                      :code :unexpected
                      :hint (ex-message error)}})

      :else
      (binding [l/*context* (request->context request)]
        (l/error :hint "unhandled error" :cause cause)
        {::yres/status 500
         ::yres/body (-> edata
                         (assoc :type :server-error)
                         (update :code #(or % :unhandled))
                         (assoc :hint (ex-message error)))}))))

(defmethod handle-exception java.io.IOException
  [cause _ _]
  (l/wrn :hint "io exception" :cause cause)
  {::yres/status 500
   ::yres/body {:type :server-error
                :code :io-exception
                :hint (ex-message cause)}})

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
  (::yres/body (handle cause request)))
