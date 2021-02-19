;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.rpc.mutations.teams
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.media :as media]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.queries.profile :as profile]
   [app.rpc.queries.teams :as teams]
   [app.storage :as sto]
   [app.tasks :as tasks]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)

;; --- Mutation: Create Team

(declare create-team)
(declare create-team-profile)
(declare create-team-default-project)

(s/def ::create-team
  (s/keys :req-un [::profile-id ::name]
          :opt-un [::id]))

(sv/defmethod ::create-team
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (let [team   (create-team conn params)
          params (assoc params :team-id (:id team))]
      (create-team-profile conn params)
      (create-team-default-project conn params)
      team)))

(defn create-team
  [conn {:keys [id name default?] :as params}]
  (let [id (or id (uuid/next))
        default? (if (boolean? default?) default? false)]
    (db/insert! conn :team
                {:id id
                 :name name
                 :is-default default?})))

(defn create-team-profile
  [conn {:keys [team-id profile-id] :as params}]
  (db/insert! conn :team-profile-rel
              {:team-id team-id
               :profile-id profile-id
               :is-owner true
               :is-admin true
               :can-edit true}))

(defn create-team-default-project
  [conn {:keys [team-id profile-id] :as params}]
  (let [proj (projects/create-project conn {:team-id team-id
                                            :name "Drafts"
                                            :default? true})]
    (projects/create-project-profile conn {:project-id (:id proj)
                                           :profile-id profile-id})))


;; --- Mutation: Update Team

(s/def ::update-team
  (s/keys :req-un [::profile-id ::name ::id]))

(sv/defmethod ::update-team
  [{:keys [pool] :as cfg} {:keys [id name profile-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id id)
    (db/update! conn :team
                {:name name}
                {:id id})
    nil))


;; --- Mutation: Leave Team

(s/def ::leave-team
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::leave-team
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms   (teams/check-read-permissions! conn profile-id id)
          members (teams/retrieve-team-members conn id)]

      (when (some :is-owner perms)
        (ex/raise :type :validation
                  :code :owner-cant-leave-team
                  :hint "reasing owner before leave"))

      (when-not (> (count members) 1)
        (ex/raise :type :validation
                  :code :cant-leave-team
                  :context {:members (count members)}))

      (db/delete! conn :team-profile-rel
                  {:profile-id profile-id
                   :team-id id})

      nil)))


;; --- Mutation: Delete Team

(s/def ::delete-team
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::delete-team
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (teams/check-edition-permissions! conn profile-id id)]
      (when-not (some :is-owner perms)
        (ex/raise :type :validation
                  :code :only-owner-can-delete-team))

      ;; Schedule object deletion
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/deletion-delay
                           :props {:id id :type :team}})

      (db/update! conn :team
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))


;; --- Mutation: Tean Update Role

(declare retrieve-team-member)
(declare role->params)

(s/def ::team-id ::us/uuid)
(s/def ::member-id ::us/uuid)
;; Temporarily disabled viewer role
;; https://tree.taiga.io/project/uxboxproject/issue/1083
;; (s/def ::role #{:owner :admin :editor :viewer})
(s/def ::role #{:owner :admin :editor})

(s/def ::update-team-member-role
  (s/keys :req-un [::profile-id ::team-id ::member-id ::role]))

(sv/defmethod ::update-team-member-role
  [{:keys [pool] :as cfg} {:keys [team-id profile-id member-id role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms   (teams/check-read-permissions! conn profile-id team-id)

          ;; We retrieve all team members instead of query the
          ;; database for a single member. This is just for
          ;; convenience, if this bocomes a bottleneck or problematic,
          ;; we will change it to more efficient fetch mechanims.
          members (teams/retrieve-team-members conn team-id)
          member  (d/seek #(= member-id (:id %)) members)]

      ;; If no member is found, just 404
      (when-not member
        (ex/raise :type :not-found
                  :code :member-does-not-exist))

      ;; First check if we have permissions to change roles
      (when-not (or (some :is-owner perms)
                    (some :is-admin perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      ;; Don't allow change role of owner member
      (when (:is-owner member)
        (ex/raise :type :validation
                  :code :cant-change-role-to-owner))

      ;; Don't allow promote to owner to admin users.
      (when (and (= role :owner)
                 (not (:is-owner perms)))
        (ex/raise :type :validation
                  :code :cant-promote-to-owner))

      (let [params (role->params role)]
        ;; Only allow single owner on team
        (when (and (= role :owner)
                   (:is-owner perms))
          (db/update! conn :team-profile-rel
                      {:is-owner false}
                      {:team-id team-id
                       :profile-id profile-id}))

        (db/update! conn :team-profile-rel params
                    {:team-id team-id
                     :profile-id member-id})
        nil))))

(defn role->params
  [role]
  (case role
    :admin  {:is-owner false :is-admin true :can-edit true}
    :editor {:is-owner false :is-admin false :can-edit true}
    :owner  {:is-owner true  :is-admin true :can-edit true}
    :viewer {:is-owner false :is-admin false :can-edit false}))


;; --- Mutation: Team Update Role

(s/def ::delete-team-member
  (s/keys :req-un [::profile-id ::team-id ::member-id]))

(sv/defmethod ::delete-team-member
  [{:keys [pool] :as cfg} {:keys [team-id profile-id member-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (teams/check-read-permissions! conn profile-id team-id)]
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

(declare upload-photo)

(s/def ::file ::media/upload)
(s/def ::update-team-photo
  (s/keys :req-un [::profile-id ::team-id ::file]))

(sv/defmethod ::update-team-photo
  [{:keys [pool storage] :as cfg} {:keys [profile-id file team-id] :as params}]
  (media/validate-media-type (:content-type file))
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (let [team    (teams/retrieve-team conn profile-id team-id)
          _       (media/run cfg {:cmd :info :input {:path (:tempfile file)
                                                     :mtype (:content-type file)}})
          photo   (upload-photo cfg params)]

      ;; Schedule deletion of old photo
      (when-let [id (:photo-id team)]
        (sto/del-object storage id))

      ;; Save new photo
      (db/update! conn :team
              {:photo-id (:id photo)}
              {:id team-id})

      (assoc team :photo-id (:id photo)))))

(defn upload-photo
  [{:keys [storage] :as cfg} {:keys [file]}]
  (let [thumb  (media/run cfg
                 {:cmd :profile-thumbnail
                  :format :jpeg
                  :quality 85
                  :width 256
                  :height 256
                  :input {:path (fs/path (:tempfile file))
                          :mtype (:content-type file)}})]


    (sto/put-object storage
                    {:content (sto/content (:data thumb) (:size thumb))
                     :content-type (:mtype thumb)})))


;; --- Mutation: Invite Member

(s/def ::email ::us/email)
(s/def ::invite-team-member
  (s/keys :req-un [::profile-id ::team-id ::email ::role]))

(sv/defmethod ::invite-team-member
  [{:keys [pool tokens] :as cfg} {:keys [profile-id team-id email role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (teams/check-edition-permissions! conn profile-id team-id)
          profile  (db/get-by-id conn :profile profile-id)
          member   (profile/retrieve-profile-data-by-email conn email)
          team     (db/get-by-id conn :team team-id)
          itoken   (tokens :generate
                           {:iss :team-invitation
                            :exp (dt/in-future "6h")
                            :profile-id (:id profile)
                            :role role
                            :team-id team-id
                            :member-email (:email member email)
                            :member-id (:id member)})
          ptoken   (tokens :generate-predefined
                           {:iss :profile-identity
                            :profile-id (:id profile)})]

      (when-not (some :is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      ;; First check if the current profile is allowed to send emails.
      (when-not (emails/allow-send-emails? conn profile)
        (ex/raise :type :validation
                  :code :profile-is-muted
                  :hint "looks like the profile has reported repeatedly as spam or has permanent bounces"))

      (when (and member (not (emails/allow-send-emails? conn member)))
        (ex/raise :type :validation
                  :code :member-is-muted
                  :hint "looks like the profile has reported repeatedly as spam or has permanent bounces"))

      ;; Secondly check if the invited member email is part of the
      ;; global spam/bounce report.
      (when (emails/has-bounce-reports? conn email)
        (ex/raise :type :validation
                  :code :email-has-permanent-bounces
                  :hint "looks like the email you invite has been repeatedly reported as spam or permanent bounce"))

      (emails/send! conn emails/invite-to-team
                    {:to email
                     :invited-by (:fullname profile)
                     :team (:name team)
                     :token itoken
                     :extra-data ptoken})
      nil)))
