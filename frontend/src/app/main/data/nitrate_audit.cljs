;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.nitrate-audit
  (:require
   [app.common.math :as mth]
   [app.common.time :as ct]
   [app.main.data.event :as ev]))

(def ^:private milliseconds-per-day (* 24 60 60 1000))

(defn age-days
  [created-at]
  (when (ct/inst? created-at)
    (-> (ct/diff-ms created-at (ct/now))
        (/ milliseconds-per-day)
        (mth/floor)
        (mth/max 0))))

(defn organization-team-count
  [teams organization-id]
  (->> teams
       (filter #(and (not (:is-default %))
                     (= organization-id
                        (or (:organization-id %)
                            (get-in % [:organization :id])))))
       (count)))

(defn add-team-to-organization-event
  [{:keys [team
           organization-id
           organization-team-count-before
           team-previous-organization-status
           add-method
           subscription-status]}]
  (let [team-created-at (:created-at team)
        event-origin (case add-method
                       "create-team-in-organization"
                       "dashboard:create-team-in-organization"

                       "move-existing-team-to-organization"
                       "dashboard:move-team-to-organization")
        props (cond-> {:is-your-penpot false
                       :add-method add-method
                       :organization-id organization-id
                       :organization-team-count-before organization-team-count-before
                       :team-previous-organization-status team-previous-organization-status
                       :is-first-team-in-organization (zero? organization-team-count-before)
                       :subscription-status subscription-status}
                (:id team)
                (assoc :team-id (:id team))

                (ct/inst? team-created-at)
                (assoc :team-created-at (.toISOString team-created-at)
                       :team-age-days (age-days team-created-at)))]
    (ev/event
     (assoc props
            ::ev/name "add-team-to-organization"
            ::ev/origin event-origin))))

(defn delete-organization-member-event
  [{:keys [organization-id
           user-id
           user-who-delete-member
           deleted-by-role
           member-added-at
           organization-member-count-before
           subscription-status]}]
  (ev/event
   {::ev/name "delete-organization-member"
    :organization-id organization-id
    :user-id user-id
    :user-who-delete-member user-who-delete-member
    :deleted-by-role deleted-by-role
    :days-since-member-added (age-days member-added-at)
    :organization-member-count-before organization-member-count-before
    :subscription-status subscription-status}))
