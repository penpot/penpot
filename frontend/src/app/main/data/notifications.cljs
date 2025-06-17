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
   [:level {:optional true} [::sm/one-of #{:success :error :info :warning}]]
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
   [:accept {:optional true}
    [:map
     [:label :string]
     [:callback ::sm/fn]]]
   [:cancel {:optional true}
    [:map
     [:label :string]
     [:callback ::sm/fn]]]
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

(def ^:private check-notification
  (sm/check-fn schema:notification))

(defn show
  [data]
  (assert (check-notification data) "expected valid notification map")

  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (let [notification (assoc data :status :visible)]
        (assoc state :notification notification)))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (let [stopper  (rx/filter (ptk/type? ::hide) stream)
             route-id (dm/get-in state [:route :data :name])]

         (->> stream
              (rx/filter (ptk/type? :app.main.router/navigate))
              (rx/map deref)
              (rx/filter #(not= route-id (:id %)))
              (rx/map hide)
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
  [& {:keys [content accept cancel tag links]}]
  (show (d/without-nils
         {:content content
          :type :inline
          :accept accept
          :cancel cancel
          :links links
          :tag tag})))
