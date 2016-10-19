;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.icons
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.config :refer (url)]
            [uxbox.main.repo.impl :refer (request send!)]
            [uxbox.util.transit :as t]))

(defmethod request :fetch/icon-collections
  [_]
  (let [params {:url (str url "/library/icon-collections")
                :method :get}]
    (send! params)))

(defmethod request :delete/icon-collection
  [_ id]
  (let [url (str url "/library/icon-collections/" id)]
    (send! {:url url :method :delete})))

(defmethod request :create/icon-collection
  [_ {:keys [data] :as body}]
  (let [body (assoc body :data (t/encode data))
        params {:url (str url "/library/icon-collections")
                :method :post
                :body body}]
    (send! params)))

(defmethod request :update/icon-collection
  [_ {:keys [id data] :as body}]
  (let [body (assoc body :data (t/encode data))
        params {:url (str url "/library/icon-collections/" id)
                :method :put
                :body body}]
    (send! params)))

(defmethod request :fetch/icons
  [_ {:keys [coll]}]
  (let [url (if coll
              (str url "/library/icon-collections/" coll "/icons")
              (str url "/library/icon-collections/icons"))
        params {:url url :method :get}]
    (send! params)))

(defmethod request :fetch/icon
  [_ {:keys [id]}]
  (let [params {:url (str url "/library/icons/" id)
                :method :get}]
    (send! params)))

(defmethod request :create/icon
  [_ body]
  (let [params {:url (str url "/library/icons")
                :method :post
                :body body}]
    (send! params)))

(defmethod request :delete/icon
  [_ id]
  (let [url (str url "/library/icons/" id)]
    (send! {:url url :method :delete})))
