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
   [app.config :as cfg]
   [app.http.session :as session]
   [app.services.mutations :as sm]
   [app.services.tokens :as tokens]
   [app.util.http :as http]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.tools.logging :as log]
   [lambdaisland.uri :as uri]))

(def base-goauth-uri "https://accounts.google.com/o/oauth2/v2/auth")

(def scope
  (str "email profile "
       "https://www.googleapis.com/auth/userinfo.email "
       "https://www.googleapis.com/auth/userinfo.profile "
       "openid"))

(defn- build-redirect-url
  []
  (let [public (uri/uri (:public-uri cfg/config))]
    (str (assoc public :path "/api/oauth/google/callback"))))

(defn- get-access-token
  [code]
  (let [params {:code code
                :client_id (:google-client-id cfg/config)
                :client_secret (:google-client-secret cfg/config)
                :redirect_uri (build-redirect-url)
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

(defn auth
  [_req]
  (let [token  (tokens/generate {:iss :google-oauth :exp (dt/in-future "15m")})
        params {:scope scope
                :access_type "offline"
                :include_granted_scopes true
                :state token
                :response_type "code"
                :redirect_uri (build-redirect-url)
                :client_id (:google-client-id cfg/config)}
        query  (uri/map->query-string params)
        uri    (-> (uri/uri base-goauth-uri)
                   (assoc :query query))]
    {:status 200
     :body {:redirect-uri (str uri)}}))


(defn callback
  [req]
  (let [token (get-in req [:params :state])
        _     (tokens/verify token {:iss :google-oauth})
        info  (some-> (get-in req [:params :code])
                      (get-access-token)
                      (get-user-info))]

    (when-not info
      (ex/raise :type :authentication
                :code :unable-to-authenticate-with-google))

    (let [profile (sm/handle {::sm/type :login-or-register
                              :email (:email info)
                              :fullname (:fullname info)})
          uagent  (get-in req [:headers "user-agent"])

          token   (tokens/generate
                   {:iss :auth
                    :exp (dt/in-future "15m")
                    :profile-id (:id profile)})
          uri     (-> (uri/uri (:public-uri cfg/config))
                      (assoc :path "/#/auth/verify-token")
                      (assoc :query (uri/map->query-string {:token token})))
          sid     (session/create (:id profile) uagent)]

      {:status 302
       :headers {"location" (str uri)}
       :cookies (session/cookies sid)
       :body ""})))
