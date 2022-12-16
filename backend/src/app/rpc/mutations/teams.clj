;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.teams
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.emails :as eml]
   [app.loggers.audit :as audit]
   [app.media :as media]
   [app.rpc.commands.teams :as cmd.teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)

;; --- Mutation: Create Team

(s/def ::create-team ::cmd.teams/create-team)

(sv/defmethod ::create-team
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.teams/create-team conn params)))

;; --- Mutation: Update Team

(s/def ::update-team ::cmd.teams/update-team)

(sv/defmethod ::update-team
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [id name profile-id] :as params}]
  (db/with-atomic [conn pool]
    (cmd.teams/check-edition-permissions! conn profile-id id)
    (db/update! conn :team
                {:name name}
                {:id id})
    nil))

;; --- Mutation: Leave Team

(s/def ::leave-team ::cmd.teams/leave-team)

(sv/defmethod ::leave-team
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.teams/leave-team conn params)))

;; --- Mutation: Delete Team

(s/def ::delete-team ::cmd.teams/delete-team)

(sv/defmethod ::delete-team
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (cmd.teams/get-permissions conn profile-id id)]
      (when-not (:is-owner perms)
        (ex/raise :type :validation
                  :code :only-owner-can-delete-team))
      (db/update! conn :team
                  {:deleted-at (dt/now)}
                  {:id id :is-default false})
      nil)))


;; --- Mutation: Team Update Role

(s/def ::update-team-member-role ::cmd.teams/update-team-member-role)

(sv/defmethod ::update-team-member-role
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.teams/update-team-member-role conn params)))

;; --- Mutation: Delete Team Member

(s/def ::delete-team-member ::cmd.teams/delete-team-member)

(sv/defmethod ::delete-team-member
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [team-id profile-id member-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (cmd.teams/get-permissions conn profile-id team-id)]
      (when-not (or (:is-owner perms)
                    (:is-admin perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))
      (when (= member-id profile-id)
        (ex/raise :type :validation
                  :code :cant-remove-yourself))

      (db/delete! conn :team-profile-rel {:profile-id member-id
                                          :team-id team-id})

      nil)))

;; --- Mutation: Update Team Photo

(s/def ::update-team-photo ::cmd.teams/update-team-photo)

(sv/defmethod ::update-team-photo
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [cfg {:keys [file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (let [cfg (update cfg :storage media/configure-assets-storage)]
    (cmd.teams/update-team-photo cfg params)))

;; --- Mutation: Invite Member

(s/def ::invite-team-member ::cmd.teams/create-team-invitations)

(sv/defmethod ::invite-team-member
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id team-id email emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (cmd.teams/get-permissions conn profile-id team-id)
          profile  (db/get-by-id conn :profile profile-id)
          team     (db/get-by-id conn :team team-id)
          emails   (cond-> (or emails #{}) (string? email) (conj email))]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      ;; First check if the current profile is allowed to send emails.
      (when-not (eml/allow-send-emails? conn profile)
        (ex/raise :type :validation
                  :code :profile-is-muted
                  :hint "looks like the profile has reported repeatedly as spam or has permanent bounces"))

      (let [cfg         (assoc cfg ::cmd.teams/conn conn)
            invitations (->> emails
                             (map (fn [email]
                                    {:email (str/lower email)
                                     :team team
                                     :profile profile
                                     :role role}))
                             (map (partial #'cmd.teams/create-invitation cfg)))]
        (with-meta (vec invitations)
          {::audit/props {:invitations (count invitations)}})))))

;; --- Mutation: Create Team & Invite Members

(s/def ::create-team-and-invite-members ::cmd.teams/create-team-with-invitations)

(sv/defmethod ::create-team-and-invite-members
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [team     (cmd.teams/create-team conn params)
          profile  (db/get-by-id conn :profile profile-id)
          cfg      (assoc cfg ::cmd.teams/conn conn)]

      ;; Create invitations for all provided emails.
      (->> emails
           (map (fn [email]
                  {:team team
                   :profile profile
                   :email (str/lower email)
                   :role role}))
           (run! (partial #'cmd.teams/create-invitation cfg)))

      (-> team
          (vary-meta assoc ::audit/props {:invitations (count emails)})
          (rph/with-defer
            #(when-let [collector (::audit/collector cfg)]
               (audit/submit! collector
                              {:type "mutation"
                               :name "invite-team-member"
                               :profile-id profile-id
                               :props {:emails emails
                                       :role role
                                       :profile-id profile-id
                                       :invitations (count emails)}})))))))

;; --- Mutation: Update invitation role

(s/def ::update-team-invitation-role
  (s/keys :req-un [::profile-id ::team-id ::email ::role]))

(sv/defmethod ::update-team-invitation-role
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id team-id email role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (cmd.teams/get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (db/update! conn :team-invitation
                  {:role (name role) :updated-at (dt/now)}
                  {:team-id team-id :email-to (str/lower email)})
      nil)))

;; --- Mutation: Delete invitation

(s/def ::delete-team-invitation ::cmd.teams/delete-team-invitation)

(sv/defmethod ::delete-team-invitation
  {::doc/added "1.0"
   ::doc/deprecated "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id team-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (cmd.teams/get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (db/delete! conn :team-invitation
                {:team-id team-id :email-to (str/lower email)})
      nil)))
