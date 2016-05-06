;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.users
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.repo.core :refer (request url send!)]
            [uxbox.util.transit :as t]))

(defn- decode-payload
  [{:keys [payload] :as rsp}]
  (let [metadata (:metadata payload)]
    (assoc rsp :payload
           (assoc payload :metadata (t/decode metadata)))))

(defmethod request :fetch/profile
  [type _]
  (let [url (str url "/profile/me")
        params {:method :get :url url}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :update/profile
  [type {:keys [metadata id] :as body}]
  (let [body (assoc body :metadata (t/encode metadata))
        params {:url (str url "/profile/me")
                :method :put
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :update/password
  [type data]
  (let [params {:url (str url "/profile/me/password")
                :method :put
                :body data}]
    (send! params)))
