;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.auth
  "A main interface for access to remote resources."
  (:refer-clojure :exclude [do])
  (:require [httpurr.client.xhr :as http]
            [httpurr.status :as http.status]
            [promesa.core :as p :include-macros true]
            [beicon.core :as rx]
            [uxbox.repo.core :as urc]
            [uxbox.state :as ust]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-token
  [params]
  (urc/req! {:url (str urc/+uri+ "/auth/token")
             :method :post
             :auth false
             :body params}))

(defn- request-profile
  []
  (p/resolved {:fullname "Cirilla Fiona"
               :photo "/images/favicon.png"
               :username "cirilla"
               :email "cirilla@uxbox.io"}))

(defmethod urc/-do :login
  [type data]
  (p/alet [authdata (p/await (request-token data))
           profile (p/await (request-profile))]
    (merge profile authdata)))
