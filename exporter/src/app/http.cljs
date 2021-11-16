;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http.export :refer [export-handler]]
   [app.http.export-frames :refer [export-frames-handler]]
   [app.http.impl :as impl]
   [app.sentry :as sentry]
   [app.util.transit :as t]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [reitit.core :as r]))

(l/set-level! :info)

(def routes
  [["/export-frames" {:handler export-frames-handler}]
   ["/export" {:handler export-handler}]])

(def instance (atom nil))

(defn- on-error
  [error request]
  (let [{:keys [type message code] :as data} (ex-data error)]
    (sentry/capture-exception error {::sentry/request request
                                     :ex-data data})

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

      (and (= :internal type)
           (= :browser-not-ready code))
      {:status 503
         :headers {"x-error" (t/encode data)}
       :body ""}

      :else
      (do
        (l/error :msg "Unexpected error" :error error)
        (js/console.error error)
        {:status 500
         :headers {"x-error" (t/encode data)}
         :body ""}))))

(defn init
  []
  (let [router  (r/router routes)
        handler (impl/router-handler router)
        server  (impl/server handler on-error)
        port    (cf/get :http-server-port 6061)]
    (.listen server port)
    (l/info :msg "welcome to penpot"
              :module "exporter"
              :version (:full @cf/version))
    (l/info :msg "starting http server" :port port)
    (reset! instance server)))

(defn stop
  []
  (if-let [server @instance]
    (p/create (fn [resolve]
                (.close server (fn []
                                 (l/info :msg "shutdown http server")
                                 (resolve)))))
    (p/resolved nil)))
