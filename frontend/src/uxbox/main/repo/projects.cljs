;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.projects
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.config :refer (url)]
            [uxbox.main.repo.pages :as pages]
            [uxbox.main.repo.impl :refer (request send!)]
            [uxbox.util.transit :as t]))

(defmethod request :fetch/projects
  [type data]
  ;; Obtain the list of projects and decode the embedded
  ;; page data in order to have it usable.
  (send! {:url (str url "/projects")
          :method :get}))

(defmethod request :fetch/project-by-token
  [_ token]
  (send! {:url (str url "/projects-by-token/" token)
          :method :get}))

(defmethod request :create/project
  [_ data]
  (send! {:url (str url "/projects")
          :method :post
          :body data}))

(defmethod request :update/project
  [_ {:keys [id] :as data}]
  (send! {:url (str url "/projects/" id)
          :method :put
          :body data}))

(defmethod request :delete/project
  [_ id]
  (send! {:url (str url "/projects/" id)
          :method :delete}))
