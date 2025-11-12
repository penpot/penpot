;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.logical-deletion
  "A code related to handle logical deletion mechanism"
  (:require
   [app.common.time :as ct]
   [app.config :as cf]))

(def ^:private canceled-status
  #{"canceled" "unpaid"})

(defn get-deletion-delay
  "Calculate the next deleted-at for a resource (file, team, etc) in function
  of team settings"
  [team]
  (if-let [{:keys [type status]} (get team :subscription)]
    (cond
      (and (= "unlimited" type) (not (contains? canceled-status status)))
      (ct/duration {:days 30})

      (and (= "enterprise" type) (not (contains? canceled-status status)))
      (ct/duration {:days 90})

      :else
      (cf/get-deletion-delay))

    (cf/get-deletion-delay)))

