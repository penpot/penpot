;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.messages
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.config :as cfg]))

(declare hide)
(declare show)

(def +animation-timeout+ 600)

(s/def ::message-type #{:success :error :info :warning})
(s/def ::message-position #{:fixed :floating :inline})
(s/def ::message-status #{:visible :hide})
(s/def ::message-controls #{:none :close :inline-actions :bottom-actions})
(s/def ::message-tag string?)
(s/def ::label string?)
(s/def ::callback fn?)
(s/def ::message-action (s/keys :req-un [::label ::callback]))
(s/def ::message-actions (s/nilable (s/coll-of ::message-action :kind vector?)))

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
      (d/update-when state :message assoc :status :hide))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter (ptk/type? ::show) stream)]
        (->> (rx/of #(dissoc % :message))
             (rx/delay +animation-timeout+)
             (rx/take-until stoper))))))

(defn hide-tag
  [tag]
  (ptk/reify ::hide-tag
    ptk/WatchEvent
    (watch [_ state stream]
      (let [message (get state :message)]
        (when (= (:tag message) tag)
          (rx/of hide))))))

(defn error
  ([content] (error content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :error
          :position :fixed
          :timeout timeout})))

(defn info
  ([content] (info content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :info
          :position :fixed
          :timeout timeout})))

(defn success
  ([content] (success content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :success
          :position :fixed
          :timeout timeout})))

(defn warn
  ([content] (warn content {}))
  ([content {:keys [timeout] :or {timeout 3000}}]
   (show {:content content
          :type :warning
          :position :fixed
          :timeout timeout})))

(defn info-dialog
  ([content controls actions]
   (info-dialog content controls actions nil))
  ([content controls actions tag]
   (show {:content content
          :type :info
          :position :floating
          :controls controls
          :actions actions
          :tag tag})))

