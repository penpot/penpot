;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.images
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.repo.core :refer (request url send!)]
            [uxbox.state :as ust]
            [uxbox.util.transit :as t]))

(defn- decode-image-collection
  [{:keys [data] :as coll}]
  coll)
  ;; (merge coll
  ;;        (when data {:data (t/decode data)})))

(defn- decode-payload
  [{:keys [payload] :as rsp}]
  rsp)
  ;; (if (sequential? payload)
  ;;   (assoc rsp :payload (mapv decode-image-collection payload))
  ;;   (assoc rsp :payload (decode-image-collection payload))))

(defmethod request :fetch/image-collections
  [_]
  (let [params {:url (str url "/library/image-collections")
                :method :get}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :delete/image-collection
  [_ id]
  (let [url (str url "/library/image-collections/" id)]
    (send! {:url url :method :delete})))

(defmethod request :create/image-collection
  [_ {:keys [data] :as body}]
  (let [body (assoc body :data (t/encode data))
        params {:url (str url "/library/image-collections")
                :method :post
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :update/image-collection
  [_ {:keys [id data] :as body}]
  (let [body (assoc body :data (t/encode data))
        params {:url (str url "/library/image-collections/" id)
                :method :put
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :fetch/images
  [_ {:keys [coll]}]
  (let [params {:url (str url "/library/images/" coll)
                :method :get}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :create/image
  [_ {:keys [coll id files] :as body}]
  (let [build-body (fn []
                     (let [data (js/FormData.)]
                       (.append data "file" (aget files 0))
                       (.append data "id" id)
                       data))
        params {:url (str url "/library/images/" coll)
                :method :post
                :body (build-body)}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :delete/image
  [_ id]
  (let [url (str url "/library/images/" id)]
    (send! {:url url :method :delete})))
