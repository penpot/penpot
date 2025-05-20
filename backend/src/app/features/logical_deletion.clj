;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.logical-deletion
  "A code related to handle logical deletion mechanism"
  (:require
   [app.config :as cf]
   [app.util.time :as dt]))

(defn get-deletion-delay
  "Calculate the next deleted-at for a resource (file, team, etc) in function
  of team settings"
  [team]
  (if-let [subscription (get team :subscription)]
    (cond
      (and (= (:type subscription) "unlimited")
           (= (:status subscription) "active"))
      (dt/duration {:days 30})

      (and (= (:type subscription) "enterprise")
           (= (:status subscription) "active"))
      (dt/duration {:days 90})

      :else
      (cf/get-deletion-delay))

    (cf/get-deletion-delay)))

