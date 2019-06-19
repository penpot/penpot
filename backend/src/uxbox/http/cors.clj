;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.cors
  "CORS Implementation for Async Ring"
  (:require [cuerdas.core :as str]))

(defn- allow-origin?
  [value {:keys [origin]}]
  (cond
    (nil? value) value
    (= origin "*") origin
    (set? origin) (origin value)
    (= origin value) origin))

(defn- normalize-headers
  [headers]
  (->> (map (comp str/lower name) headers)
       (str/join ",")))

(defn- normalize-methods
  [methods]
  (->> (map (comp str/upper name) methods)
       (str/join ",")))

(defn- get-preflight-headers
  [origin {:keys [allow-methods allow-headers max-age allow-credentials]
           :or {allow-methods #{:get :post :put :delete}}
           :as opts}]
  (when-let [origin (allow-origin? origin opts)]
    (cond-> {"access-control-allow-origin" origin
             "access-control-allow-methods" (normalize-methods allow-methods)}
      allow-credentials
      (assoc "access-control-allow-credentials" "true")

      max-age
      (assoc "access-control-max-age" (str max-age))

      allow-headers
      (assoc "access-control-allow-headers" (normalize-headers allow-headers)))))

(defn get-response-headers
  [origin {:keys [allow-headers expose-headers allow-credentials] :as opts}]
  (when-let [origin (allow-origin? origin opts)]
    (cond-> {"access-control-allow-origin" origin}
      allow-credentials
      (assoc "access-control-allow-credentials" "true")

      allow-headers
      (assoc "access-control-allow-headers" (normalize-headers allow-headers))

      expose-headers
      (assoc "access-control-expose-headers" (normalize-headers expose-headers)))))

(defn- cors-preflight?
  [{:keys [request-method headers] :as req}]
  (and (= request-method :options)
       (contains? headers "origin")
       (contains? headers "access-control-request-method")))

(defn wrap-cors
  "A chain handler that handles cors related headers."
  [handler opts]
  (fn [{:keys [headers] :as req} respond raise]
    (let [origin (get headers "origin")]
      (if (cors-preflight? req)
        (let [headers (get-preflight-headers origin opts)]
          (respond {:status 200 :headers headers :body ""}))
        (let [headers (get-response-headers origin opts)
              wrapped-respond (fn [response] (respond (update response :headers merge headers)))]
          (handler req wrapped-respond raise))))))



