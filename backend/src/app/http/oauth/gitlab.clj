;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.oauth.gitlab
  (:require
   [app.common.data :as d]
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

(def scope "read_user")

(defn- build-redirect-url
  [cfg]
  (let [public (u/uri (:public-uri cfg))]
    (str (assoc public :path "/api/oauth/gitlab/callback"))))

(defn- build-oauth-uri
  [cfg]
  (let [base-uri (u/uri (:base-uri cfg))]
    (assoc base-uri :path "/oauth/authorize")))

(defn- build-token-url
  [cfg]
  (let [base-uri (u/uri (:base-uri cfg))]
    (str (assoc base-uri :path "/oauth/token"))))

(defn- build-user-info-url
  [cfg]
  (let [base-uri (u/uri (:base-uri cfg))]
    (str (assoc base-uri :path "/api/v4/user"))))

(defn- get-access-token
  [cfg code]
  (try
    (let [params {:client_id (:client-id cfg)
                  :client_secret (:client-secret cfg)
                  :code code
                  :grant_type "authorization_code"
                  :redirect_uri (build-redirect-url cfg)}
          req    {:method :post
                  :headers {"content-type" "application/x-www-form-urlencoded"}
                  :uri (build-token-url cfg)
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
  [cfg token]
  (try
    (let [req {:uri (build-user-info-url cfg)
               :headers {"Authorization" (str "Bearer " token)}
               :timeout 6000
               :method :get}
          res (http/send! req)]

      (when (= 200 (:status res))
        (let [data (json/read-str (:body res))]
          {:email (get data "email")
           :backend "gitlab"
           :fullname (get data "name")})))

    (catch Exception e
      (l/error :hint "unexpected exception on get-user-info"
               :cause e)
      nil)))


(defn- retrieve-info
  [{:keys [tokens] :as cfg} request]
  (let [token (get-in request [:params :state])
        state (tokens :verify {:token token :iss :gitlab-oauth})
        info  (some->> (get-in request [:params :code])
                       (get-access-token cfg)
                       (get-user-info cfg))]
    (when-not info
      (ex/raise :type :internal
                :code :unable-to-auth))

    (cond-> info
      (some? (:invitation-token state))
      (assoc :invitation-token (:invitation-token state)))))


(defn- auth-handler
  [{:keys [tokens] :as cfg} request]
  (let [invitation (get-in request [:params :invitation-token])
        state      (tokens :generate
                           {:iss :gitlab-oauth
                            :invitation-token invitation
                            :exp (dt/in-future "15m")})

        params     {:client_id (:client-id cfg)
                    :redirect_uri (build-redirect-url cfg)
                    :response_type "code"
                    :state state
                    :scope scope}
        query      (u/map->query-string params)
        uri        (-> (build-oauth-uri cfg)
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

(s/def ::client-id ::us/not-empty-string)
(s/def ::client-secret ::us/not-empty-string)
(s/def ::base-uri ::us/not-empty-string)
(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec :app.http.oauth/gitlab [_]
  (s/keys :req-un [::public-uri
                   ::session
                   ::tokens]
          :opt-un [::base-uri
                   ::client-id
                   ::client-secret]))

(defmethod ig/prep-key :app.http.oauth/gitlab
  [_ cfg]
  (d/merge {:base-uri "https://gitlab.com"}
           (d/without-nils cfg)))

(defn- default-handler
  [_]
  (ex/raise :type :not-found))

(defmethod ig/init-key :app.http.oauth/gitlab
  [_ cfg]
  (if (and (:client-id cfg)
           (:client-secret cfg))
    {:handler #(auth-handler cfg %)
     :callback-handler #(callback-handler cfg %)}
    {:handler default-handler
     :callback-handler default-handler}))
