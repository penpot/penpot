;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.pages
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.repo.core :refer (-do url send!)]
            [uxbox.state :as ust]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod -do :fetch/pages
  [type data]
  (send! {:url (str url "/pages") :method :get}))

(defmethod -do :fetch/pages-by-project
  [type {:keys [project] :as params}]
  (let [url (str url "/projects/" project "/pages")]
    (send! {:method :get :url url})))

(defmethod -do :fetch/page-history
  [type {:keys [page] :as params}]
  (let [url (str url "/pages/" page "/history")
        query (select-keys params [:max :since :pinned])]
    (send! {:method :get :url url :query query })))

(defmethod -do :delete/page
  [_ id]
  (let [url (str url "/pages/" id)]
    (send! {:url url :method :delete})))

(defmethod -do :create/page
  [type {:keys [id] :as data}]
  (let [params {:url (str url "/pages")
                :method :post
                :body data}]
    (send! params)))

(defmethod -do :update/page
  [type {:keys [id] :as data}]
  (let [params {:url (str url "/pages/" id)
                :method :put
                :body data}]
    (send! params)))

(defmethod -do :update/page-metadata
  [type {:keys [id] :as data}]
  (let [params {:url (str url "/pages/" id "/metadata")
                :method :put
                :body data}]
    (send! params)))
