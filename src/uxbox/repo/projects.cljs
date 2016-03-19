;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.projects
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.repo.core :refer (-do url send!)]
            [uxbox.state :as ust]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod -do :fetch/projects
  [type data]
  (let [url (str url "/projects")]
    (send! {:url url :method :get})))

(defmethod -do :create/project
  [_ data]
  (let [params {:url (str url "/projects")
                :method :post
                :body data}]
    (send! params)))

(defmethod -do :delete/project
  [_ id]
  (let [url (str url "/projects/" id)]
    (send! {:url url :method :delete})))
