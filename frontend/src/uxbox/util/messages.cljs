;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.messages
  "Messages notifications."
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.util.timers :as ts]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.data :refer [classnames]]
            [uxbox.util.dom :as dom]
            [uxbox.util.i18n :refer [tr]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Constants

(def +animation-timeout+ 600)

;; --- Message Event

(declare hide)
(declare show?)

(deftype Show [data]
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
           (rx/take-until stoper)))))

(defn show
  [message]
  (Show. message))

(defn show?
  [v]
  (instance? Show v))

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

(deftype Hide [^:mutable canceled?]
  ptk/UpdateEvent
  (update [_ state]
    (update state :message
            (fn [v]
              (if (nil? v)
                (do (set! canceled? true) nil)
                (assoc v :state :hide)))))

  ptk/WatchEvent
  (watch [_ state stream]
    (if canceled?
      (rx/empty)
      (->> (rx/of #(dissoc state :message))
           (rx/delay +animation-timeout+)))))

(defn hide
  []
  (Hide. false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI Components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Notification Component

(mx/defc notification-box
  {:mixins [mx/static]}
  [{:keys [type on-close] :as message}]
  (let [classes (classnames :error (= type :error)
                            :info (= type :info)
                            :hide-message (= (:state message) :hide)
                            :quick true)]
    [:div.message {:class classes}
     [:div.message-body
      [:span.close {:on-click on-close} i/close]
      [:span (:content message)]]]))

;; --- Dialog Component

(mx/defc dialog-box
  {:mixins [mx/static mx/reactive]}
  [{:keys [on-accept on-cancel on-close] :as message}]
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
          (tr "ds.accept")]
         [:a.btn-transparent.btn-small
          {:on-click cancel}
          (tr "ds.cancel")]]]])))

;; --- Main Component (entry point)

(mx/defc messages-widget
  {:mixins [mx/static mx/reactive]}
  [message]
  (case (:type message)
    :error (notification-box message)
    :info (notification-box message)
    :dialog (dialog-box message)
    nil))
