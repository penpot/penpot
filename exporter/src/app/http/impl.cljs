;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.impl
  (:require
   ["http" :as http]
   ["cookies" :as Cookies]
   ["inflation" :as inflate]
   ["raw-body" :as raw-body]
   [app.util.transit :as t]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [promesa.core :as p]
   [reitit.core :as r]))

(def methods-with-body
  #{"POST" "PUT" "DELETE"})

(defn- match
  [router {:keys [path query] :as request}]
  (when-let [match (r/match-by-path router path)]
    (assoc match :query-params (u/query-string->map query))))

(defn- handle-response
  [req res]
  (fn [{:keys [body headers status] :or {headers {} status 200}}]
    (.writeHead ^js res status (clj->js headers))
    (.end ^js res body)))

(defn- parse-headers
  [req]
  (let [orig (unchecked-get req "headers")]
    (persistent!
     (reduce #(assoc! %1 %2 (unchecked-get orig %2))
             (transient {})
             (js/Object.keys orig)))))

(defn- parse-body
  [req]
  (let [headers (unchecked-get req "headers")
        method  (unchecked-get req "method")
        ctype   (unchecked-get headers "content-type")
        opts     #js {:limit "5mb" :encoding "utf8"}]
    (when (contains? methods-with-body method)
      (-> (raw-body (inflate req) opts)
          (p/then (fn [data]
                    (cond-> data
                      (= ctype "application/transit+json")
                      (t/decode))))))))

(defn- handler-adapter
  [handler on-error]
  (fn [req res]
    (let [cookies (new Cookies req res)
          headers (parse-headers req)
          uri     (u/uri (unchecked-get req "url"))
          request {:method (str/lower (unchecked-get req "method"))
                   :path (:path uri)
                   :query (:query uri)
                   :url uri
                   :headers headers
                   :cookies cookies
                   :internal-request req
                   :internal-response res}]
      (-> (parse-body req)
          (p/then (fn [body]
                    (let [request (assoc request :body body)]
                      (handler request))))
          (p/catch (fn [error] (on-error error request)))
          (p/then (handle-response req res))))))

(defn router-handler
  [router]
  (fn [{:keys [body] :as request}]
    (let [route   (match router request)
          params  (merge {}
                         (:query-params route)
                         (:path-params route)
                         (when (map? body) body))
          request (assoc request
                         :route route
                         :params params)

          handler (get-in route [:data :handler])]
      (if (and route handler)
        (handler request)
        {:status 404
         :body "Not found"}))))

(defn server
  [handler on-error]
  (.createServer ^js http (handler-adapter handler on-error)))
