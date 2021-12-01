;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.store
  (:require-macros [app.main.store])
  (:require
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]))

(enable-console-print!)

(defonce loader (l/atom false))
(defonce on-error (l/atom identity))

(defonce state
  (ptk/store {:resolve ptk/resolve
              :on-error (fn [e] (@on-error e))}))

(defonce stream
  (ptk/input-stream state))

(defonce last-events
  (let [buffer (atom #queue [])
        remove #{:potok.core/undefined
                 :app.main.data.workspace.notifications/handle-pointer-update}]
    (->> stream
         (rx/filter ptk/event?)
         (rx/map ptk/type)
         (rx/filter (complement remove))
         (rx/map str)
         (rx/dedupe)
         (rx/buffer 20 1)
         (rx/subs #(reset! buffer %)))

    buffer))

(defn emit!
  ([] nil)
  ([event]
   (ptk/emit! state event)
   nil)
  ([event & events]
   (apply ptk/emit! state (cons event events))
   nil))

(defn emitf
  [& events]
  #(apply ptk/emit! state events))


