;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.colors
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.util.constants :refer (url)]
            [uxbox.main.repo.impl :refer (request send!)]
            [uxbox.util.transit :as t]))

(defn- decode-color-collection
  [{:keys [data] :as coll}]
  (merge coll
         (when data {:data (t/decode data)})))

(defn- decode-payload
  [{:keys [payload] :as rsp}]
  (if (sequential? payload)
    (assoc rsp :payload (mapv decode-color-collection payload))
    (assoc rsp :payload (decode-color-collection payload))))

(defmethod request :fetch/color-collection
  [_ id]
  (let [params {:url (str url "/library/color-collections/" id)
                :method :get}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :fetch/color-collections
  [_]
  (let [params {:url (str url "/library/color-collections")
                :method :get}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :delete/color-collection
  [_ id]
  (let [url (str url "/library/color-collections/" id)]
    (send! {:url url :method :delete})))

(defmethod request :create/color-collection
  [_ {:keys [data] :as body}]
  (let [body (assoc body :data (t/encode data))
        params {:url (str url "/library/color-collections")
                :method :post
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :update/color-collection
  [_ {:keys [id data] :as body}]
  (let [body (assoc body :data (t/encode data))
        params {:url (str url "/library/color-collections/" id)
                :method :put
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))
