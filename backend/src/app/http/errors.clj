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
        error  (ex-data err)]
    (cond
      (and (str/starts-with? header "text/html")
           (= :spec-validation (:code error)))
      {:status 400
       :headers {"content-type" "text/html"}
       :body (str "<pre style='font-size:16px'>"
                  (:hint-verbose error)
                  "</pre>\n")}
      :else
      {:status 400
       :body error})))

(defmethod handle-exception :not-found
  [err _]
  (let [response (ex-data err)]
    {:status 404
     :body response}))

(defmethod handle-exception :service-error
  [err req]
  (handle-exception (.getCause ^Throwable err) req))

(defmethod handle-exception :parse
  [err _]
  {:status 400
   :body {:type :parse
          :message (ex-message err)}})

(defn get-context-string
  [err request]
  (str
   "=| uri:          " (pr-str (:uri request)) "\n"
   "=| method:       " (pr-str (:request-method request)) "\n"
   "=| path-params:  " (pr-str (:path-params request)) "\n"
   "=| query-params: " (pr-str (:query-params request)) "\n"

   (when-let [bparams (:body-params request)]
     (str "=| body-params:  " (pr-str bparams) "\n"))

   (when (ex/ex-info? err)
     (str "=| ex-data:      " (pr-str (ex-data err)) "\n"))

   "\n"))


(defmethod handle-exception :assertion
  [err request]
  (let [{:keys [data] :as edata} (ex-data err)]
    (log/errorf err
                (str "Assertion error\n"
                     (get-context-string err request)
                     (with-out-str (expound/printer data))))
    {:status 500
     :body {:type :internal-error
            :message "Assertion error"
            :data (ex-data err)}}))

(defmethod handle-exception :default
  [err request]
  (log/errorf err (str "Internal Error\n" (get-context-string err request)))
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
