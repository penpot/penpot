;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.http.auth.github
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.http.session :as session]
   [app.util.http :as http]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]))

(def base-github-uri
  (u/uri "https://github.com"))

(def base-api-github-uri
  (u/uri "https://api.github.com"))

(def authorize-uri
  (assoc base-github-uri :path "/login/oauth/authorize"))

(def token-url
  (assoc base-github-uri :path "/login/oauth/access_token"))

(def user-info-url
  (assoc base-api-github-uri :path "/user"))

(def scope "user:email")


(defn- build-redirect-url
  [cfg]
  (let [public (u/uri (:public-uri cfg))]
    (str (assoc public :path "/api/oauth/github/callback"))))

(defn- get-access-token
  [cfg code state]
  (let [params {:client_id (:client-id cfg)
                :client_secret (:client-secret cfg)
                :code code
                :state state
                :redirect_uri (build-redirect-url cfg)}
        req    {:method :post
                :headers {"content-type" "application/x-www-form-urlencoded"
                          "accept" "application/json"}
                :uri  (str token-url)
                :body (u/map->query-string params)}
        res    (http/send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-github
                :context {:status (:status res)
                          :body (:body res)}))
    (try
      (let [data (json/read-str (:body res))]
        (get data "access_token"))
      (catch Throwable e
        (log/error "unexpected error on parsing response body from github access token request" e)
        nil))))

(defn- get-user-info
  [token]
  (let [req {:uri (str user-info-url)
             :headers {"authorization" (str "token " token)}
             :method :get}
        res (http/send! req)]

    (when (not= 200 (:status res))
      (ex/raise :type :internal
                :code :invalid-response-from-github
                :context {:status (:status res)
                          :body (:body res)}))

    (try
      (let [data (json/read-str (:body res))]
        {:email (get data "email")
         :fullname (get data "name")})
      (catch Throwable e
        (log/error "unexpected error on parsing response body from github access token request" e)
        nil))))

(defn auth
  [{:keys [tokens] :as cfg} request]
  (let [state  (tokens :generate
                       {:iss :github-oauth
                        :exp (dt/in-future "15m")})

        params {:client_id (:client-id cfg/config)
                :redirect_uri (build-redirect-url)
                :state state
                :scope scope}
        query (u/map->query-string params)
        uri   (-> authorize-uri
                  (assoc :query query))]
    {:status 200
     :body {:redirect-uri (str uri)}}))

(defn callback
  [{:keys [tokens rpc session] :as cfg} request]
  (let [state (get-in request [:params :state])
        _     (tokens :verify {:token state :iss :github-oauth})
        info  (some-> (get-in request [:params :code])
                      (get-access-token state)
                      (get-user-info))]

    (when-not info
      (ex/raise :type :authentication
                :code :unable-to-authenticate-with-github))

    (let [method-fn (get-in rpc [:method :mutations :login-or-register])
          profile   (method-fn {:email (:email info)
                                :fullname (:fullname info)})
          uagent    (get-in request [:headers "user-agent"])

          token     (tokens :generate
                            {:iss :auth
                             :exp (dt/in-future "15m")
                             :profile-id (:id profile)})

          uri       (-> (u/uri (:public-uri cfg/config))
                        (assoc :path "/#/auth/verify-token")
                        (assoc :query (u/map->query-string {:token token})))

          sid     (session/create! session {:profile-id (:id profile)
                                            :user-agent uagent})]

      {:status 302
       :headers {"location" (str uri)}
       :cookies (session/cookies session/cookies {:value sid})
       :body ""})))

;; --- ENTRY POINT

(s/def ::client-id ::us/not-empty-string)
(s/def ::client-secret ::us/not-empty-string)
(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec :app.http.auth/github [_]
  (s/keys :req-un [::public-uri
                   ::session
                   ::tokens]
          :opt-un [::client-id
                   ::client-secret]))

(defn- default-handler
  [req]
  (ex/raise :type :not-found))

(defmethod ig/init-key :app.http.auth/github
  [_ cfg]
  (if (and (:client-id cfg)
           (:client-secret cfg))
    {:auth-handler #(auth cfg %)
     :callback-handler #(callback cfg %)}
    {:auth-handler default-handler
     :callback-handler default-handler}))

