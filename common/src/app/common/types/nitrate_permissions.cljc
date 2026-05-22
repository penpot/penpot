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
   :send-invitations "ownersAndAdmins"})

(defn- can-create-team?
  [{:keys [is-org-owner? permission-value]}]
  (or is-org-owner?
      (= permission-value "any")))

(defn- can-delete-team?
  [{:keys [is-org-owner? permission-value team-perms allow-org-owner-delete?]}]
  (cond
    (= permission-value "onlyMe")
    (and allow-org-owner-delete? is-org-owner?)
    (= permission-value "onlyOwners")
    (boolean (:is-owner team-perms))
    :else false))

(defn- can-move-team?
  [{:keys [permission-value target-org-same-owner?]}]
  (cond
    (= permission-value "never")
    false
    (= permission-value "always")
    true
    (= permission-value "myOrganizations")
    (true? target-org-same-owner?)
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

(def ^:private action-rules
  {:create-team {:permission-key :create-teams
                 :check-fn       can-create-team?}
   :delete-team {:permission-key :delete-teams
                 :check-fn       can-delete-team?}
   :move-team {:permission-key :move-teams
               :check-fn       can-move-team?}
   :send-invitations {:permission-key :send-invitations
                      :check-fn       can-invite-to-team?}})

(defn- normalize-org-permissions
  [org-perms]
  (merge defaults (or (:permissions org-perms) {})))

(defn- owner?
  [org-perms profile-id]
  (= profile-id (:owner-id org-perms)))

(defn allowed?
  "Returns true only for explicitly allowed actions (fail-closed)."
  [action {:keys [org-perms profile-id team-perms allow-org-owner-delete? target-org-same-owner?]}]
  (let [{:keys [permission-key check-fn] :as rule}
        (get action-rules action)
        permissions (normalize-org-permissions org-perms)
        is-org-owner? (owner? org-perms profile-id)
        permission-value (get permissions permission-key)]
    (cond
      (nil? rule) false
      :else (boolean (check-fn {:is-org-owner? is-org-owner?
                                :permission-value permission-value
                                :team-perms team-perms
                                :allow-org-owner-delete? allow-org-owner-delete?
                                :target-org-same-owner? target-org-same-owner?})))))

(defn can-send-invitations?
  [{:keys [nitrate-enabled? organization profile-id team-permissions]}]
  (let [in-org? (and nitrate-enabled? organization)]
    (if in-org?
      (allowed? :send-invitations
                {:org-perms {:owner-id    (:owner-id organization)
                             :permissions (:permissions organization)}
                 :profile-id profile-id
                 :team-perms team-permissions})
      (or (boolean (:is-owner team-permissions))
          (boolean (:is-admin team-permissions))))))
