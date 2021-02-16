;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.http.auth.google
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.http :as http]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]))

(def base-goauth-uri "https://accounts.google.com/o/oauth2/v2/auth")

(def scope
  (str "email profile "
       "https://www.googleapis.com/auth/userinfo.email "
       "https://www.googleapis.com/auth/userinfo.profile "
       "openid"))

(defn- build-redirect-url
  [cfg]
  (let [public (u/uri (:public-uri cfg))]
    (str (assoc public :path "/api/oauth/google/callback"))))

(defn- get-access-token
  [cfg code]
  (try
    (let [params {:code code
                  :client_id (:client-id cfg)
                  :client_secret (:client-secret cfg)
                  :redirect_uri (build-redirect-url cfg)
                  :grant_type "authorization_code"}
          req    {:method :post
                  :headers {"content-type" "application/x-www-form-urlencoded"}
                  :uri "https://oauth2.googleapis.com/token"
                  :timeout 6000
                  :body (u/map->query-string params)}
          res    (http/send! req)]

      (when (= 200 (:status res))
        (-> (json/read-str (:body res))
            (get "access_token"))))

    (catch Exception e
      (log/error e "unexpected error on get-access-token")
      nil)))

(defn- get-user-info
  [token]
  (try
    (let [req {:uri "https://openidconnect.googleapis.com/v1/userinfo"
               :headers {"Authorization" (str "Bearer " token)}
               :method :get}
          res (http/send! req)]
      (when (= 200 (:status res))
        (let [data (json/read-str (:body res))]
          {:email (get data "email")
           :fullname (get data "name")})))
    (catch Exception e
      (log/error e "unexpected exception on get-user-info")
      nil)))

(defn- retrieve-info
  [{:keys [tokens] :as cfg} request]
  (let [token (get-in request [:params :state])
        state (tokens :verify {:token token :iss :google-oauth})
        info  (some->> (get-in request [:params :code])
                       (get-access-token cfg)
                       (get-user-info))]
    (when-not info
      (ex/raise :type :internal
                :code :unable-to-auth))

    (cond-> info
      (some? (:invitation-token state))
      (assoc :invitation-token (:invitation-token state)))))

(defn- register-profile
  [{:keys [rpc] :as cfg} info]
  (let [method-fn (get-in rpc [:methods :mutation :login-or-register])
        profile   (method-fn {:email (:email info)
                              :backend "google"
                              :fullname (:fullname info)})]
    (cond-> profile
      (some? (:invitation-token info))
      (assoc :invitation-token (:invitation-token info)))))

(defn- generate-redirect-uri
  [{:keys [tokens] :as cfg} profile]
  (let [token (or (:invitation-token profile)
                  (tokens :generate {:iss :auth
                                     :exp (dt/in-future "15m")
                                     :profile-id (:id profile)}))]
    (-> (u/uri (:public-uri cfg))
        (assoc :path "/#/auth/verify-token")
        (assoc :query (u/map->query-string {:token token})))))

(defn- generate-error-redirect-uri
  [cfg]
  (-> (u/uri (:public-uri cfg))
      (assoc :path "/#/auth/login")
      (assoc :query (u/map->query-string {:error "unable-to-auth"}))))

(defn- redirect-response
  [uri]
  {:status 302
   :headers {"location" (str uri)}
   :body ""})

(defn- auth-handler
  [{:keys [tokens] :as cfg} request]
  (let [invitation (get-in request [:params :invitation-token])
        state      (tokens :generate
                           {:iss :google-oauth
                            :invitation-token invitation
                            :exp (dt/in-future "15m")})
        params     {:scope scope
                    :access_type "offline"
                    :include_granted_scopes true
                    :state state
                    :response_type "code"
                    :redirect_uri (build-redirect-url cfg)
                    :client_id (:client-id cfg)}
        query      (u/map->query-string params)
        uri        (-> (u/uri base-goauth-uri)
                       (assoc :query query))]

    {:status 200
     :body {:redirect-uri (str uri)}}))

(defn- callback-handler
  [{:keys [session] :as cfg} request]
  (try
    (let [info    (retrieve-info cfg request)
          profile (register-profile cfg info)
          uri     (generate-redirect-uri cfg profile)
          sxf     ((:create session) (:id profile))]
      (sxf request (redirect-response uri)))
    (catch Exception _e
      (-> (generate-error-redirect-uri cfg)
          (redirect-response)))))

(s/def ::client-id ::us/not-empty-string)
(s/def ::client-secret ::us/not-empty-string)
(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec :app.http.auth/google [_]
  (s/keys :req-un [::public-uri
                   ::session
                   ::tokens]
          :opt-un [::client-id
                   ::client-secret]))

(defn- default-handler
  [_]
  (ex/raise :type :not-found))

(defmethod ig/init-key :app.http.auth/google
  [_ cfg]
  (if (and (:client-id cfg)
           (:client-secret cfg))
    {:auth-handler #(auth-handler cfg %)
     :callback-handler #(callback-handler cfg %)}
    {:auth-handler default-handler
     :callback-handler default-handler}))
