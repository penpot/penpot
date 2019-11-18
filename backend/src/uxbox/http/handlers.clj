;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.handlers
  (:require
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [uxbox.http.errors :as errors]
   [uxbox.http.session :as session]
   [uxbox.services.core :as sv]
   [uxbox.util.uuid :as uuid]))

(defn query-handler
  [req]
  (let [type (get-in req [:path-params :type])
        data (merge (:params req)
                    {::sv/type (keyword type)
                     :user (:user req)})]
    (-> (sv/query (with-meta data {:req req}))
        (p/handle (fn [result error]
                    (if error
                      (errors/handle error)
                      {:status 200
                       :body result}))))))

(defn mutation-handler
  [req]
  (let [type (get-in req [:path-params :type])
        data (merge (:params req)
                    (:body-params req)
                    (:uploads req)
                    {::sv/type (keyword type)
                     :user (:user req)})]
    (-> (sv/mutation (with-meta data {:req req}))
        (p/handle (fn [result error]
                    (if error
                      (errors/handle error)
                      {:status 200 :body result}))))))

(defn login-handler
  [req]
  (let [data (:body-params req)
        user-agent (get-in req [:headers "user-agent"])]
    (-> (sv/mutation (assoc data ::sv/type :login))
        (p/then #(session/create % user-agent))
        (p/then (fn [token]
                  {:status 204
                   :cookies {"auth-token" {:value token}}
                   :body ""}))
        (p/catch errors/handle))))

(defn logout-handler
  [req]
  (let [token (get-in req [:cookies "auth-token"])
        token (uuid/from-string token)]
    (-> (session/delete token)
        (p/then (fn [token]
                  {:status 204
                   :cookies {"auth-token" {:value nil}}
                   :body ""}))
        (p/catch errors/handle))))

(defn register-handler
  [req]
  (let [data (merge (:body-params req)
                    {::sv/type :register-profile})
        user-agent (get-in req [:headers "user-agent"])]
    (-> (sv/mutation (with-meta data {:req req}))
        (p/then (fn [{:keys [id] :as user}]
                  (session/create id user-agent)))
        (p/then' (fn [token]
                  {:status 204
                   :cookies {"auth-token" {:value token}}
                   :body ""}))
        (p/catch' errors/handle))))

(defn echo-handler
  [req]
  {:status 200
   :body {:params (:params req)
          :cookies (:cookies req)
          :headers (:headers req)}})

