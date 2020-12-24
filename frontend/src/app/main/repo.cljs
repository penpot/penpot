;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.repo
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [app.config :as cfg]
   [app.util.http-api :as http]))

(defn- handle-response
  [response]
  (cond
    (http/success? response)
    (rx/of (:body response))

    (= (:status response) 400)
    (rx/throw (:body response))

    (= (:status response) 401)
    (rx/throw {:type :authentication
               :code :not-authenticated})

    (= (:status response) 403)
    (rx/throw {:type :authorization
               :code :not-authorized})

    (= (:status response) 404)
    (rx/throw (:body response))

    (= 0 (:status response))
    (rx/throw {:type :offline})

    :else
    (rx/throw (merge {:type :server-error
                      :status (:status response)}
                     (:body response)))))



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
    (->> (http/send! {:method :post :uri uri})
         (rx/mapcat handle-response))))

(defmethod mutation :login-with-gitlab
  [id params]
  (let [uri (str cfg/public-uri "/api/oauth/gitlab")]
    (->> (http/send! {:method :post :uri uri})
      (rx/mapcat handle-response))))

(defmethod mutation :upload-media-object
  [id params]
  (let [form (js/FormData.)]
    (run! (fn [[key val]]
            (if (list? val)
              (.append form (name key) (first val) (second val))
              (.append form (name key) val)))
          (seq params))
    (send-mutation! id form)))

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

(defmethod mutation :login
  [id params]
  (let [uri (str cfg/public-uri "/api/login")]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(defmethod mutation :logout
  [id params]
  (let [uri (str cfg/public-uri "/api/logout")]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(defmethod mutation :login-with-ldap
  [id params]
  (let [uri (str cfg/public-uri "/api/login-ldap")]
    (->> (http/send! {:method :post :uri uri :body params})
         (rx/mapcat handle-response))))

(def client-error? http/client-error?)
(def server-error? http/server-error?)
