;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.organization
  (:require
   [app.common.schema :as sm]))

(def schema:organization
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:slug ::sm/text]
   [:owner-id ::sm/uuid]
   [:avatar-bg-url ::sm/uri]
   [:logo-id {:optional true} [:maybe ::sm/uuid]]
   [:expired-license {:optional true} [:maybe :boolean]]
   [:permissions {:optional true}
    [:maybe [:map
             [:create-teams {:optional true} [:maybe [:enum "any" "onlyMe"]]]
             [:delete-teams {:optional true} [:maybe [:enum "onlyMe" "onlyOwners"]]]]]]])


(def schema:team-with-organization
  [:map
   [:id ::sm/uuid]
   [:is-your-penpot :boolean]
   [:organization schema:organization]])

(def organization->team-keys
  "Organization field keys to include in the nested :organization map."
  [:id :name :custom-photo :slug :avatar-bg-url :owner-id :expired-license :permissions])

(defn apply-organization
  "Updates a team map with organization fields in a nested :organization map.
  Associates each org field within :organization when the value is non-nil;
  dissociates the field otherwise. This correctly handles both attaching an org
  (all values present) and detaching one (org is nil or all fields absent)."
  [team organization]
  (let [id (:id organization)]
    (if id
      (assoc team :organization
             (reduce (fn [acc k]
                       (let [v (get organization k)]
                         (if (some? v)
                           (assoc acc k v)
                           (dissoc acc k))))
                     (or (:organization team) {})
                     organization->team-keys))
      (dissoc team :organization))))
