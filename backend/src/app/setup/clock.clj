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
   [app.common.time :as ct]
   [app.setup :as-alias setup]
   [integrant.core :as ig])
  (:import
   java.time.Clock
   java.time.Duration))

(defonce current
  (atom {:clock (Clock/systemDefaultZone)
         :offset nil}))

(defmethod ig/init-key ::setup/clock
  [_ _]
  (add-watch current ::common
             (fn [_ _ _ {:keys [clock offset]}]
               (let [clock (if (ct/duration? offset)
                             (Clock/offset ^Clock clock
                                           ^Duration offset)
                             clock)]
                 (l/wrn :hint "altering clock" :clock (str clock))
                 (alter-var-root #'ct/*clock* (constantly clock))))))


(defmethod ig/halt-key! ::setup/clock
  [_ _]
  (remove-watch current ::common))

(defn set-offset!
  [duration]
  (swap! current assoc :offset (some-> duration ct/duration)))

(defn set-clock!
  ([]
   (swap! current assoc :clock (Clock/systemDefaultZone)))
  ([clock]
   (when (instance? Clock clock)
     (swap! current assoc :clock clock))))
