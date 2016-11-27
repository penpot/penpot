;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.messages
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.util.timers :as ts]))

;; --- Constants

(def +animation-timeout+ 600)

;; --- Message Event

(declare hide-message)
(declare show-message?)

(defrecord ShowMessage [data]
  ptk/UpdateEvent
  (update [_ state]
    (let [message (assoc data :state :visible)]
      (assoc state :message message)))

  ptk/WatchEvent
  (watch [_ state s]
    (let [stoper (->> (rx/filter show-message? s)
                      (rx/take 1))]
      (->> (rx/of (hide-message))
           (rx/delay (:timeout data))
           (rx/take-until stoper)))))

(defn show-message
  [message]
  (ShowMessage. message))

(defn show-message?
  [v]
  (instance? ShowMessage v))

(defn show-error
  [message & {:keys [timeout] :or {timeout 3000}}]
  (show-message {:content message
                 :type :error
                 :timeout timeout}))

(defn show-info
  [message & {:keys [timeout] :or {timeout 3000}}]
  (show-message {:content message
                 :type :info
                 :timeout timeout}))

(defn show-dialog
  [message & {:keys [on-accept on-cancel]}]
  (show-message {:content message
                 :on-accept on-accept
                 :on-cancel on-cancel
                 :timeout js/Number.MAX_SAFE_INTEGER
                 :type :dialog}))

;; --- Hide Message

(defrecord HideMessage [^:mutable canceled?]
  ptk/UpdateEvent
  (update [_ state]
    (update state :message
            (fn [v]
              (if (nil? v)
                (do (set! canceled? true) nil)
                (assoc v :state :hide)))))

  ptk/WatchEvent
  (watch [_ state s]
    (if canceled?
      (rx/empty)
      (->> (rx/of #(dissoc state :message))
           (rx/delay +animation-timeout+)))))

(defn hide-message
  []
  (HideMessage. false))

;; --- Direct Call Api

(defn error!
  [& args]
  (ts/schedule 0 #(st/emit! (apply show-error args))))

(defn info!
  [& args]
  (ts/schedule 0 #(st/emit! (apply show-info args))))

(defn dialog!
  [& args]
  (ts/schedule 0 #(st/emit! (apply show-dialog args))))

(defn close!
  []
  (st/emit! (hide-message)))

(defn error
  [& args]
  (rx/of (apply show-error args)))

(defn info
  [& args]
  (rx/of (apply show-info args)))
