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
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
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
      (when (:timeout data)
        (let [stoper (rx/filter (ptk/type? ::show) stream)]
          (->> (rx/of hide)
               (rx/delay (:timeout data))
               (rx/take-until stoper)))))))

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
  ([content] (error content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :error
          :timeout timeout})))

(defn info
  ([content] (info content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :info
          :timeout timeout})))

(defn success
  ([content] (success content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :success
          :timeout timeout})))

(defn warn
  ([content] (warn content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :warning
          :timeout timeout})))
