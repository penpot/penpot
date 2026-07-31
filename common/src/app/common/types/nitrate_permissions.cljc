;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.nitrate-permissions)

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
  [{:keys [nitrate-enabled? organization profile-id team-permissions]}]
  (let [in-organization? (and nitrate-enabled? organization)]
    (if in-organization?
      (allowed? :send-invitations
                {:organization-perms {:owner-id    (:owner-id organization)
                                      :permissions (:permissions organization)}
                 :profile-id profile-id
                 :team-perms team-permissions})
      (or (boolean (:is-owner team-permissions))
          (boolean (:is-admin team-permissions))))))
