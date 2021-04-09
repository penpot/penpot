;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.oauth.github
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.http.oauth.google :as gg]
   [app.util.http :as http]
   [app.util.logging :as l]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
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
  [cfg state code]
  (try
    (let [params {:client_id (:client-id cfg)
                  :client_secret (:client-secret cfg)
                  :code code
                  :state state
                  :redirect_uri (build-redirect-url cfg)}
          req    {:method :post
                  :headers {"content-type" "application/x-www-form-urlencoded"
                            "accept" "application/json"}
                  :uri  (str token-url)
                  :timeout 6000
                  :body (u/map->query-string params)}
          res    (http/send! req)]

      (when (= 200 (:status res))
        (-> (json/read-str (:body res))
            (get "access_token"))))

    (catch Exception e
      (l/error :hint "unexpected error on get-access-token"
               :cause e)
      nil)))

(defn- get-user-info
  [_ token]
  (try
    (let [req {:uri (str user-info-url)
               :headers {"authorization" (str "token " token)}
               :timeout 6000
               :method :get}
          res (http/send! req)]
      (when (= 200 (:status res))
        (let [data (json/read-str (:body res))]
          {:email (get data "email")
           :backend "github"
           :fullname (get data "name")})))
    (catch Exception e
      (l/error :hint "unexpected exception on get-user-info"
               :cause e)
      nil)))

(defn- retrieve-info
  [{:keys [tokens] :as cfg} request]
  (let [token (get-in request [:params :state])
        state (tokens :verify {:token token :iss :github-oauth})
        info  (some->> (get-in request [:params :code])
                       (get-access-token cfg state)
                       (get-user-info cfg))]
    (when-not info
      (ex/raise :type :internal
                :code :unable-to-auth))

    (cond-> info
      (some? (:invitation-token state))
      (assoc :invitation-token (:invitation-token state)))))

(defn auth-handler
  [{:keys [tokens] :as cfg} request]
  (let [invitation (get-in request [:params :invitation-token])
        state      (tokens :generate {:iss :github-oauth
                                      :invitation-token invitation
                                      :exp (dt/in-future "15m")})
        params     {:client_id (:client-id cfg)
                    :redirect_uri (build-redirect-url cfg)
                    :state state
                    :scope scope}
        query      (u/map->query-string params)
        uri        (-> authorize-uri
                       (assoc :query query))]
    {:status 200
     :body {:redirect-uri (str uri)}}))

(defn- callback-handler
  [{:keys [session] :as cfg} request]
  (try
    (let [info    (retrieve-info cfg request)
          profile (gg/register-profile cfg info)
          uri     (gg/generate-redirect-uri cfg profile)
          sxf     ((:create session) (:id profile))]
      (->> (gg/redirect-response uri)
           (sxf request)))
    (catch Exception _e
      (-> (gg/generate-error-redirect-uri cfg)
          (gg/redirect-response)))))


;; --- ENTRY POINT

(s/def ::client-id ::us/not-empty-string)
(s/def ::client-secret ::us/not-empty-string)
(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec :app.http.oauth/github [_]
  (s/keys :req-un [::public-uri
                   ::session
                   ::tokens]
          :opt-un [::client-id
                   ::client-secret]))

(defn- default-handler
  [_]
  (ex/raise :type :not-found))

(defmethod ig/init-key :app.http.oauth/github
  [_ cfg]
  (if (and (:client-id cfg)
           (:client-secret cfg))
    {:handler #(auth-handler cfg %)
     :callback-handler #(callback-handler cfg %)}
    {:handler default-handler
     :callback-handler default-handler}))

