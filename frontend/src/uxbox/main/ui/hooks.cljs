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

;; (defn- extract-type
;;   [dt]
;;   (let [types (unchecked-get dt "types")
;;         total (alength types)]
;;     (loop [i 0]
;;       (if (= i total)
;;         nil
;;         (if-let [match (re-find #"dnd/(.+)" (aget types i))]
;;           (second match)
;;           (recur (inc i)))))))


(defn invisible-image
  []
  (let [img (js/Image.)
        imd "data:image/gif;base64,R0lGODlhAQABAIAAAAUEBAAAACwAAAAAAQABAAACAkQBADs="]
    (set! (.-src img) imd)
    img))

(defn use-sortable
  [& {:keys [type data on-drop on-drag] :as opts}]
  (let [ref   (mf/use-ref)
        state (mf/use-state {})

        on-drag-start
        (fn [event]
          ;; (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [dtrans (unchecked-get event "dataTransfer")]
            (.setDragImage dtrans (invisible-image) 0 0)
            (set! (.-effectAllowed dtrans) "move")
            (.setData dtrans "application/json" (t/encode data))
            ;; (.setData dtrans (str "dnd/" type) "")
            (when (fn? on-drag)
              (on-drag data))
            (swap! state (fn [state]
                           (if (:dragging? state)
                             state
                             (assoc state :dragging? true))))))

        on-drag-over
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)

          (let [target (dom/get-target event)
                dtrans (unchecked-get event "dataTransfer")
                ypos   (unchecked-get event "offsetY")
                height (unchecked-get target "clientHeight")
                thold  (/ height 2)
                side   (if (> ypos thold) :bot :top)]

            (set! (.-dropEffect dtrans) "move")
            (set! (.-effectAllowed dtrans) "move")

            (swap! state update :over (fn [state]
                                        (if (not= state side)
                                          side
                                          state)))))

        ;; on-drag-enter
        ;; (fn [event]
        ;;   (dom/prevent-default event)
        ;;   (dom/stop-propagation event)
        ;;   (let [dtrans (unchecked-get event "dataTransfer")
        ;;         ty (extract-type dt)]
        ;;     (when (= ty type)
        ;;       #_(js/console.log "on-drag-enter" (:name data) ty type)
        ;;       #_(swap! state (fn [state]
        ;;                      (if (:over? state)
        ;;                        state
        ;;                        (assoc state :over? true)))))))

        on-drag-leave
        (fn [event]
          (let [target (.-currentTarget event)
                related (.-relatedTarget event)]
            (when-not (.contains target related)
              ;; (js/console.log "on-drag-leave" (:name data))
              (swap! state (fn [state]
                             (if (:over state)
                               (dissoc state :over)
                               state))))))

        on-drop'
        (fn [event]
          (dom/stop-propagation event)
          (let [target (dom/get-target event)
                dtrans (unchecked-get event "dataTransfer")
                dtdata (.getData dtrans "application/json")

                ypos   (unchecked-get event "offsetY")
                height (unchecked-get target "clientHeight")
                thold  (/ height 2)
                side   (if (> ypos thold) :bot :top)]

            ;; TODO: seems unnecessary
            (swap! state (fn [state]
                           (cond-> state
                             (:dragging? state) (dissoc :dragging?)
                             (:over state) (dissoc :over))))

            (when (fn? on-drop)
              (on-drop side (t/decode dtdata)))))

        on-drag-end
        (fn [event]
          (swap! state (fn [state]
                         (cond-> state
                           (:dragging? state) (dissoc :dragging?)
                           (:over state) (dissoc :over)))))

        on-mount
        (fn []
          (let [dom (mf/ref-val ref)]
            (.setAttribute dom "draggable" true)
            (.setAttribute dom "data-type" type)

            (.addEventListener dom "dragstart" on-drag-start false)
            ;; (.addEventListener dom "dragenter" on-drag-enter false)
            (.addEventListener dom "dragover" on-drag-over false)
            (.addEventListener dom "dragleave" on-drag-leave true)
            (.addEventListener dom "drop" on-drop' false)
            (.addEventListener dom "dragend" on-drag-end false)
            #(do
               (.removeEventListener dom "dragstart" on-drag-start)
               ;; (.removeEventListener dom "dragenter" on-drag-enter)
               (.removeEventListener dom "dragover" on-drag-over)
               (.removeEventListener dom "dragleave" on-drag-leave)
               (.removeEventListener dom "drop" on-drop')
               (.removeEventListener dom "dragend" on-drag-end))))]

    (mf/use-effect
     (mf/deps type data on-drop)
     on-mount)
    [(deref state) ref]))
