;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.middleware
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.metrics :as mtx]
   [app.util.transit :as t]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]))

(defn- wrap-parse-request-body
  [handler]
  (letfn [(parse-transit [body]
            (let [reader (t/reader body)]
              (t/read! reader)))

          (parse-json [body]
            (let [reader (io/reader body)]
              (json/read reader)))

          (parse [type body]
            (try
              (case type
                :json (parse-json body)
                :transit (parse-transit body))
              (catch Exception e
                (let [type (if (:debug @cfg/config) :json-verbose :json)
                      data {:type :parse
                            :hint "unable to parse request body"
                            :message (ex-message e)}]
                  {:status 400
                   :headers {"content-type" "application/transit+json"}
                   :body (t/encode-str data {:type type})}))))]

    (fn [{:keys [headers body request-method] :as request}]
      (let [ctype (get headers "content-type")]
        (handler
         (case ctype
           "application/transit+json"
           (let [params (parse :transit body)]
             (-> request
                 (assoc :body-params params)
                 (update :params merge params)))

           "application/json"
           (let [params (parse :json body)]
             (-> request
                 (assoc :body-params params)
                 (update :params merge params)))

           request))))))

(def parse-request-body
  {:name ::parse-request-body
   :compile (constantly wrap-parse-request-body)})

(defn- impl-format-response-body
  [response]
  (let [body (:body response)
        type (if (:debug @cfg/config) :json-verbose :json)]
    (cond
      (coll? body)
      (-> response
          (assoc :body (t/encode body {:type type}))
          (update :headers assoc
                  "content-type"
                  "application/transit+json"))

      (nil? body)
      (assoc response :status 204 :body "")

      :else
      response)))

(defn- wrap-format-response-body
  [handler]
  (fn [request]
    (let [response (handler request)]
      (cond-> response
        (map? response) (impl-format-response-body)))))

(def format-response-body
  {:name ::format-response-body
   :compile (constantly wrap-format-response-body)})

(defn- wrap-errors
  [handler on-error]
  (fn [request]
    (try
      (handler request)
      (catch Throwable e
        (on-error e request)))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})

(def metrics
  {:name ::metrics
   :wrap (fn [handler]
           (mtx/wrap-counter handler {:id "http__requests_counter"
                                      :help "Absolute http requests counter."}))})

(def cookies
  {:name ::cookies
   :compile (constantly wrap-cookies)})

(def params
  {:name ::params
   :compile (constantly wrap-params)})

(def multipart-params
  {:name ::multipart-params
   :compile (constantly wrap-multipart-params)})

(def keyword-params
  {:name ::keyword-params
   :compile (constantly wrap-keyword-params)})

(defn- wrap-development-cors
  [handler]
  (letfn [(add-cors-headers [response]
            (update response :headers
                    (fn [headers]
                      (-> headers
                          (assoc "access-control-allow-origin" "http://localhost:3449")
                          (assoc "access-control-allow-methods" "GET,POST,DELETE,OPTIONS,PUT,HEAD,PATCH")
                          (assoc "access-control-allow-credentials" "true")
                          (assoc "access-control-expose-headers" "x-requested-with, content-type, cookie")
                          (assoc "access-control-allow-headers" "content-type")))))]
    (fn [request]
      (if (= (:request-method request) :options)
        (-> {:status 200 :body ""}
            (add-cors-headers))
        (let [response (handler request)]
          (add-cors-headers response))))))

(def development-cors
  {:name ::development-cors
   :compile (fn [& _args]
              (when *assert*
                wrap-development-cors))})

(def development-resources
  {:name ::development-resources
   :compile (fn [& _args]
              (when *assert*
                #(wrap-resource % "public")))})

