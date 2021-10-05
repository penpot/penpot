;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.messages
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(declare hide)
(declare show)

(def default-animation-timeout 600)
(def default-timeout 5000)

(s/def ::type #{:success :error :info :warning})
(s/def ::position #{:fixed :floating :inline})
(s/def ::status #{:visible :hide})
(s/def ::controls #{:none :close :inline-actions :bottom-actions})

(s/def ::tag (s/or :str ::us/string :kw ::us/keyword))
(s/def ::label ::us/string)
(s/def ::callback fn?)
(s/def ::action (s/keys :req-un [::label ::callback]))
(s/def ::actions (s/every ::action :kind vector?))
(s/def ::timeout (s/nilable ::us/integer))
(s/def ::content ::us/string)

(s/def ::message
  (s/keys :req-un [::type]
          :opt-un [::status
                   ::position
                   ::controls
                   ::tag
                   ::timeout
                   ::actions
                   ::status]))

(defn show
  [data]
  (us/verify ::message data)
  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [message (assoc data :status :visible)]
        (assoc state :message message)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
        (let [stoper (rx/filter (ptk/type? ::hide) stream)]
          (->> stream
               (rx/filter (ptk/type? :app.util.router/navigate))
               (rx/map (constantly hide))
               (rx/take-until stoper)))
        (when (:timeout data)
          (let [stoper (rx/filter (ptk/type? ::show) stream)]
            (->> (rx/of hide)
                 (rx/delay (:timeout data))
                 (rx/take-until stoper))))))))

(def hide
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :message assoc :status :hide))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stoper (rx/filter (ptk/type? ::show) stream)]
        (->> (rx/of #(dissoc % :message))
             (rx/delay default-animation-timeout)
             (rx/take-until stoper))))))

(defn hide-tag
  [tag]
  (ptk/reify ::hide-tag
    ptk/WatchEvent
    (watch [_ state _]
      (let [message (get state :message)]
        (when (= (:tag message) tag)
          (rx/of hide))))))

(defn error
  ([content] (error content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :type :error
          :position :fixed
          :timeout timeout})))

(defn info
  ([content] (info content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :type :info
          :position :fixed
          :timeout timeout})))

(defn success
  ([content] (success content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :type :success
          :position :fixed
          :timeout timeout})))

(defn warn
  ([content] (warn content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
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
