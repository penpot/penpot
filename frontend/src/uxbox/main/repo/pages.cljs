;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.pages
  "A main interface for access to remote resources."
  (:require
   [uxbox.config :refer [url]]
   [uxbox.main.repo.impl :refer [request send!]]))

(defmethod request :fetch/pages
  [type data]
  (let [params {:url (str url "/pages")
                :method :get}]
    (send! params)))

(defmethod request :fetch/pages-by-project
  [type {:keys [project] :as params}]
  (let [url (str url "/pages")
        params {:project project}]
    (send! {:method :get :url url :query params})))

(defmethod request :fetch/page-history
  [type {:keys [page] :as params}]
  (let [url (str url "/pages/" page "/history")
        query (select-keys params [:max :since :pinned])
        params {:method :get :url url :query query}]
    (send! params)))

(defmethod request :delete/page
  [_ id]
  (let [url (str url "/pages/" id)]
    (send! {:url url
            :method :delete})))

(defmethod request :create/page
  [type body]
  (let [params {:url (str url "/pages")
                :method :post
                :body body}]
    (send! params)))

(defmethod request :update/page
  [type {:keys [id] :as body}]
  (let [params {:url (str url "/pages/" id)
                :method :put
                :body body}]
    (send! params)))

(defmethod request :update/page-history
  [type {:keys [id page] :as data}]
  (let [params {:url (str url "/pages/" page "/history/" id)
                :method :put
                :body data}]
    (send! params)))

(defmethod request :update/page-metadata
  [type {:keys [id metadata] :as body}]
  (let [body (dissoc body :data)
        params {:url (str url "/pages/" id "/metadata")
                :method :put
                :body body}]
    (send! params)))
