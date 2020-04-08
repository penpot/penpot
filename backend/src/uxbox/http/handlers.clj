;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.handlers
  (:require
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.emails :as emails]
   [uxbox.http.session :as session]
   [uxbox.services.init]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.uuid :as uuid]
   [vertx.web :as vw]
   [vertx.eventbus :as ve]))

(def unauthorized-services
  #{:create-demo-profile
    :logout
    :profile
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
      (-> (sq/handle (with-meta data {:req req}))
          (p/then' (fn [result]
                     {:status 200
                      :body result})))
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
      (-> (sm/handle (with-meta data {:req req}))
          (p/then' (fn [result]
                     {:status 200 :body result})))
      {:status 403
       :body {:type :authentication
              :code :unauthorized}})))

(defn login-handler
  [req]
  (let [data (:body-params req)
        user-agent (get-in req [:headers "user-agent"])]
    (p/let [profile (sm/handle (assoc data ::sm/type :login))
            token   (session/create (:id profile) user-agent)]
      {:status 200
       :cookies {"auth-token" {:value token :path "/"}}
       :body profile})))

(defn logout-handler
  [req]
  (some-> (get-in req [:cookies "auth-token"])
          (uuid/from-string)
          (session/delete)
          (p/then' (fn [token]
                     {:status 204
                      :cookies {"auth-token" nil}
                      :body ""}))))

(defn echo-handler
  [req]
  (p/promise {:status 200
              :body {:params (:params req)
                     :cookies (:cookies req)
                     :headers (:headers req)}}))

