;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.repo
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [app.config :as cfg]
   [app.util.http-api :as http]))

(defn- handle-response
  [{:keys [status body] :as response}]
  (cond
    (= 204 status)
    ;; We need to send "something" so the streams listening downstream can act
    (rx/of :empty)

    (= 502 status)
    (rx/throw {:type :bad-gateway})

    (= 503 status)
    (rx/throw {:type :service-unavailable})

    (= 0 (:status response))
    (rx/throw {:type :offline})

    (and (= 200 status)
         (coll? body))
    (rx/of body)

    (and (>= status 400)
         (map? body))
    (rx/throw body)

    :else
    (rx/throw {:type :unexpected-error
               :status status
               :data body})))

(defn send-query!
  [id params]
  (let [uri (str cfg/public-uri "/api/rpc/query/" (name id))]
    (->> (http/send! {:method :get :uri uri :query params})
         (rx/mapcat handle-response))))

(defn send-mutation!
  [id params]
  (let [uri (str cfg/public-uri "/api/rpc/mutation/" (name id))]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(defn- dispatch
  [& args]
  (first args))

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

(defmethod mutation :login-with-google
  [id params]
  (let [uri (str cfg/public-uri "/api/oauth/google")]
    (->> (http/send! {:method :post :uri uri :query params})
         (rx/mapcat handle-response))))

(defmethod mutation :login-with-gitlab
  [id params]
  (let [uri (str cfg/public-uri "/api/oauth/gitlab")]
    (->> (http/send! {:method :post :uri uri :query params})
      (rx/mapcat handle-response))))

(defmethod mutation :login-with-github
  [id params]
  (let [uri (str cfg/public-uri "/api/oauth/github")]
    (->> (http/send! {:method :post :uri uri :query params})
         (rx/mapcat handle-response))))

(defmethod mutation :upload-file-media-object
  [id params]
  (let [form (js/FormData.)]
    (run! (fn [[key val]]
            (if (list? val)
              (.append form (name key) (first val) (second val))
              (.append form (name key) val)))
          (seq params))
    (send-mutation! id form)))

(defmethod mutation :send-feedback
  [id params]
  (let [uri (str cfg/public-uri "/api/feedback")]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(defmethod mutation :update-profile-photo
  [id params]
  (let [form (js/FormData.)]
    (run! (fn [[key val]]
            (.append form (name key) val))
          (seq params))
    (send-mutation! id form)))

(defmethod mutation :update-team-photo
  [id params]
  (let [form (js/FormData.)]
    (run! (fn [[key val]]
            (.append form (name key) val))
          (seq params))
    (send-mutation! id form)))

(def client-error? http/client-error?)
(def server-error? http/server-error?)
