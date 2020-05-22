;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.handlers
  (:require
   [uxbox.common.exceptions :as ex]
   [uxbox.common.uuid :as uuid]
   [uxbox.emails :as emails]
   [uxbox.http.session :as session]
   [uxbox.services.init]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]))

(def unauthorized-services
  #{:create-demo-profile
    :logout
    :profile
    :verify-profile-token
    :recover-profile
    :register-profile
    :request-profile-recovery
    :viewer-bundle
    :login})

(defn query-handler
  [req]
  (let [type (keyword (get-in req [:path-params :type]))
        data (merge (:params req)
                    {::sq/type type})
        data (cond-> data
               (:profile-id req) (assoc :profile-id (:profile-id req)))]
    (if (or (:profile-id req)
            (contains? unauthorized-services type))
      {:status 200
       :body (sq/handle (with-meta data {:req req}))}
      {:status 403
       :body {:type :authentication
              :code :unauthorized}})))

(defn mutation-handler
  [req]
  (let [type (keyword (get-in req [:path-params :type]))
        data (merge (:params req)
                    (:body-params req)
                    (:uploads req)
                    {::sm/type type})
        data (cond-> data
               (:profile-id req) (assoc :profile-id (:profile-id req)))]
    (if (or (:profile-id req)
            (contains? unauthorized-services type))
      (let [body (sm/handle (with-meta data {:req req}))]
        (if (= type :delete-profile)
          (do
            (some-> (get-in req [:cookies "auth-token" :value])
                    (uuid/uuid)
                    (session/delete))
            {:status 204
             :cookies {"auth-token" {:value "" :max-age -1}}
             :body ""})
          {:status 200
           :body body}))
      {:status 403
       :body {:type :authentication
              :code :unauthorized}})))

(defn login-handler
  [req]
  (let [data (:body-params req)
        user-agent (get-in req [:headers "user-agent"])]
    (let [profile (sm/handle (assoc data ::sm/type :login))
          token   (session/create (:id profile) user-agent)]
      {:status 200
       :cookies {"auth-token" {:value token :path "/"}}
       :body profile})))

(defn logout-handler
  [req]
  (some-> (get-in req [:cookies "auth-token" :value])
          (uuid/uuid)
          (session/delete))
  {:status 200
   :cookies {"auth-token" {:value "" :max-age -1}}
   :body ""})

(defn echo-handler
  [req]
  {:status 200
   :body {:params (:params req)
          :cookies (:cookies req)
          :headers (:headers req)}})

