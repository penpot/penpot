;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.kvstore
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [cuerdas.core :as str]
            [uxbox.config :refer (url)]
            [uxbox.main.repo.impl :refer (request send!)]
            [uxbox.util.transit :as t]))

(defmethod request :fetch/kvstore
  [_ id]
  (let [url (str url "/kvstore/" id)
        params {:url url :method :get}]
    (->> (send! params)
         (rx/map (fn [{:keys [payload] :as response}]
                   (if (or (nil? payload) (str/empty? payload))
                     (assoc response :payload {:key id :value nil :version nil})
                     response))))))

(defmethod request :update/kvstore
  [_ data]
  (let [url (str url "/kvstore")
        params {:url url :method :put :body data}]
    (send! params)))
