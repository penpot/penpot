;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.http.auth.google
  (:require
   [clojure.data.json :as json]
   [clojure.tools.logging :as log]
   [lambdaisland.uri :as uri]
   [uxbox.common.exceptions :as ex]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.services.tokens :as tokens]
   [uxbox.services.mutations :as sm]
   [uxbox.http.session :as session]
   [uxbox.util.http :as http]))

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
        (log/error "unexpected error on parsing response body from google access tooken request" e)
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
        (log/error "unexpected error on parsing response body from google access tooken request" e)
        nil))))

(defn auth
  [req]
  (let [token  (tokens/create! db/pool {:type :google-oauth})
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
        tdata (tokens/retrieve db/pool token)
        info  (some-> (get-in req [:params :code])
                      (get-access-token)
                      (get-user-info))]

    (when (not= :google-oauth (:type tdata))
      (ex/raise :type :validation
                :code ::tokens/invalid-token))

    (when-not info
      (ex/raise :type :authentication
                :code ::unable-to-authenticate-with-google))

    (let [profile (sm/handle {::sm/type :login-or-register
                              :email (:email info)
                              :fullname (:fullname info)})
          uagent  (get-in req [:headers "user-agent"])

          tdata   {:type :authentication
                   :profile profile}
          token   (tokens/create! db/pool tdata {:valid {:minutes 10}})

          uri     (-> (uri/uri (:public-uri cfg/config))
                      (assoc :path "/#/auth/verify-token")
                      (assoc :query (uri/map->query-string {:token token})))
          sid     (session/create (:id profile) uagent)]

      {:status 302
       :headers {"location" (str uri)}
       :cookies (session/cookies sid)
       :body ""})))

