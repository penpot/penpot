;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.auth
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.config :refer (url)]
            [uxbox.main.repo.impl :refer (request send!)]))

(defmethod request :fetch/profile
  [type _]
  (let [url (str url "/profile/me")]
    (send! {:method :get :url url})))

(defmethod request :fetch/token
  [type data]
  (let [url (str url "/auth/token")]
    (send! {:url url
                :method :post
                :auth false
                :body data})))

(defmethod request :update/profile
  [type data]
  (let [params {:url (str url "/profile/me")
                :method :put
                :body data}]
    (send! params)))

(defmethod request :auth/register
  [_ data]
  (let [params {:url (str url "/auth/register")
                :method :post
                :body data}]
    (send! params)))

(defmethod request :auth/recovery-request
  [_ data]
  (let [params {:url (str url "/auth/recovery")
                :method :post
                :body data}]
    (send! params)))

(defmethod request :auth/validate-recovery-token
  [_ token]
  (let [params {:url (str url "/auth/recovery/" token)
                :method :get}]
    (send! params)))

(defmethod request :auth/recovery
  [_ data]
  (let [params {:url (str url "/auth/recovery")
                :method :put
                :body data}]
    (send! params)))
