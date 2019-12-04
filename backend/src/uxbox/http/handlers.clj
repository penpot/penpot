;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.handlers
  (:require
   [promesa.core :as p]
   [uxbox.emails :as emails]
   [uxbox.http.session :as session]
   [uxbox.services.init]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.uuid :as uuid]))

(defn query-handler
  [req]
  (let [type (get-in req [:path-params :type])
        data (merge (:params req)
                    {::sq/type (keyword type)
                     :user (:user req)})]
    (-> (sq/handle (with-meta data {:req req}))
        (p/then' (fn [result]
                  {:status 200
                   :body result})))))

(defn mutation-handler
  [req]
  (let [type (get-in req [:path-params :type])
        data (merge (:params req)
                    (:body-params req)
                    (:uploads req)
                    {::sm/type (keyword type)
                     :user (:user req)})]
    (-> (sm/handle (with-meta data {:req req}))
        (p/then' (fn [result]
                   {:status 200 :body result})))))

(defn login-handler
  [req]
  (let [data (:body-params req)
        user-agent (get-in req [:headers "user-agent"])]
    (-> (sm/handle (assoc data ::sm/type :login))
        (p/then #(session/create (:id %) user-agent))
        (p/then' (fn [token]
                   {:status 204
                    :cookies {"auth-token" {:value token}}
                    :body ""})))))

(defn logout-handler
  [req]
  (let [token (get-in req [:cookies "auth-token"])
        token (uuid/from-string token)]
    (-> (session/delete token)
        (p/then' (fn [token]
                   {:status 204
                    :cookies {"auth-token" {:value nil}}
                    :body ""})))))

(defn register-handler
  [req]
  (let [data (merge (:body-params req)
                    {::sm/type :register-profile})
        user-agent (get-in req [:headers "user-agent"])]
    (-> (sm/handle (with-meta data {:req req}))
        (p/then (fn [{:keys [id] :as user}]
                  (session/create id user-agent)))
        (p/then' (fn [token]
                  {:status 204
                   :cookies {"auth-token" {:value token}}
                   :body ""})))))

(defn echo-handler
  [req]
  {:status 200
   :body {:params (:params req)
          :cookies (:cookies req)
          :headers (:headers req)}})
