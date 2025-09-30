;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.security
  "Additional security layer middlewares"
  (:require
   [app.config :as cf]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(def ^:private safe-methods
  #{:get :head :options})

(defn- wrap-sec-fetch-metadata
  "Sec-Fetch metadata security layer middleware"
  [handler]
  (fn [request]
    (let [site (yreq/get-header request "sec-fetch-site")]
      (cond
        (= site "same-origin")
        (handler request)

        (or (= site "same-site")
            (= site "cross-site"))
        (if (contains? safe-methods (yreq/method request))
          (handler request)
          {::yres/status 403})

        :else
        (handler request)))))

(def sec-fetch-metadata
  {:name ::sec-fetch-metadata
   :compile (fn [_ _]
              (when (contains? cf/flags :sec-fetch-metadata-middleware)
                wrap-sec-fetch-metadata))})

(defn- wrap-client-header-check
  "Check for a penpot custom header to be present as additional CSRF
  protection"
  [handler]
  (fn [request]
    (let [client (yreq/get-header request "x-client")]
      (if (some? client)
        (handler request)
        {::yres/status 403}))))

(def client-header-check
  {:name ::client-header-check
   :compile (fn [_ _]
              (when (contains? cf/flags :client-header-check-middleware)
                wrap-client-header-check))})
