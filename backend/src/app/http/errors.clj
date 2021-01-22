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
   [clojure.pprint :refer [pprint]]
   [app.common.exceptions :as ex]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [expound.alpha :as expound]))

(defn get-context-string
  [request edata]
  (str "=| uri:    " (pr-str (:uri request)) "\n"
       "=| method: " (pr-str (:request-method request)) "\n"
       "=| params: \n"
       (with-out-str
         (pprint (:params request)))
       "\n"

       (when (map? edata)
         (str "=| ex-data: \n"
              (with-out-str
                (pprint edata))))

       "\n"))

(defmulti handle-exception
  (fn [err & _rest]
    (let [edata (ex-data err)]
      (or (:type edata)
          (class err)))))

(defmethod handle-exception :authentication
  [err _]
  {:status 401 :body (ex-data err)})

(defn- explain-error
  [error]
  (with-out-str
    (expound/printer (:data error))))

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
  (let [edata (ex-data error)]
    (log/error error
                (str "Internal error: assertion\n"
                     (get-context-string request edata)
                     (explain-error edata)))
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
  (let [edata (ex-data error)]
    (log/error error
                (str "Internal Error: "
                     (ex-message error)
                     (get-context-string request edata)))
    {:status 500
     :body {:type :server-error
            :hint (ex-message error)
            :data edata}}))

(defn handle
  [error req]
  (if (or (instance? java.util.concurrent.CompletionException error)
          (instance? java.util.concurrent.ExecutionException error))
    (handle-exception (.getCause ^Throwable error) req)
    (handle-exception error req)))
