;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.messages
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare hide)
(declare show)

(def default-animation-timeout 600)
(def default-timeout 7000)

(def ^:private
  schema:message
  (sm/define
    [:map {:title "Message"}
     [:type [::sm/one-of #{:success :error :info :warning}]]
     [:status {:optional true}
      [::sm/one-of #{:visible :hide}]]
     [:position {:optional true}
      [::sm/one-of #{:fixed :floating :inline}]]
     [:notification-type {:optional true}
      [::sm/one-of #{:inline :context :toast}]]
     [:controls {:optional true}
      [::sm/one-of #{:none :close :inline-actions :bottom-actions}]]
     [:tag {:optional true}
      [:or :string :keyword]]
     [:timeout {:optional true}
      [:maybe :int]]
     [:actions {:optional true}
      [:vector
       [:map
        [:label :string]
        [:callback ::sm/fn]]]]
     [:links {:optional true}
      [:vector
       [:map
        [:label :string]
        [:callback ::sm/fn]]]]]))

(defn show
  [data]
  (dm/assert!
   "expected valid message map"
   (sm/check! schema:message data))

  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [message (assoc data :status :visible)]
        (assoc state :message message)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (let [stopper (rx/filter (ptk/type? ::hide) stream)]
         (->> stream
              (rx/filter (ptk/type? :app.util.router/navigate))
              (rx/map (constantly hide))
              (rx/take-until stopper)))
       (when (:timeout data)
         (let [stopper (rx/filter (ptk/type? ::show) stream)]
           (->> (rx/of hide)
                (rx/delay (:timeout data))
                (rx/take-until stopper))))))))

(def hide
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :message assoc :status :hide))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::show) stream)]
        (->> (rx/of #(dissoc % :message))
             (rx/delay default-animation-timeout)
             (rx/take-until stopper))))))

(defn hide-tag
  [tag]
  (ptk/reify ::hide-tag
    ptk/WatchEvent
    (watch [_ state _]
      (let [message (get state :message)]
        (when (= (:tag message) tag)
          (rx/of hide))))))

(defn error
  ([content]
   (show {:content content
          :type :error
          :notification-type :toast
          :position :fixed})))

(defn info
  ([content] (info content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :type :info
          :notification-type :toast
          :position :fixed
          :timeout timeout})))

(defn success
  ([content] (success content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :type :success
          :notification-type :toast
          :position :fixed
          :timeout timeout})))

(defn warn
  ([content] (warn content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :type :warning
          :notification-type :toast
          :position :fixed
          :timeout timeout})))

(defn dialog
  [& {:keys [content controls actions position tag type]
      :or {controls :none position :floating type :info}}]
  (show (d/without-nils
         {:content content
          :type type
          :position position
          :controls controls
          :actions actions
          :tag tag})))

(defn info-dialog
  [& {:keys [content controls links actions tag]
      :or {controls :none links nil tag nil}}]
  (show (d/without-nils
         {:content content
          :type :info
          :position :floating
          :notification-type :inline
          :controls controls
          :links links
          :actions actions
          :tag tag})))
