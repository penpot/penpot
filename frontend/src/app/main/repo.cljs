;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.repo
  (:require
   [app.common.data :as d]
   [app.common.uri :as u]
   [app.config :as cfg]
   [app.util.http :as http]
   [beicon.core :as rx]))

(defn handle-response
  [{:keys [status body] :as response}]
  (cond
    (= 204 status)
    ;; We need to send "something" so the streams listening downstream can act
    (rx/of nil)

    (= 502 status)
    (rx/throw {:type :bad-gateway})

    (= 503 status)
    (rx/throw {:type :service-unavailable})

    (= 0 (:status response))
    (rx/throw {:type :offline})

    (= 200 status)
    (rx/of body)

    (and (>= status 400)
         (map? body))
    (rx/throw body)

    :else
    (rx/throw {:type :unexpected-error
               :status status
               :data body})))

(def ^:private base-uri cfg/public-uri)

(defn- send-query!
  "A simple helper for send and receive transit data on the penpot
  query api."
  [id params]
  (->> (http/send! {:method :get
                    :uri (u/join base-uri "api/rpc/query/" (name id))
                    :query params})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defn- send-mutation!
  "A simple helper for a common case of sending and receiving transit
  data to the penpot mutation api."
  [id params]
  (->> (http/send! {:method :post
                    :uri (u/join base-uri "api/rpc/mutation/" (name id))
                    :body (http/transit-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defn- dispatch [& args] (first args))

(defmulti query dispatch)
(defmulti mutation dispatch)

(defmethod query :default
  [id params]
  (send-query! id params))

(defmethod mutation :default
  [id params]
  (send-mutation! id params))

(defn query!
  ([id] (query id {}))
  ([id params] (query id params)))

(defn mutation!
  ([id] (mutation id {}))
  ([id params] (mutation id params)))

(defmethod mutation :login-with-oauth
  [_ {:keys [provider] :as params}]
  (let [uri    (u/join base-uri "api/auth/oauth/" (d/name provider))
        params (dissoc params :provider)]
    (->> (http/send! {:method :post :uri uri :query params})
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response))))

(defmethod mutation :send-feedback
  [_ params]
  (->> (http/send! {:method :post
                    :uri (u/join base-uri "api/feedback")
                    :body (http/transit-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defmethod query :export
  [_ params]
  (->> (http/send! {:method :post
                    :uri (u/join base-uri "export")
                    :body (http/transit-data params)
                    :response-type :blob})
       (rx/mapcat handle-response)))

(derive :upload-file-media-object ::multipart-upload)
(derive :update-profile-photo ::multipart-upload)
(derive :update-team-photo ::multipart-upload)

(defmethod mutation ::multipart-upload
  [id params]
  (->> (http/send! {:method :post
                    :uri  (u/join base-uri "api/rpc/mutation/" (name id))
                    :body (http/form-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))
