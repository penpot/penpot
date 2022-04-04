;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.repo
  (:require
   [app.common.data :as d]
   [app.common.uri :as u]
   [app.config :as cf]
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

    (= 413 status)
    (rx/throw {:type :validation
               :code :request-body-too-large})

    (and (>= status 400)
         (map? body))
    (rx/throw body)

    :else
    (rx/throw {:type :unexpected-error
               :status status
               :data body})))

(def ^:private base-uri cf/public-uri)

(defn- send-query!
  "A simple helper for send and receive transit data on the penpot
  query api."
  [id params]
  (->> (http/send! {:method :get
                    :uri (u/join base-uri "api/rpc/query/" (name id))
                    :credentials "include"
                    :query params})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defn- send-mutation!
  "A simple helper for a common case of sending and receiving transit
  data to the penpot mutation api."
  [id params]
  (->> (http/send! {:method :post
                    :uri (u/join base-uri "api/rpc/mutation/" (name id))
                    :credentials "include"
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
    (->> (http/send! {:method :post
                      :uri uri
                      :credentials "include"
                      :query params})
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response))))

(defmethod mutation :send-feedback
  [_ params]
  (->> (http/send! {:method :post
                    :uri (u/join base-uri "api/feedback")
                    :credentials "include"
                    :body (http/transit-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defn- send-export
  [{:keys [blob?] :as params}]
  (->> (http/send! {:method :post
                    :uri (u/join base-uri "api/export")
                    :body (http/transit-data (dissoc params :blob?))
                    :credentials "include"
                    :response-type (if blob? :blob :text)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defmethod query :exporter
  [_ params]
  (let [default {:wait false
                 :blob? false
                 :uri (str base-uri)}]
    (send-export (merge default params))))

(derive :upload-file-media-object ::multipart-upload)
(derive :update-profile-photo ::multipart-upload)
(derive :update-team-photo ::multipart-upload)

(defmethod mutation ::multipart-upload
  [id params]
  (->> (http/send! {:method :post
                    :uri  (u/join base-uri "api/rpc/mutation/" (name id))
                    :credentials "include"
                    :body (http/form-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))
