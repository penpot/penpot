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

(defn drop-side
  [height ypos detect-center?]
  (let [thold  (/ height 2)
        thold1 (* height 0.2)
        thold2 (* height 0.8)]
    (if detect-center?
      (cond
        (< ypos thold1) :top
        (> ypos thold2) :bot
        :else :center)
      (if (> ypos thold) :bot :top))))

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

;; The dnd interface is broken in several ways. This is the official documentation
;; https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API
;;
;; And there is some discussion of the problems and many uncomplete solutions
;; https://github.com/lolmaus/jquery.dragbetter/#what-this-is-all-about
;; https://www.w3schools.com/jsref/event_relatedtarget.asp
;; https://stackoverflow.com/questions/14194324/firefox-firing-dragleave-when-dragging-over-text?noredirect=1&lq=1
;; https://stackoverflow.com/questions/7110353/html5-dragleave-fired-when-hovering-a-child-element

;; This function is useful to debug the erratic dnd interface behaviour when something weird occurs
;; (defn- trace
;;   [event data label]
;;   (js/console.log
;;     label
;;     "[" (:name data) "]"
;;     (if (.-currentTarget event) (.-textContent (.-currentTarget event)) "null")
;;     (if (.-relatedTarget event) (.-textContent (.-relatedTarget event)) "null")))

(defn use-sortable
  [& {:keys [type data on-drop on-drag on-hold detect-center?] :as opts}]
  (let [ref   (mf/use-ref)
        state (mf/use-state {:over nil
                             :timer nil})

        on-drag-start
        (fn [event]
          (dom/stop-propagation event)
          ;; (trace event data "drag-start")
          (let [dtrans (unchecked-get event "dataTransfer")]
            (.setDragImage dtrans (invisible-image) 0 0)
            (set! (.-effectAllowed dtrans) "move")
            (.setData dtrans "application/json" (t/encode data))
            (when (fn? on-drag)
              (on-drag data))))

        on-drag-enter
        (fn [event]
          (dom/prevent-default event) ;; prevent default to allow drag enter
          (let [target (.-currentTarget event)
                related (.-relatedTarget event)]
            (when-not (.contains target related) ;; ignore events triggered by elements that are
              (dom/stop-propagation event)       ;; children of the drop target
              ;; (trace event data "drag-enter")
              (when (fn? on-hold)
                (swap! state (fn [state]
                               (-> state
                                   (cancel-timer)
                                   (set-timer 1000 on-hold))))))))

        on-drag-over
        (fn [event]
          (dom/prevent-default event) ;; prevent default to allow drag over
          (let [target (dom/get-target event)
                related (.-relatedTarget event)
                dtrans (unchecked-get event "dataTransfer")
                ypos   (unchecked-get event "offsetY")
                height (unchecked-get target "clientHeight")
                side   (drop-side height ypos detect-center?)]
            (when-not (.contains target related)
              (dom/stop-propagation event)
              ;; (trace event data "drag-over")
              (swap! state assoc :over side))))

        on-drag-leave
        (fn [event]
          (let [target (.-currentTarget event)
                related (.-relatedTarget event)]
            (when-not (.contains target related)
              (dom/stop-propagation event)
              ;; (trace event data "drag-leave")
              (swap! state (fn [state]
                             (-> state
                               (cancel-timer)
                               (dissoc :over)))))))

        on-drop'
        (fn [event]
          (dom/stop-propagation event)
          ;; (trace event data "drop")
          (let [target (dom/get-target event)
                dtrans (unchecked-get event "dataTransfer")
                dtdata (.getData dtrans "application/json")

                ypos   (unchecked-get event "offsetY")
                height (unchecked-get target "clientHeight")
                side   (drop-side height ypos detect-center?)]

            (swap! state (fn [state]
                           (-> state
                             (cancel-timer)
                             (dissoc :over))))

            (when (fn? on-drop)
              (on-drop side (t/decode dtdata)))))

        on-drag-end
        (fn [event]
          ;; (trace event data "drag-end")
          (swap! state (fn [state]
                         (-> state
                           (cancel-timer)
                           (dissoc :over)))))

        on-mount
        (fn []
          (let [dom (mf/ref-val ref)]
            (.setAttribute dom "draggable" true)
            (.setAttribute dom "data-type" type)

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
     (mf/deps type data on-drop)
     on-mount)

    [(deref state) ref]))

