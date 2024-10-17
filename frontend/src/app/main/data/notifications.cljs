;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.notifications
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare hide)
(declare show)

(def default-timeout 7000)

(def ^:private schema:notification
  [:map {:title "Notification"}
   [:level [::sm/one-of #{:success :error :info :warning}]]
   [:status {:optional true}
    [::sm/one-of #{:visible :hide}]]
   [:position {:optional true}
    [::sm/one-of #{:fixed :floating :inline}]]
   [:type {:optional true}
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
      [:callback ::sm/fn]]]]])

(def ^:private valid-notification?
  (sm/validator schema:notification))

(defn show
  [data]

  (dm/assert!
   "expected valid notification map"
   (valid-notification? data))

  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [notification (assoc data :status :visible)]
        (assoc state :notification notification)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (let [stopper (rx/filter (ptk/type? ::hide) stream)]
         (->> stream
              (rx/filter (ptk/type? :app.util.router/navigate))
              (rx/map (fn [_] (hide)))
              (rx/take-until stopper)))
       (when (:timeout data)
         (let [stopper (rx/filter (ptk/type? ::show) stream)]
           (->> (rx/of (hide))
                (rx/delay (:timeout data))
                (rx/take-until stopper))))))))

(defn hide
  [& {:keys [tag]}]
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (if (some? tag)
        (let [notification (get state :notification)]
          (if (= tag (:tag notification))
            (dissoc state :notification)
            state))
        (dissoc state :notification)))))

(defn error
  ([content]
   (show {:content content
          :level :error
          :type :toast
          :position :fixed})))

(defn info
  ([content] (info content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :level :info
          :type :toast
          :position :fixed
          :timeout timeout})))

(defn success
  ([content] (success content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :level :success
          :type :toast
          :position :fixed
          :timeout timeout})))

(defn warn
  ([content] (warn content {}))
  ([content {:keys [timeout] :or {timeout default-timeout}}]
   (show {:content content
          :level :warning
          :type :toast
          :position :fixed
          :timeout timeout})))

(defn dialog
  [& {:keys [content controls actions position tag level links]
      :or {controls :none position :floating level :info}}]
  (show (d/without-nils
         {:content content
          :level level
          :links links
          :position position
          :controls controls
          :actions actions
          :tag tag})))
