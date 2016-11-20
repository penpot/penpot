;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.users
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.config :refer (url)]
            [uxbox.main.repo.impl :refer (request send!)]
            [uxbox.util.transit :as t]))

(defmethod request :fetch/profile
  [type _]
  (let [url (str url "/profile/me")
        params {:method :get :url url}]
    (send! params)))

(defmethod request :update/profile
  [type body]
  (let [params {:url (str url "/profile/me")
                :method :put
                :body body}]
    (send! params)))

(defmethod request :update/profile-password
  [type data]
  (let [params {:url (str url "/profile/me/password")
                :method :put
                :body data}]
    (send! params)))

(defmethod request :update/profile-photo
  [_ {:keys [file] :as body}]
  (let [body (doto (js/FormData.)
               (.append "file" file))
        params {:url (str url "/profile/me/photo")
                :method :post
                :body body}]
    (send! params)))
