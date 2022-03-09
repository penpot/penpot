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
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

(defn- parse-client-ip
  [request]
  (or (some-> (yrq/get-header request "x-forwarded-for") (str/split ",") first)
      (yrq/get-header request "x-real-ip")
      (yrq/remote-addr request)))

(defn get-error-context
  [request error]
  (let [data (ex-data error)]
    (merge
     {:path          (:uri request)
      :method        (:request-method request)
      :hint          (ex-message error)
      :params        (:params request)

      :spec-problems (some->> data ::s/problems (take 10) seq vec)
      :spec-value    (some->> data ::s/value)
      :data          (some-> data (dissoc ::s/problems ::s/value ::s/spec))
      :ip-addr       (parse-client-ip request)
      :profile-id    (:profile-id request)}

     (let [headers (:headers request)]
       {:user-agent (get headers "user-agent")
        :frontend-version (get headers "x-frontend-version" "unknown")})

     (when (and data (::s/problems data))
       {:spec-explain (us/pretty-explain data)}))))

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

(defmethod handle-exception :validation
  [err _]
  (let [data    (ex-data err)
        explain (us/pretty-explain data)]
    (yrs/response 400 (-> data
                          (dissoc ::s/problems ::s/value)
                          (cond-> explain (assoc :explain explain))))))

(defmethod handle-exception :assertion
  [error request]
  (let [edata (ex-data error)
        explain (us/pretty-explain edata)]
    (l/error ::l/raw (ex-message error)
             ::l/context (get-error-context request error)
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

(defmethod handle-exception :default
  [error request]
  (let [edata (ex-data error)]
    ;; NOTE: this is a special case for the idle-in-transaction error;
    ;; when it happens, the connection is automatically closed and
    ;; next-jdbc combines the two errors in a single ex-info. We only
    ;; need the :handling error, because the :rollback error will be
    ;; always "connection closed".
    (if (and (ex/exception? (:rollback edata))
             (ex/exception? (:handling edata)))
      (handle-exception (:handling edata) request)
      (do
        (l/error ::l/raw (ex-message error)
                 ::l/context (get-error-context request error)
                 :cause error)
        (yrs/response 500 {:type :server-error
                           :code :unexpected
                           :hint (ex-message error)
                           :data edata})))))

(defmethod handle-exception org.postgresql.util.PSQLException
  [error request]
  (let [state (.getSQLState ^java.sql.SQLException error)]
    (l/error ::l/raw (ex-message error)
             ::l/context (get-error-context request error)
             :cause error)
    (cond
      (= state "57014")
      (yrs/response 504 {:type :server-timeout
                         :code :statement-timeout
                         :hint (ex-message error)})

      (= state "25P03")
      (yrs/response 504 {:type :server-timeout
                         :code :idle-in-transaction-timeout
                         :hint (ex-message error)})

      :else
      (yrs/response 500 {:type :server-error
                         :code :psql-exception
                         :hint (ex-message error)
                         :state state}))))

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause ^Throwable error) req)
    (handle-exception error req)))
