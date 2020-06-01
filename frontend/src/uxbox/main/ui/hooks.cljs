;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs S.L

(ns uxbox.main.ui.hooks
  "A collection of general purpose react hooks."
  (:require
   [cljs.spec.alpha :as s]
   [uxbox.common.spec :as us]
   [beicon.core :as rx]
   [goog.events :as events]
   [rumext.alpha :as mf]
   [uxbox.util.transit :as t]
   [uxbox.util.dom :as dom]
   [uxbox.util.dom.dnd :as dnd]
   [uxbox.util.webapi :as wapi]
   [uxbox.util.timers :as ts]
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

(defn use-fullscreen
  [ref]
  (let [state (mf/use-state (dom/fullscreen?))
        change (mf/use-callback #(reset! state (dom/fullscreen?)))
        toggle (mf/use-callback (mf/deps @state)
                                #(let [el (mf/ref-val ref)]
                                   (swap! state not)
                                   (if @state
                                     (wapi/exit-fullscreen)
                                     (wapi/request-fullscreen el))))]
    (mf/use-effect
     (fn []
       (.addEventListener js/document "fullscreenchange" change)
       #(.removeEventListener js/document "fullscreenchange" change)))

    [toggle @state]))

(defn invisible-image
  []
  (let [img (js/Image.)
        imd "data:image/gif;base64,R0lGODlhAQABAIAAAAUEBAAAACwAAAAAAQABAAACAkQBADs="]
    (set! (.-src img) imd)
    img))

(defn- set-timer
  [state ms func]
  (assoc state :timer (ts/schedule ms func)))

(defn- cancel-timer
  [state]
  (let [timer (:timer state)]
    (if timer
      (do
        (rx/dispose! timer)
        (dissoc state :timer))
      state)))

(defn use-sortable
  [& {:keys [data-type data on-drop on-drag on-hold detect-center?] :as opts}]
  (let [ref   (mf/use-ref)
        state (mf/use-state {:over nil
                             :timer nil})

        on-drag-start
        (fn [event]
          (dom/stop-propagation event)
          ;; (dnd/trace event data "drag-start")
          (dnd/set-data! event data-type data)
          (dnd/set-drag-image! event (invisible-image))
          (dnd/set-allowed-effect! event "move")
          (when (fn? on-drag)
            (on-drag data)))

        on-drag-enter
        (fn [event]
          (dom/prevent-default event) ;; prevent default to allow drag enter
          (when-not (dnd/from-child? event)
            (dom/stop-propagation event)
            ;; (dnd/trace event data "drag-enter")
            (when (fn? on-hold)
              (swap! state (fn [state]
                             (-> state
                                 (cancel-timer)
                                 (set-timer 1000 on-hold)))))))

        on-drag-over
        (fn [event]
          (when (dnd/has-type? event data-type)
            (dom/prevent-default event) ;; prevent default to allow drag over
            (when-not (dnd/from-child? event)
              (dom/stop-propagation event)
              ;; (dnd/trace event data "drag-over")
              (let [side (dnd/drop-side event detect-center?)]
                (swap! state assoc :over side)))))

        on-drag-leave
        (fn [event]
          (when-not (dnd/from-child? event)
            (dom/stop-propagation event)
            ;; (dnd/trace event data "drag-leave")
            (swap! state (fn [state]
                           (-> state
                             (cancel-timer)
                             (dissoc :over))))))

        on-drop'
        (fn [event]
          (dom/stop-propagation event)
          ;; (dnd/trace event data "drop")
          (let [side (dnd/drop-side event detect-center?)
                drop-data (dnd/get-data event data-type)]
            (swap! state (fn [state]
                           (-> state
                             (cancel-timer)
                             (dissoc :over))))
            (when (fn? on-drop)
              (on-drop side drop-data))))

        on-drag-end
        (fn [event]
          ;; (dnd/trace event data "drag-end")
          (swap! state (fn [state]
                         (-> state
                           (cancel-timer)
                           (dissoc :over)))))

        on-mount
        (fn []
          (let [dom (mf/ref-val ref)]
            (.setAttribute dom "draggable" true)

            (.addEventListener dom "dragstart" on-drag-start false)
            (.addEventListener dom "dragenter" on-drag-enter false)
            (.addEventListener dom "dragover" on-drag-over false)
            (.addEventListener dom "dragleave" on-drag-leave true)
            (.addEventListener dom "drop" on-drop' false)
            (.addEventListener dom "dragend" on-drag-end false)
            #(do
               (.removeEventListener dom "dragstart" on-drag-start)
               (.removeEventListener dom "dragenter" on-drag-enter)
               (.removeEventListener dom "dragover" on-drag-over)
               (.removeEventListener dom "dragleave" on-drag-leave)
               (.removeEventListener dom "drop" on-drop')
               (.removeEventListener dom "dragend" on-drag-end))))]

    (mf/use-effect
     (mf/deps data on-drop)
     on-mount)

    [(deref state) ref]))


(defn use-stream
  "Wraps the subscription to a strem into a `use-effect` call"
  [stream on-subscribe]
  (mf/use-effect (fn []
                   (let [sub (->> stream (rx/subs on-subscribe))]
                     #(rx/dispose! sub)))))
