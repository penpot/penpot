;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.images
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.config :refer (url)]
            [uxbox.main.repo.impl :refer (request send!)]
            [uxbox.util.transit :as t]))

(defmethod request :fetch/image-collections
  [_]
  (let [params {:url (str url "/library/image-collections")
                :method :get}]
    (send! params)))

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
    (send! params)))

(defmethod request :update/image-collection
  [_ {:keys [id data] :as body}]
  (let [body (assoc body :data (t/encode data))
        params {:url (str url "/library/image-collections/" id)
                :method :put
                :body body}]
    (send! params)))

(defmethod request :fetch/images
  [_ {:keys [coll]}]
  (let [url (str url "/library/images")
        qp  (when coll {:collection coll})
        params {:url url :method :get :query qp}]
    (send! params)))

(defmethod request :fetch/image
  [_ {:keys [id]}]
  (let [params {:url (str url "/library/images/" id)
                :method :get}]
    (send! params)))

(defmethod request :create/image
  [_ {:keys [collection id file width height mimetype] :as body}]
  (let [body (doto (js/FormData.)
               (.append "mimetype" mimetype)
               (.append "collection" (str collection))
               (.append "file" file)
               (.append "width" width)
               (.append "height" height)
               (.append "id" id))
        params {:url (str url "/library/images/")
                :method :post
                :body body}]
    (send! params)))

(defmethod request :delete/image
  [_ id]
  (let [url (str url "/library/images/" id)]
    (send! {:url url :method :delete})))

(defmethod request :update/image
  [_ {:keys [id collection] :as body}]
  (let [params {:url (str url "/library/images/" id)
                :method :put
                :body body}]
    (send! params)))

(defmethod request :copy/image
  [_ {:keys [id collection] :as body}]
  (let [params {:url (str url "/library/images/copy")
                :method :put
                :body body}]
    (send! params)))
