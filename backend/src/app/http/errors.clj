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
   [app.common.uuid :as uuid]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(defn- parse-client-ip
  [{:keys [headers] :as request}]
  (or (some-> (get headers "x-forwarded-for") (str/split ",") first)
      (get headers "x-real-ip")
      (get request :remote-addr)))

(defn get-error-context
  [request error]
  (let [data (ex-data error)]
    (merge
     {:id            (uuid/next)
      :path          (:uri request)
      :method        (:request-method request)
      :hint          (or (:hint data) (ex-message error))
      :params        (l/stringify-data (:params request))
      :spec-problems (some-> data ::s/problems)
      :ip-addr       (parse-client-ip request)
      :profile-id    (:profile-id request)}

     (let [headers (:headers request)]
       {:user-agent (get headers "user-agent")
        :frontend-version (get headers "x-frontend-version" "unknown")})

     (dissoc data ::s/problems))))

(defmulti handle-exception
  (fn [err & _rest]
    (let [edata (ex-data err)]
      (or (:type edata)
          (class err)))))

(defmethod handle-exception :authentication
  [err _]
  {:status 401 :body (ex-data err)})

(defmethod handle-exception :restriction
  [err _]
  {:status 400 :body (ex-data err)})

(defmethod handle-exception :validation
  [err req]
  (let [header (get-in req [:headers "accept"])
        edata  (ex-data err)]
    (if (and (= :spec-validation (:code edata))
             (str/starts-with? header "text/html"))
      {:status 400
       :headers {"content-type" "text/html"}
       :body (str "<pre style='font-size:16px'>"
                  (:explain edata)
                  "</pre>\n")}
      {:status 400
       :body   (dissoc edata ::s/problems)})))

(defmethod handle-exception :assertion
  [error request]
  (let [edata (ex-data error)]
    (l/with-context (get-error-context request error)
      (l/error :hint (ex-message error) :cause error))
    {:status 500
     :body {:type :server-error
            :code :assertion
            :data (dissoc edata ::s/problems)}}))

(defmethod handle-exception :not-found
  [err _]
  {:status 404 :body (ex-data err)})

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
        (l/with-context (get-error-context request error)
          (l/error :hint (ex-message error) :cause error))

        {:status 500
         :body {:type :server-error
                :code :unexpected
                :hint (ex-message error)
                :data edata}}))))

(defmethod handle-exception org.postgresql.util.PSQLException
  [error request]
  (let [state (.getSQLState ^java.sql.SQLException error)]

    (l/with-context (get-error-context request error)
      (l/error :hint "psql exception"
               :error-message (ex-message error)
               :state state
               :cause error))

    (cond
      (= state "57014")
      {:status 504
       :body {:type :server-timeout
              :code :statement-timeout
              :hint (ex-message error)}}

      (= state "25P03")
      {:status 504
       :body {:type :server-timeout
              :code :idle-in-transaction-timeout
              :hint (ex-message error)}}

      :else
      {:status 500
       :body {:type :server-error
              :code :psql-exception
              :hint (ex-message error)
              :state state}})))

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause ^Throwable error) req)
    (handle-exception error req)))
