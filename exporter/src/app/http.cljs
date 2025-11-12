;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http
  (:require
   ["cookies$default" :as Cookies]
   ["inflation$default" :as inflate]
   ["node:http" :as http]
   ["node:stream$default" :as stream]
   ["raw-body$default" :as raw-body]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.handlers :as handlers]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [promesa.core :as p]))

(l/set-level! :info)

(defprotocol IStreamableResponseBody
  (write-body! [_ response]))

(extend-protocol IStreamableResponseBody
  string
  (write-body! [data response]
    (.write ^js response data)
    (.end ^js response))

  js/Buffer
  (write-body! [data response]
    (.write ^js response data)
    (.end ^js response))

  stream/Stream
  (write-body! [data response]
    (.pipe ^js data response)
    (.on ^js data "error" (fn [cause]
                            (js/console.error cause)
                            (.end response)))))

(defn- handle-response
  [{:keys [:response/body
           :response/headers
           :response/status
           response]
    :as exchange}]
  (let [status  (or status 200)
        headers (clj->js headers)
        body    (or body "")]
    (.writeHead ^js response status headers)
    (write-body! body response)))

(defn- parse-headers
  [req]
  (let [orig (unchecked-get req "headers")]
    (persistent!
     (reduce #(assoc! %1 (str/lower %2) (unchecked-get orig %2))
             (transient {})
             (js/Object.keys orig)))))

(defn- wrap-body-params
  [handler]
  (let [opts #js {:limit "60mb" :encoding "utf8"}]
    (fn [{:keys [:request/method :request/headers request] :as exchange}]
      (let [ctype (get headers "content-type")]
        (if (= method "post")
          (-> (raw-body (inflate request) opts)
              (p/then (fn [data]
                        (cond-> data
                          (= ctype "application/transit+json")
                          (t/decode-str))))
              (p/then (fn [data]
                        (handler (assoc exchange :request/body-params data)))))
          (handler exchange))))))

(defn- wrap-params
  [handler]
  (fn [{:keys [:request/body-params :request/query-params] :as exchange}]
    (handler (assoc exchange :request/params (merge query-params body-params)))))

(defn- wrap-response-format
  [handler]
  (fn [exchange]
    (p/then
     (handler exchange)
     (fn [{:keys [:response/body :response/status] :as exchange}]
       (cond
         (map? body)
         (let [data (t/encode-str body {:type :json-verbose})
               size (js/Buffer.byteLength data "utf-8")]
           (-> exchange
               (assoc :response/body data)
               (assoc :response/status 200)
               (update :response/headers assoc "content-type" "application/transit+json")
               (update :response/headers assoc "content-length" size)))

         (and (nil? body)
              (= 200 status))
         (-> exchange
             (assoc :response/body "")
             (assoc :response/status 204)
             (assoc :response/headers {"content-length" 0}))

         :else
         exchange)))))

(defn- wrap-query-params
  [handler]
  (fn [{:keys [:request/uri] :as exchange}]
    (handler (assoc exchange :request/query-params (u/query-string->map (:query uri))))))

(defn- wrap-error
  [handler on-error]
  (fn [exchange]
    (-> (p/do (handler exchange))
        (p/catch (fn [cause] (on-error cause exchange))))))

(defn- wrap-auth
  [handler cookie-name]
  (fn [{:keys [:request/cookies] :as exchange}]
    (let [token (.get ^js cookies cookie-name)]
      (handler (cond-> exchange token (assoc :request/auth-token token))))))

(defn- wrap-health
  "Add /healthz entry point intercept."
  [handler]
  (fn [{:keys [:request/path] :as exchange}]
    (if (= path "/readyz")
      (assoc exchange
             :response/status 200
             :response/body "OK")
      (handler exchange))))

(defn- create-adapter
  [handler]
  (fn [req res]
    (let [cookies  (Cookies. req res)
          headers  (parse-headers req)
          uri      (u/uri (unchecked-get req "url"))
          exchange {:request/method (str/lower (unchecked-get req "method"))
                    :request/path (:path uri)
                    :request/uri uri
                    :request/headers headers
                    :request/cookies cookies
                    :request req
                    :response res}]
      (-> (p/do (handler exchange))
          (p/then handle-response)))))

(defn- create-server
  [handler]
  (.createServer ^js http (create-adapter handler)))

(def instance (atom nil))

(defn init
  []
  (let [handler (-> handlers/handler
                    (wrap-health)
                    (wrap-auth "auth-token")
                    (wrap-response-format)
                    (wrap-params)
                    (wrap-query-params)
                    (wrap-body-params)
                    (wrap-error handlers/on-error))
        server  (create-server handler)
        port    (cf/get :http-server-port 6061)]

    (.listen server port)
    (l/info :hint "welcome to penpot"
            :module "exporter"
            :flags cf/flags
            :version (:full cf/version))
    (l/info :hint "starting http server" :port port)
    (reset! instance server)))

(defn stop
  []
  (if-let [server @instance]
    (p/create (fn [resolve]
                (.close server (fn []
                                 (l/info :hint "shutdown http server")
                                 (resolve)))))
    (p/resolved nil)))
