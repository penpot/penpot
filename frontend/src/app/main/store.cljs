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

(def on-event identity)

(def ^:dynamic *debug-events* false)

;; Only created in development build
(when *assert*
  (def debug-exclude-events
    #{:app.main.data.workspace.notifications/handle-pointer-update
      :app.main.data.workspace.notifications/handle-pointer-send
      :app.main.data.workspace.persistence/update-persistence-status
      :app.main.data.workspace.changes/update-indices
      :app.main.data.websocket/send-message
      :app.main.data.workspace.selection/change-hover-state})

  (set! on-event (fn [e]
                   (when (and *debug-events*
                              (ptk/event? e)
                              (not (debug-exclude-events (ptk/type e))))
                     (.log js/console (str "[stream]: " (ptk/repr-event e)) )))))

(defonce state
  (ptk/store {:resolve ptk/resolve
              :on-event on-event
              :on-error (fn [cause]
                          (when cause
                            #_(log/error :hint "unexpected exception on store" :cause cause)
                            (@on-error cause)))}))

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
