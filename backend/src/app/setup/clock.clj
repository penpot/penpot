;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.setup.clock
  "A service/module that manages the system clock and allows runtime
  modification of time offset (useful for testing and time adjustments)."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]))

(defonce state
  (atom {}))

(defn assign-offset
  "Assign virtual clock offset to a specific user. Is the responsability
  of RPC module to properly bind the correct clock to the user
  request."
  [profile-id duration]
  (swap! state (fn [state]
                 (if (nil? duration)
                   (dissoc state profile-id)
                   (assoc state profile-id duration)))))

(defn get-offset
  [profile-id]
  (get @state profile-id))

(defn get-clock
  [profile-id]
  (if-let [offset (get-offset profile-id)]
    (ct/offset-clock offset)
    (ct/get-system-clock)))

(defn set-global-clock
  ([]
   (set-global-clock (ct/get-system-clock)))
  ([clock]
   (assert (ct/clock? clock) "expected valid clock instance")
   (l/wrn :hint "altering clock" :clock (str clock))
   (alter-var-root #'ct/*clock* (constantly clock))))
