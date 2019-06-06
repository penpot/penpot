;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.auth
  (:require [clojure.spec.alpha :as s]
            [struct.core :as st]
            [uxbox.services :as sv]
            [uxbox.util.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.util.uuid :as uuid]))

(defn login
  {:description "User login endpoint"
   :parameters {:body {:username [st/required st/string]
                       :password [st/required st/string]
                       :scope [st/required st/string]}}}
  [ctx]
  (let [data (get-in ctx [:parameters :body])
        user @(sv/novelty (assoc data :type :login))]
    (-> (http/no-content)
        (assoc :session {:user-id (get user :id)}))))

(defn authorization-middleware
  [handler]
  (fn
    ([request]
     (if-let [identity (get-in request [:session :user-id])]
       (handler (assoc request :identity identity :user identity))
       (http/forbidden nil)))
    ([request respond raise]
     (if-let [identity (get-in request [:session :user-id])]
       (handler (assoc request :identity identity :user identity) respond raise)
       (respond (http/forbidden nil))))))
