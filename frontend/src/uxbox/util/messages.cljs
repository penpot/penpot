;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.messages
  "Messages notifications."
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.timers :as ts]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Constants

(def +animation-timeout+ 600)

;; --- Message Event

(declare hide)
(declare show?)

(defn show
  [data]
  (reify
    ptk/EventType
    (type [_] ::show)

    ptk/UpdateEvent
    (update [_ state]
      (let [message (assoc data :state :visible)]
        (assoc state :message message)))

    ptk/WatchEvent
    (watch [_ state s]
      (let [stoper (->> (rx/filter show? s)
                        (rx/take 1))]
        (->> (rx/of (hide))
             (rx/delay (:timeout data))
             (rx/take-until stoper))))))

(defn show?
  [v]
  (= ::show (ptk/type v)))

(defn error
  [message & {:keys [timeout] :or {timeout 3000}}]
  (show {:content message
         :type :error
         :timeout timeout}))

(defn info
  [message & {:keys [timeout] :or {timeout 3000}}]
  (show {:content message
         :type :info
         :timeout timeout}))

(defn dialog
  [message & {:keys [on-accept on-cancel]}]
  (show {:content message
         :on-accept on-accept
         :on-cancel on-cancel
         :timeout js/Number.MAX_SAFE_INTEGER
         :type :dialog}))

;; --- Hide Message

(defn hide
  []
  (let [canceled? (volatile! {})]
    (reify
      ptk/UpdateEvent
      (update [_ state]
        (update state :message
                (fn [v]
                  (if (nil? v)
                    (do (vreset! canceled? true) nil)
                    (assoc v :state :hide)))))

      ptk/WatchEvent
      (watch [_ state stream]
        (if @canceled?
          (rx/empty)
          (->> (rx/of #(dissoc % :message))
               (rx/delay +animation-timeout+)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI Components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Notification Component

(mf/defc notification-box
  [{:keys [message on-close] :as message}]
  (let [type (:type message)
        classes (classnames :error (= type :error)
                            :info (= type :info)
                            :hide-message (= (:state message) :hide)
                            :quick true)]
    [:div.message {:class classes}
     [:div.message-body
      [:span.close {:on-click on-close} i/close]
      [:span (:content message)]]]))

;; --- Dialog Component

(mf/defc dialog-box
  [{:keys [on-accept on-cancel on-close message] :as props}]
  (let [classes (classnames :info true
                            :hide-message (= (:state message) :hide))]
    (letfn [(accept [event]
              (dom/prevent-default event)
              (on-accept)
              (ts/schedule 0 on-close))

            (cancel [event]
              (dom/prevent-default event)
              (when on-cancel
                (on-cancel))
              (ts/schedule 0 on-close))]
      [:div.message {:class classes}
       [:div.message-body
        [:span.close {:on-click cancel} i/close]
        [:span (:content message)]
        [:div.message-action
         [:a.btn-transparent.btn-small
          {:on-click accept}
          "Accept"]
         [:a.btn-transparent.btn-small
          {:on-click cancel}
          "Cancel"]]]])))

;; --- Main Component (entry point)

(mf/defc messages-widget
  [{:keys [message] :as props}]
  (case (:type message)
    :error (mf/element notification-box props)
    :info (mf/element notification-box props)
    :dialog (mf/element dialog-box props)
    nil))
