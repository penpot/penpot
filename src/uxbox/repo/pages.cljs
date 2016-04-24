;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.pages
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.repo.core :refer (request url send!)]
            [uxbox.state :as ust]
            [uxbox.util.transit :as t]))

(defn- decode-page
  [{:keys [data options] :as page}]
  (merge page
         (when data {:data (t/decode data)})
         (when options {:options (t/decode options)})))

(defn- decode-payload
  [{:keys [payload] :as rsp}]
  (if (sequential? payload)
    (assoc rsp :payload (mapv decode-page payload))
    (assoc rsp :payload (decode-page payload))))

(defmethod request :fetch/pages
  [type data]
  (let [params {:url (str url "/pages")
                :method :get}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :fetch/pages-by-project
  [type {:keys [project] :as params}]
  (let [url (str url "/projects/" project "/pages")]
    (->> (send! {:method :get :url url})
         (rx/map decode-payload))))

(defmethod request :fetch/page-history
  [type {:keys [page] :as params}]
  (let [url (str url "/pages/" page "/history")
        query (select-keys params [:max :since :pinned])
        params {:method :get :url url :query query}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :delete/page
  [_ id]
  (let [url (str url "/pages/" id)]
    (send! {:url url
            :method :delete})))

(defmethod request :create/page
  [type {:keys [data options] :as body}]
  (let [body (assoc body
                    :data (t/encode data)
                    :options (t/encode options))
        params {:url (str url "/pages")
                :method :post
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :update/page
  [type {:keys [id data options] :as body}]
  (let [body (assoc body
                    :data (t/encode data)
                    :options (t/encode options))
        params {:url (str url "/pages/" id)
                :method :put
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :update/page-history
  [type {:keys [id page] :as data}]
  (let [params {:url (str url "/pages/" page "/history/" id)
                :method :put
                :body data}]
    (->> (send! params)
         (rx/map decode-payload))))

(defmethod request :update/page-metadata
  [type {:keys [id options] :as body}]
  (let [body (dissoc body :data)
        body (assoc body :options (t/encode options))
        params {:url (str url "/pages/" id "/metadata")
                :method :put
                :body body}]
    (->> (send! params)
         (rx/map decode-payload))))
