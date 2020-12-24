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
   [app.common.spec :as us]
   [app.common.data :as d]
   [app.config :as cfg]
   [app.http.session :as session]
   [app.util.http :as http]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [lambdaisland.uri :as uri]))

(def scope "read_user")

(defn- build-redirect-url
  [cfg]
  (let [public (uri/uri (:public-uri cfg))]
    (str (assoc public :path "/api/oauth/gitlab/callback"))))


(defn- build-oauth-uri
  [cfg]
  (let [base-uri (uri/uri (:base-uri cfg))]
    (assoc base-uri :path "/oauth/authorize")))


(defn- build-token-url
  [cfg]
  (let [base-uri (uri/uri (:base-uri cfg))]
    (str (assoc base-uri :path "/oauth/token"))))


(defn- build-user-info-url
  [cfg]
  (let [base-uri (uri/uri (:base-uri cfg))]
    (str (assoc base-uri :path "/api/v4/user"))))

(defn- get-access-token
  [cfg code]
  (let [params {:client_id (:client-id cfg)
                :client_secret (:client-secret cfg)
                :code code
                :grant_type "authorization_code"
                :redirect_uri (build-redirect-url cfg)}
        req    {:method :post
                :headers {"content-type" "application/x-www-form-urlencoded"}
                :uri (build-token-url cfg)
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
  [{:keys [tokens] :as cfg} _request]
  (let [token  (tokens :generate {:iss :gitlab-oauth
                                  :exp (dt/in-future "15m")})

        params {:client_id (:client-id cfg)
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
  [{:keys [tokens rpc session] :as cfg} request]
  (let [token (get-in request [:params :state])
        _     (tokens :verify {:token token :iss :gitlab-oauth})
        info  (some->> (get-in request [:params :code])
                       (get-access-token cfg)
                       (get-user-info))]

    (when-not info
      (ex/raise :type :authentication
                :code :unable-to-authenticate-with-gitlab))

    (let [method-fn (get-in rpc [:methods :mutation :login-or-register])
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
(s/def ::base-uri ::us/not-empty-string)
(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec :app.http.auth/gitlab [_]
  (s/keys :req-un [::public-uri
                   ::session
                   ::tokens]
          :opt-un [::base-uri
                   ::client-id
                   ::client-secret]))


(defmethod ig/prep-key :app.http.auth/gitlab
  [_ cfg]
  (d/merge {:base-uri "https://gitlab.com"}
           (d/without-nils cfg)))

(defn- default-handler
  [req]
  (ex/raise :type :not-found))

(defmethod ig/init-key :app.http.auth/gitlab
  [_ cfg]
  (if (and (:client-id cfg)
           (:client-secret cfg))
    {:auth-handler #(auth cfg %)
     :callback-handler #(callback cfg %)}
    {:auth-handler default-handler
     :callback-handler default-handler}))
