;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.auth
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [struct.core :as st]
            [uxbox.services :as sv]
            [uxbox.http.response :as rsp]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]))

(defn login
  {:description "User login endpoint"
   :parameters {:body {:username [st/required st/string]
                       :password [st/required st/string]
                       :scope [st/required st/string]}}}
  [ctx]
  (let [data (get-in ctx [:parameters :body])]
    (->> (sv/novelty (assoc data :type :login))
         (p/map (fn [{:keys [id] :as user}]
                  (-> (rsp/no-content)
                      (assoc :session {:user-id id})))))))

(defn register
  {:parameters {:body {:username [st/required st/string]
                       :email [st/required st/email]
                       :password [st/required st/string]
                       :fullname [st/required st/string]}}}
  [{:keys [parameters]}]
  (let [data (get parameters :body)
        message (assoc data :type :register-profile)]
    (->> (sv/novelty message)
         (p/map rsp/ok))))

(defn request-recovery
  {:parameters {:body {:username [st/required st/string]}}}
  [{:keys [parameters]}]
  (let [data (get parameters :body)
        message (assoc data :type :request-profile-password-recovery)]
    (->> (sv/novelty message)
         (p/map (constantly (rsp/no-content))))))

(defn recover-password
  {:parameters {:body {:token [st/required st/string]
                       :password [st/required st/string]}}}
  [{:keys [parameters]}]
  (let [data (get parameters :body)
        message (assoc data :type :recover-profile-password)]
    (->> (sv/novelty message)
         (p/map (constantly (rsp/no-content))))))

(defn validate-recovery-token
  {:parameters {:path {:token [st/required st/string]}}}
  [{:keys [parameters]}]
  (let [message {:type :validate-profile-password-recovery-token
                 :token (get-in parameters [:path :token])}]
    (->> (sv/query message)
         (p/map (fn [v]
                  (if v
                    (rsp/no-content)
                    (rsp/not-found "")))))))
