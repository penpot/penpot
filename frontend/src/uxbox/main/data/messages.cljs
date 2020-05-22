;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.messages
  (:require
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [beicon.core :as rx]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.exceptions :as ex]
   [uxbox.config :as cfg]))

(declare hide)
(declare show)

(def +animation-timeout+ 600)

(defn show
  [data]
  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [message (assoc data :status :visible)]
        (assoc state :message message)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? ::show) stream)]
        (->> (rx/of hide)
             (rx/delay (:timeout data))
             (rx/take-until stoper))))))

(def hide
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (update state :message assoc :status :hide))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/of #(dissoc % :message))
           (rx/delay +animation-timeout+)))))


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

(defn success
  [message & {:keys [timeout] :or {timeout 3000}}]
  (show {:content message
         :type :info
         :timeout timeout}))
