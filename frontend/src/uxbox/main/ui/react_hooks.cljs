;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs S.L

(ns uxbox.main.ui.react-hooks
  "A collection of general purpose react hooks."
  (:require
   [cljs.spec.alpha :as s]
   [uxbox.common.spec :as us]
   [beicon.core :as rx]
   [goog.events :as events]
   [rumext.alpha :as mf]
   ["mousetrap" :as mousetrap])
  (:import goog.events.EventType))

(defn use-rxsub
  [ob]
  (let [[state reset-state!] (mf/useState @ob)]
    (mf/useEffect
     (fn []
       (let [sub (rx/subscribe ob #(reset-state! %))]
         #(rx/cancel! sub)))
     #js [ob])
    state))


(s/def ::shortcuts
  (s/map-of ::us/string fn?))

(defn use-shortcuts
  [shortcuts]
  (us/assert ::shortcuts shortcuts)
  (mf/use-effect
   (fn []
     (->> (seq shortcuts)
          (run! (fn [[key f]]
                  (mousetrap/bind key (fn [event]
                                        (js/console.log "[debug]: shortcut:" key)
                                        (.preventDefault event)
                                        (f event))))))
     (fn [] (mousetrap/reset))))
  nil)

