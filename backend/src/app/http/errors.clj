;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.errors
  "A errors handling for the http server."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.util.logging :as l]
   [clojure.pprint]
   [cuerdas.core :as str]))

(defn- parse-client-ip
  [{:keys [headers] :as request}]
  (or (some-> (get headers "x-forwarded-for") (str/split ",") first)
      (get headers "x-real-ip")
      (get request :remote-addr)))

(defn- stringify-data
  [data]
  (binding [clojure.pprint/*print-right-margin* 200]
    (let [result (with-out-str (clojure.pprint/pprint data))]
      (str/prune result (* 1024 1024) "[...]"))))

(defn get-error-context
  [request error]
  (let [data (ex-data error)]
    (d/without-nils
     (merge
      {:id      (str (uuid/next))
       :path    (str (:uri request))
       :method  (name (:request-method request))
       :hint    (or (:hint data) (ex-message error))
       :params  (stringify-data (:params request))
       :data    (stringify-data (dissoc data :explain))
       :ip-addr (parse-client-ip request)
       :explain (str/prune (:explain data) (* 1024 1024) "[...]")}

     (when-let [id (:profile-id request)]
       {:profile-id id})

     (let [headers (:headers request)]
       {:user-agent (get headers "user-agent")
        :frontend-version (get headers "x-frontend-version" "unknown")})

     (when (map? data)
       {:error-type (:type data)
        :error-code (:code data)})))))

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
       :body   (dissoc edata :data)})))

(defmethod handle-exception :assertion
  [error request]
  (let [edata (ex-data error)
        cdata (get-error-context request error)]
    (l/update-thread-context! cdata)
    (l/error :hint "internal error: assertion"
             :error-id (str (:id cdata))
             :cause error)

    {:status 500
     :body {:type :server-error
            :code :assertion
            :data (dissoc edata :data)}}))

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
      (let [cdata (get-error-context request error)]
        (l/update-thread-context! cdata)
        (l/error :hint "internal error"
                 :error-message (ex-message error)
                 :error-id (str (:id cdata))
                 :cause error)
        {:status 500
         :body {:type :server-error
                :code :unexpected
                :hint (ex-message error)
                :data edata}}))))

(defmethod handle-exception org.postgresql.util.PSQLException
  [error request]
  (let [cdata (get-error-context request error)
        state (.getSQLState ^java.sql.SQLException error)]

    (l/update-thread-context! cdata)
    (l/error :hint "psql exception"
             :error-message (ex-message error)
             :error-id (str (:id cdata))
             :sql-state state
             :cause error)

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
