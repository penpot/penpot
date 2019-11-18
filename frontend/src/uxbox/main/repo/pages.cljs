;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.pages
  "A main interface for access to remote resources."
  (:require
   [uxbox.config :refer [url]]
   [uxbox.main.repo.impl :as rp :refer [request send!]]))

(defmethod request :fetch/pages-by-project
  [type {:keys [project] :as params}]
  (let [url (str url "/w/query/pages-by-project")
        params {:project-id project}]
    (send! {:method :get :url url :query params})))

(defmethod request :fetch/page-history
  [type {:keys [page] :as params}]
  (let [url (str url "/w/query/page-history")
        query  (-> (select-keys params [:max :since :pinned])
                   (assoc :id page))
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
  (let [params {:url (str url "/w/mutation/update-page")
                :method :post
                :body body}]
    (send! params)))

(defmethod request :update/page-metadata
  [type {:keys [id] :as body}]
  (let [params {:url (str url "/w/mutation/update-page-metadata")
                :method :post
                :body body}]
    (send! params)))


(defmethod request :update/page-history
  [type {:keys [id page] :as data}]
  (let [params {:url (str url "/pages/" page "/history/" id)
                :method :put
                :body data}]
    (send! params)))
