;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.errors
  "A errors handling for the http server."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.http :as-alias http]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

(def ^:dynamic *context* {})

(defn- parse-client-ip
  [request]
  (or (some-> (yrq/get-header request "x-forwarded-for") (str/split ",") first)
      (yrq/get-header request "x-real-ip")
      (yrq/remote-addr request)))

(defn get-context
  [request]
  (merge
   *context*
   {:path          (:path request)
    :method        (:method request)
    :params        (:params request)
    :ip-addr       (parse-client-ip request)
    :profile-id    (:profile-id request)}
   (let [headers (:headers request)]
     {:user-agent (get headers "user-agent")
      :frontend-version (get headers "x-frontend-version" "unknown")})))

(defmulti handle-exception
  (fn [err & _rest]
    (let [edata (ex-data err)]
      (or (:type edata)
          (class err)))))

(defmethod handle-exception :authentication
  [err _]
  (yrs/response 401 (ex-data err)))

(defmethod handle-exception :restriction
  [err _]
  (yrs/response 400 (ex-data err)))

(defmethod handle-exception :rate-limit
  [err _]
  (let [headers (-> err ex-data ::http/headers)]
    (yrs/response :status 429 :body "" :headers headers)))

(defmethod handle-exception :validation
  [err _]
  (let [{:keys [code] :as data} (ex-data err)]
    (cond
      (= code :spec-validation)
      (let [explain (us/pretty-explain data)]
        (yrs/response :status 400
                      :body   (-> data
                                  (dissoc ::s/problems ::s/value)
                                  (cond-> explain (assoc :explain explain)))))

      (= code :request-body-too-large)
      (yrs/response :status 413 :body data)

      :else
      (yrs/response :status 400 :body data))))

(defmethod handle-exception :assertion
  [error request]
  (let [edata (ex-data error)
        explain (us/pretty-explain edata)]
    (l/error ::l/raw (str (ex-message error) "\n" explain)
             ::l/context (get-context request)
             :cause error)
    (yrs/response :status 500
                  :body   {:type :server-error
                           :code :assertion
                           :data (-> edata
                                     (dissoc ::s/problems ::s/value ::s/spec)
                                     (cond-> explain (assoc :explain explain)))})))

(defmethod handle-exception :not-found
  [err _]
  (yrs/response 404 (ex-data err)))

(defmethod handle-exception org.postgresql.util.PSQLException
  [error request]
  (let [state (.getSQLState ^java.sql.SQLException error)]
    (l/error ::l/raw (ex-message error)
             ::l/context (get-context request)
             :cause error)
    (cond
      (= state "57014")
      (yrs/response 504 {:type :server-error
                         :code :statement-timeout
                         :hint (ex-message error)})

      (= state "25P03")
      (yrs/response 504 {:type :server-error
                         :code :idle-in-transaction-timeout
                         :hint (ex-message error)})

      :else
      (yrs/response 500 {:type :server-error
                         :code :unexpected
                         :hint (ex-message error)
                         :state state}))))

(defmethod handle-exception :default
  [error request]
  (let [edata (ex-data error)]
    (cond
      ;; This means that exception is not a controlled exception.
      (nil? edata)
      (do
        (l/error ::l/raw (ex-message error)
                 ::l/context (get-context request)
                 :cause error)
        (yrs/response 500 {:type :server-error
                           :code :unexpected
                           :hint (ex-message error)}))

      ;; This is a special case for the idle-in-transaction error;
      ;; when it happens, the connection is automatically closed and
      ;; next-jdbc combines the two errors in a single ex-info. We
      ;; only need the :handling error, because the :rollback error
      ;; will be always "connection closed".
      (and (ex/exception? (:rollback edata))
           (ex/exception? (:handling edata)))
      (handle-exception (:handling edata) request)

      :else
      (do
        (l/error ::l/raw (ex-message error)
                 ::l/context (get-context request)
                 :cause error)
        (yrs/response 500 {:type :server-error
                           :code :unhandled
                           :hint (ex-message error)
                           :data edata})))))

(defn handle
  [cause request]
  (cond
    (or (instance? java.util.concurrent.CompletionException cause)
        (instance? java.util.concurrent.ExecutionException cause))
    (handle-exception (.getCause ^Throwable cause) request)

    (ex/wrapped? cause)
    (let [context (meta cause)
          cause   (deref cause)]
      (binding [*context* context]
        (handle-exception cause request)))

    :else
    (handle-exception cause request)))
