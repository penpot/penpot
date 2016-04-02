;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.users
  "A main interface for access to remote resources."
  (:refer-clojure :exclude [do])
  (:require [beicon.core :as rx]
            [uxbox.repo.core :refer (request url send!)]
            [uxbox.state :as ust]))

(defmethod request :fetch/profile
  [type _]
  (let [url (str url "/profile/me")]
    (send! {:method :get :url url})))

(defmethod request :update/profile
  [type data]
  (let [params {:url (str url "/profile/me")
                :method :put
                :body data}]
    (send! params)))
