;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.auth.gitlab
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.http.session :as session]
   [app.services.mutations :as sm]
   [app.services.tokens :as tokens]
   [app.util.http :as http]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.tools.logging :as log]
   [lambdaisland.uri :as uri]))


(def default-base-gitlab-uri "https://gitlab.com")

(def scope "read_user")

(defn- build-redirect-url
  []
  (let [public (uri/uri (:public-uri cfg/config))]
    (str (assoc public :path "/api/oauth/gitlab/callback"))))


(defn- build-oauth-uri
  []
  (let [base-uri (uri/uri (:gitlab-base-uri cfg/config default-base-gitlab-uri))]
    (assoc base-uri :path "/oauth/authorize")))


(defn- build-token-url
  []
  (let [base-uri (uri/uri (:gitlab-base-uri cfg/config default-base-gitlab-uri))]
    (str (assoc base-uri :path "/oauth/token"))))


(defn- build-user-info-url
  []
  (let [base-uri (uri/uri (:gitlab-base-uri cfg/config default-base-gitlab-uri))]
    (str (assoc base-uri :path "/api/v4/user"))))


(defn- get-access-token
  [code]
  (let [params {:client_id (:gitlab-client-id cfg/config)
                :client_secret (:gitlab-client-secret cfg/config)
                :code code
                :grant_type "authorization_code"
                :redirect_uri (build-redirect-url)}
        req    {:method :post
                :headers {"content-type" "application/x-www-form-urlencoded"}
                :uri (build-token-url)
                :body (uri/map->query-string params)}
        res    (http/send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-gitlab
                :context {:status (:status res)
                          :body (:body res)}))

    (try
      (let [data (json/read-str (:body res))]
        (get data "access_token"))
      (catch Throwable e
        (log/error "unexpected error on parsing response body from gitlab access token request" e)
        nil))))


(defn- get-user-info
  [token]
  (let [req {:uri (build-user-info-url)
             :headers {"Authorization" (str "Bearer " token)}
             :method :get}
        res (http/send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-gitlab
                :context {:status (:status res)
                          :body (:body res)}))

    (try
      (let [data (json/read-str (:body res))]
        ;; (clojure.pprint/pprint data)
        {:email (get data "email")
         :fullname (get data "name")})
      (catch Throwable e
        (log/error "unexpected error on parsing response body from gitlab access token request" e)
        nil))))

(defn auth
  [req]
  (let [token  (tokens/generate
                {:iss :gitlab-oauth
                 :exp (dt/in-future "15m")})

        params {:client_id (:gitlab-client-id cfg/config)
                :redirect_uri (build-redirect-url)
                :response_type "code"
                :state token
                :scope scope}
        query  (uri/map->query-string params)
        uri    (-> (build-oauth-uri)
                   (assoc :query query))]
    {:status 200
     :body {:redirect-uri (str uri)}}))

(defn callback
  [req]
  (let [token (get-in req [:params :state])
        tdata (tokens/verify token {:iss :gitlab-oauth})
        info  (some-> (get-in req [:params :code])
                      (get-access-token)
                      (get-user-info))]

    (when-not info
      (ex/raise :type :authentication
                :code :unable-to-authenticate-with-gitlab))

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
