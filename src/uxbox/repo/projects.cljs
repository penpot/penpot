;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.projects
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

(defmethod urc/-do :fetch/projects
  [type data]
  (urc/req! {:url (str urc/+uri+ "/projects") :method :get}))

(defmethod urc/-do :fetch/pages
  [type data]
  (urc/req! {:url (str urc/+uri+ "/pages") :method :get}))

(defmethod urc/-do :create/page
  [type {:keys [id] :as data}]
  (let [params {:url (str urc/+uri+ "/pages")
                :method :post
                :body data}]
    (urc/req! params)))

(defmethod urc/-do :update/page
  [type {:keys [id] :as data}]
  (let [params {:url (str urc/+uri+ "/pages/" id)
                :method :put
                :body data}]
    (urc/req! params)))
