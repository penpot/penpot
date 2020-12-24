;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.auth.google
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.http.session :as session]
   [app.util.http :as http]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [lambdaisland.uri :as uri]))

(def base-goauth-uri "https://accounts.google.com/o/oauth2/v2/auth")

(def scope
  (str "email profile "
       "https://www.googleapis.com/auth/userinfo.email "
       "https://www.googleapis.com/auth/userinfo.profile "
       "openid"))

(defn- build-redirect-url
  [cfg]
  (let [public (uri/uri (:public-uri cfg))]
    (str (assoc public :path "/api/oauth/google/callback"))))

(defn- get-access-token
  [cfg code]
  (let [params {:code code
                :client_id (:client-id cfg)
                :client_secret (:client-secret cfg)
                :redirect_uri (build-redirect-url cfg)
                :grant_type "authorization_code"}
        req    {:method :post
                :headers {"content-type" "application/x-www-form-urlencoded"}
                :uri "https://oauth2.googleapis.com/token"
                :body (uri/map->query-string params)}
        res    (http/send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-google
                :context {:status (:status res)
                          :body (:body res)}))

    (try
      (let [data (json/read-str (:body res))]
        (get data "access_token"))
      (catch Throwable e
        (log/error "unexpected error on parsing response body from google access token request" e)
        nil))))

(defn- get-user-info
  [token]
  (let [req {:uri "https://openidconnect.googleapis.com/v1/userinfo"
             :headers {"Authorization" (str "Bearer " token)}
             :method :get}
        res (http/send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-google
                :context {:status (:status res)
                          :body (:body res)}))

    (try
      (let [data (json/read-str (:body res))]
        ;; (clojure.pprint/pprint data)
        {:email (get data "email")
         :fullname (get data "name")})
      (catch Throwable e
        (log/error "unexpected error on parsing response body from google access token request" e)
        nil))))

(defn- auth
  [{:keys [tokens] :as cfg} _req]
  (let [token  (tokens :generate {:iss :google-oauth :exp (dt/in-future "15m")})
        params {:scope scope
                :access_type "offline"
                :include_granted_scopes true
                :state token
                :response_type "code"
                :redirect_uri (build-redirect-url)
                :client_id (:client-id cfg)}
        query  (uri/map->query-string params)
        uri    (-> (uri/uri base-goauth-uri)
                   (assoc :query query))]
    {:status 200
     :body {:redirect-uri (str uri)}}))

(defn- callback
  [{:keys [tokens rpc session] :as cfg} request]
  (let [token (get-in request [:params :state])
        _     (tokens :verify {:token token :iss :google-oauth})
        info  (some->> (get-in request [:params :code])
                       (get-access-token cfg)
                       (get-user-info))]

    (when-not info
      (ex/raise :type :authentication
                :code :unable-to-authenticate-with-google))

    (let [method-fn (get-in rpc [:method :mutations :login-or-register])
          profile   (method-fn {:email (:email info)
                                :fullname (:fullname info)})
          uagent    (get-in request [:headers "user-agent"])
          token     (tokens :generate {:iss :auth
                                       :exp (dt/in-future "15m")
                                       :profile-id (:id profile)})

          uri       (-> (uri/uri (:public-uri cfg))
                        (assoc :path "/#/auth/verify-token")
                        (assoc :query (uri/map->query-string {:token token})))
          sid       (session/create! session {:profile-id (:id profile)
                                              :user-agent uagent})]
      {:status 302
       :headers {"location" (str uri)}
       :cookies (session/cookies session {:value sid})
       :body ""})))

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
  [req]
  (ex/raise :type :not-found))

(defmethod ig/init-key :app.http.auth/google
  [_ cfg]
  (if (and (:client-id cfg)
           (:client-secret cfg))
    {:auth-handler #(auth cfg %)
     :callback-handler #(callback cfg %)}
    {:auth-handler default-handler
     :callback-handler default-handler}))
