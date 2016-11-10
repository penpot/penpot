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
  (letfn [(decode-payload [{:keys [payload] :as response}]
            (assoc response :payload (mapv decode-page payload)))
          (decode-page [{:keys [page-metadata page-data] :as project}]
            (assoc project
                   :page-metadata (t/decode page-metadata)
                   :page-data (t/decode page-data)))]
    ;; Obtain the list of projects and decode the embedded
    ;; page data in order to have it usable.
    (->> (send! {:url (str url "/projects")
                 :method :get})
         (rx/map decode-payload))))

(defmethod request :fetch/project-by-token
  [_ token]
  (letfn [(decode-pages [response]
            (let [pages (->> (get-in response [:payload :pages])
                             (mapv pages/decode-page))]
              (assoc-in response [:payload :pages] pages)))]
    (->> (send! {:url (str url "/projects-by-token/" token)
                 :method :get})
         (rx/map decode-pages))))

(defmethod request :create/project
  [_ data]
  (let [params {:url (str url "/projects")
                :method :post
                :body data}]
    (send! params)))

(defmethod request :delete/project
  [_ id]
  (let [url (str url "/projects/" id)]
    (send! {:url url :method :delete})))
