;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.data.messages
  (:require [cuerdas.core :as str]
            [promesa.core :as p]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]))

;; --- Constants

(def ^:const +animation-timeout+ 600)

;; --- Message Event

(declare hide-message)
(declare show-message?)

(defrecord ShowMessage [data]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [message (assoc data :state :visible)]
      (assoc state :message message)))

  rs/WatchEvent
  (-apply-watch [_ state s]
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
  rs/UpdateEvent
  (-apply-update [_ state]
    (update state :message
            (fn [v]
              (if (nil? v)
                (do (set! canceled? true) nil)
                (assoc v :state :hide)))))

  rs/WatchEvent
  (-apply-watch [_ state s]
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
  (p/schedule 0 #(rs/emit! (apply show-error args))))

(defn info!
  [& args]
  (p/schedule 0 #(rs/emit! (apply show-info args))))

(defn dialog!
  [& args]
  (p/schedule 0 #(rs/emit! (apply show-dialog args))))

(defn close!
  []
  (rs/emit! (hide-message)))

(defn error
  [& args]
  (rx/of (apply show-error args)))

(defn info
  [& args]
  (rx/of (apply show-info args)))
