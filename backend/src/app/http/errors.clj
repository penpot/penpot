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
   [app.common.exceptions :as ex]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [expound.alpha :as expound]))


(defn get-context-string
  [request edata]
  (str "=| uri:    " (pr-str (:uri request)) "\n"
       "=| method: " (pr-str (:request-method request)) "\n"
       "=| params: " (pr-str (:params request)) "\n"

       (when (map? edata)
         (str "=| ex-data:      " (pr-str edata) "\n"))

       "\n"))

(defmulti handle-exception
  (fn [err & _rest]
    (let [edata (ex-data err)]
      (or (:type edata)
          (class err)))))

(defmethod handle-exception :authorization
  [err _]
  {:status 403
   :body (ex-data err)})

(defmethod handle-exception :validation
  [err req]
  (let [header (get-in req [:headers "accept"])
        edata  (ex-data err)]
    (cond
      (= :spec-validation (:code edata))
      (if (str/starts-with? header "text/html")
        {:status 400
         :headers {"content-type" "text/html"}
         :body (str "<pre style='font-size:16px'>"
                    (with-out-str
                      (expound/printer (:data edata)))
                    "</pre>\n")}
        {:status 400
         :body (assoc edata :explain (with-out-str (expound/printer (:data edata))))})

      :else
      {:status 400
       :body edata})))

(defmethod handle-exception :assertion
  [error request]
  (let [edata (ex-data error)]
    (log/errorf error
                (str "Assertion error\n"
                     (get-context-string request edata)
                     (with-out-str (expound/printer (:data edata)))))
    {:status 500
     :body (assoc edata :explain (with-out-str (expound/printer (:data edata))))}))

(defmethod handle-exception :not-found
  [err _]
  (let [response (ex-data err)]
    {:status 404
     :body response}))

(defmethod handle-exception :service-error
  [err req]
  (handle-exception (.getCause ^Throwable err) req))


(defmethod handle-exception :default
  [error request]
  (let [edata (ex-data error)]
    (log/errorf error
                (str "Internal Error\n"
                     (get-context-string request edata)))
    (if (nil? edata)
      {:status 500
       :body {:type :server-error
              :hint (ex-message error)}}
      {:status 500
       :body (dissoc edata :data)})))

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause ^Throwable error) req)
    (handle-exception error req)))
