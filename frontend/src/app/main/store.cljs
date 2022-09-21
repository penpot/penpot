;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.store
  (:require
   [app.common.logging :as log]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]))

(log/set-level! :info)

(enable-console-print!)

(defonce loader (l/atom false))
(defonce on-error (l/atom identity))

(defmethod ptk/resolve :default
  [type data]
  (ptk/data-event type data))

(defonce state
  (ptk/store {:resolve ptk/resolve
              :on-error (fn [e] (@on-error e))}))

(defonce stream
  (ptk/input-stream state))

(defonce last-events
  (let [buffer  (atom [])
        allowed #{:app.main.data.workspace/initialize-page
                  :app.main.data.workspace/finalize-page
                  :app.main.data.workspace/initialize-file
                  :app.main.data.workspace/finalize-file}]
    (->> (rx/merge
          (->> stream
               (rx/filter (ptk/type? :app.main.data.workspace.changes/commit-changes))
               (rx/map #(-> % deref :hint-origin str))
               (rx/dedupe))
          (->> stream
               (rx/map ptk/type)
               (rx/filter #(contains? allowed %))
               (rx/map str)))
         (rx/scan (fn [buffer event]
                    (cond-> (conj buffer event)
                      (> (count buffer) 20)
                      (pop)))
                  #queue [])
         (rx/subs #(reset! buffer (vec %))))
    buffer))

(defn emit!
  ([] nil)
  ([event]
   (ptk/emit! state event)
   nil)
  ([event & events]
   (apply ptk/emit! state (cons event events))
   nil))

(defonce ongoing-tasks (l/atom #{}))

(add-watch ongoing-tasks ::ongoing-tasks
           (fn [_ _ _ events]
             (if (empty? events)
               (obj/set! js/window "onbeforeunload" nil)
               (obj/set! js/window "onbeforeunload" (constantly false)))))
