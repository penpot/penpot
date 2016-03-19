;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.auth
  "A main interface for access to remote resources."
  (:refer-clojure :exclude [do])
  (:require [beicon.core :as rx]
            [uxbox.repo.core :refer (-do url send!)]
            [uxbox.state :as ust]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-token
  [params]
  (let [url (str url "/auth/token")]
    (send! {:url url
                :method :post
                :auth false
                :body params})))

(defn- request-profile
  []
  (rx/of {:fullname "Cirilla Fiona"
          :photo "/images/favicon.png"
          :username "cirilla"
          :email "cirilla@uxbox.io"}))

(defmethod -do :login
  [type data]
  (->> (rx/zip (request-token data)
               (request-profile))
       (rx/map (fn [[authdata profile]]
                 (println authdata profile)
                 (println authdata profile)
                 (merge authdata profile)))))
