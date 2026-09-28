;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.organization
  (:require
   [app.common.flags :as flags]
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
   [:sso-active {:optional true} [:maybe :boolean]]
   [:permissions {:optional true}
    [:maybe [:map
             [:create-teams {:optional true} [:maybe [:enum "any" "onlyMe"]]]
             [:delete-teams {:optional true} [:maybe [:enum "onlyMe" "onlyOwners"]]]
             [:move-teams {:optional true} [:maybe [:enum "always" "myOrganizations" "never"]]]
             [:new-team-members {:optional true} [:maybe [:enum "anyone" "members"]]]]]]])


(def schema:team-with-organization
  [:map
   [:id ::sm/uuid]
   [:is-your-penpot :boolean]
   [:organization schema:organization]])

(def organization->team-keys
  "Organization field keys to include in the nested :organization map."
  [:id :name :custom-photo :slug :avatar-bg-url :owner-id :expired-license :permissions :sso-active])

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


(def schema:organization-with-avatar
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:initials [:maybe :string]]
   [:logo [:maybe ::sm/uri]]
   [:avatar-bg-url [:maybe ::sm/uri]]
   [:sso-active {:optional true} [:maybe :boolean]]])

(def schema:nitrate-sso
  [:map {:title "NitrateOrganizationSso"}
   [:organization-id ::sm/uuid]
   [:active {:optional true} [:maybe :boolean]]
   [:provider {:optional true} [:maybe :string]]
   [:client-id {:optional true} [:maybe :string]]
   [:client-secret {:optional true} [:maybe :string]]
   [:issuer {:optional true} [:maybe :string]]])


;; --- Organization permission rules ---

(def ^:private defaults
  {:create-teams "any"
   :delete-teams "onlyOwners"
   :move-teams "always"
   :send-invitations "ownersAndAdmins"
   :new-team-members "anyone"})

(defn- can-create-team?
  [{:keys [is-organization-owner? permission-value]}]
  (or is-organization-owner?
      (= permission-value "any")))

(defn- can-delete-team?
  [{:keys [is-organization-owner? permission-value team-perms]}]
  (cond
    ;; Organization owners can always delete teams inside their organizations.
    is-organization-owner?
    true
    (= permission-value "onlyOwners")
    (boolean (:is-owner team-perms))
    :else false))

(defn- can-move-team?
  [{:keys [permission-value target-organization-same-owner?]}]
  (cond
    (= permission-value "never")
    false
    (= permission-value "always")
    true
    (= permission-value "myOrganizations")
    (true? target-organization-same-owner?)
    :else false))

(defn- can-invite-to-team?
  [{:keys [permission-value team-perms]}]
  (cond
    (= permission-value "ownersAndAdmins")
    (or (boolean (:is-owner team-perms))
        (boolean (:is-admin team-perms)))

    (= permission-value "owners")
    (boolean (:is-owner team-perms))

    :else false))

(defn- can-add-anybody-to-team?
  [{:keys [permission-value]}]
  (= permission-value "anyone"))

(def ^:private action-rules
  {:create-team          {:permission-key :create-teams
                          :check-fn       can-create-team?}
   :delete-team          {:permission-key :delete-teams
                          :check-fn       can-delete-team?}
   :move-team            {:permission-key :move-teams
                          :check-fn       can-move-team?}
   :send-invitations     {:permission-key :send-invitations
                          :check-fn        can-invite-to-team?}
   :add-anybody-to-team  {:permission-key :new-team-members
                          :check-fn       can-add-anybody-to-team?}})

(defn- normalize-organization-permissions
  [organization-perms]
  (merge defaults (or (:permissions organization-perms) {})))

(defn- owner?
  [organization-perms profile-id]
  (= profile-id (:owner-id organization-perms)))

(defn allowed?
  "Returns true only for explicitly allowed actions (fail-closed)."
  [action {:keys [organization-perms profile-id team-perms target-organization-same-owner?]}]
  (let [{:keys [permission-key check-fn] :as rule}
        (get action-rules action)
        permissions (normalize-organization-permissions organization-perms)
        is-organization-owner? (owner? organization-perms profile-id)
        permission-value (get permissions permission-key)]
    (cond
      (nil? rule) false
      :else (boolean (check-fn {:is-organization-owner? is-organization-owner?
                                :permission-value permission-value
                                :team-perms team-perms
                                :target-organization-same-owner? target-organization-same-owner?})))))

(defn can-send-invitations?
  "Returns true when the user can send invitations to a team.
  Falls back to team-level permissions (owner/admin) when the
  admin-console flag is off or the team has no organization."
  [{:keys [organization profile-id team-permissions]}]
  (if (and (contains? flags/*current* :admin-console) organization)
    (allowed? :send-invitations
              {:organization-perms {:owner-id    (:owner-id organization)
                                    :permissions (:permissions organization)}
               :profile-id profile-id
               :team-perms team-permissions})
    (or (boolean (:is-owner team-permissions))
        (boolean (:is-admin team-permissions)))))
