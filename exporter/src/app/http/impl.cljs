;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.impl
  (:require
   ["http" :as http]
   ["inflation" :as inflate]
   ["koa" :as koa]
   ["raw-body" :as raw-body]
   [app.util.transit :as t]
   [cuerdas.core :as str]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]
   [reitit.core :as r])
  (:import
   goog.Uri))

(defn- query-params
  "Given goog.Uri, read query parameters into Clojure map."
  [^Uri uri]
  (let [^js q (.getQueryData uri)]
    (->> q
         (.getKeys)
         (map (juxt keyword #(.get q %)))
         (into {}))))

(defn- match
  [router ctx]
  (let [uri (.parse Uri (unchecked-get ctx "originalUrl"))]
    (when-let [match (r/match-by-path router (.getPath ^js uri))]
      (assoc match :query-params (query-params uri)))))

(defn- handle-error
  [error request]
  (let [{:keys [type message code] :as data} (ex-data error)]
    (cond
      (= :validation type)
      (let [header (get-in request [:headers "accept"])]
        (if (and (str/starts-with? header "text/html")
                 (= :spec-validation (:code data)))
          {:status 400
           :headers {"content-type" "text/html"}
           :body (str "<pre style='font-size:16px'>" (:explain data) "</pre>\n")}
          {:status 400
           :headers {"content-type" "text/html"}
           :body (str "<pre style='font-size:16px'>" (:explain data) "</pre>\n")}))

      :else
      (do
        (log/error :msg "Unexpected error"
                   :error error)
        (js/console.error error)
        {:status 500
         :headers {"x-metadata" (t/encode {:type :unexpected
                                           :message (ex-message error)})}
         :body ""}))))


(defn- handle-response
  [ctx {:keys [body headers status] :or {headers {} status 200}}]
  (run! (fn [[k v]] (.set ^js ctx k v)) headers)
  (set! (.-body ^js ctx) body)
  (set! (.-status ^js ctx) status)
  nil)

(defn- parse-headers
  [ctx]
  (let [orig (unchecked-get ctx "headers")]
    (persistent!
     (reduce #(assoc! %1 %2 (unchecked-get orig %2))
             (transient {})
             (js/Object.keys orig)))))

(def parse-body? #{"POST" "PUT" "DELETE"})

(defn- parse-body
  [ctx]
  (let [headers (unchecked-get ctx "headers")
        ctype   (unchecked-get headers "content-type")]
    (when (parse-body? (.-method ^js ctx))
      (-> (inflate (.-req ^js ctx))
          (raw-body #js {:limit "5mb" :encoding "utf8"})
          (p/then (fn [data]
                  (cond-> data
                    (= ctype "application/transit+json")
                    (t/decode))))))))

(defn- wrap-handler
  [f extra]
  (fn [ctx]
    (p/let [cookies (unchecked-get ctx "cookies")
            headers (parse-headers ctx)
            body    (parse-body ctx)
            request (assoc extra
                           :method (str/lower (unchecked-get ctx "method"))
                           :body body
                           :ctx ctx
                           :headers headers
                           :cookies cookies)]
      (-> (p/do! (f request))
          (p/then  (fn [rsp]
                     (when (map? rsp)
                       (handle-response ctx rsp))))
          (p/catch (fn [err]
                     (->> (handle-error err request)
                          (handle-response ctx))))))))

(defn- router-handler
  [router]
  (fn [{:keys [ctx body] :as request}]
    (let [route   (match router ctx)
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
  [handler]
  (.createServer http @handler))

(defn handler
  [router extra]
  (let [instance (doto (new koa)
                   (.use (-> (router-handler router)
                             (wrap-handler extra))))]
    (specify! instance
      cljs.core/IDeref
      (-deref [_]
        (.callback instance)))))

