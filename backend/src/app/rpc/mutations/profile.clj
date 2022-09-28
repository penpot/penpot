;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.profile
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.emails :as eml]
   [app.loggers.audit :as audit]
   [app.media :as media]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.doc :as-alias doc]
   [app.rpc.mutations.teams :as teams]
   [app.rpc.queries.profile :as profile]
   [app.rpc.semaphore :as rsem]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [promesa.exec :as px]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/not-empty-string)
(s/def ::lang ::us/string)
(s/def ::path ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::password ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)
(s/def ::theme ::us/string)

;; --- MUTATION: Update Profile (own)

(s/def ::newsletter-subscribed ::us/boolean)
(s/def ::update-profile
  (s/keys :req-un [::fullname ::profile-id]
          :opt-un [::lang ::theme ::newsletter-subscribed]))

(sv/defmethod ::update-profile
  [{:keys [pool] :as cfg} {:keys [profile-id fullname lang theme newsletter-subscribed] :as params}]
  (db/with-atomic [conn pool]
    ;; NOTE: we need to retrieve the profile independently if we use
    ;; it or not for explicit locking and avoid concurrent updates of
    ;; the same row/object.
    (let [profile (-> (db/get-by-id conn :profile profile-id {:for-update true})
                      (profile/decode-profile-row))

          ;; Update the profile map with direct params
          profile (-> profile
                      (assoc :fullname fullname)
                      (assoc :lang lang)
                      (assoc :theme theme))

          ;; Update profile props if the indirect prop is coming in
          ;; the params map and update the profile props data
          ;; acordingly.
          profile (cond-> profile
                    (some? newsletter-subscribed)
                    (update :props assoc :newsletter-subscribed newsletter-subscribed))]

      (db/update! conn :profile
                  {:fullname fullname
                   :lang lang
                   :theme theme
                   :props (db/tjson (:props profile))}
                  {:id profile-id})

      (with-meta (-> profile profile/strip-private-attrs d/without-nils)
        {::audit/props (audit/profile->props profile)}))))

;; --- MUTATION: Update Password

(declare validate-password!)
(declare update-profile-password!)
(declare invalidate-profile-session!)

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password ::old-password]))

(sv/defmethod ::update-profile-password
  {::rsem/queue :auth}
  [{:keys [pool] :as cfg} {:keys [password] :as params}]
  (db/with-atomic [conn pool]
    (let [profile    (validate-password! conn params)
          session-id (:app.rpc/session-id params)]
      (when (= (str/lower (:email profile))
               (str/lower (:password params)))
        (ex/raise :type :validation
                  :code :email-as-password
                  :hint "you can't use your email as password"))
      (update-profile-password! conn (assoc profile :password password))
      (invalidate-profile-session! conn (:id profile) session-id)
      nil)))

(defn- invalidate-profile-session!
  "Removes all sessions except the current one."
  [conn profile-id session-id]
  (let [sql "delete from http_session where profile_id = ? and id != ?"]
    (:next.jdbc/update-count (db/exec-one! conn [sql profile-id session-id]))))

(defn- validate-password!
  [conn {:keys [profile-id old-password] :as params}]
  (let [profile (db/get-by-id conn :profile profile-id)]
    (when-not (:valid (cmd.auth/verify-password old-password (:password profile)))
      (ex/raise :type :validation
                :code :old-password-not-match))
    profile))

(defn update-profile-password!
  [conn {:keys [id password] :as profile}]
  (db/update! conn :profile
              {:password (cmd.auth/derive-password password)}
              {:id id}))

;; --- MUTATION: Update Photo

(declare update-profile-photo)

(s/def ::file ::media/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::profile-id ::file]))

(sv/defmethod ::update-profile-photo
  [cfg {:keys [file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (let [cfg (update cfg :storage media/configure-assets-storage)]
    (update-profile-photo cfg params)))

(defn update-profile-photo
  [{:keys [pool storage executor] :as cfg} {:keys [profile-id] :as params}]
  (p/let [profile (px/with-dispatch executor
                    (db/get-by-id pool :profile profile-id))
          photo   (teams/upload-photo cfg params)]

    ;; Schedule deletion of old photo
    (when-let [id (:photo-id profile)]
      (sto/touch-object! storage id))

    ;; Save new photo
    (db/update! pool :profile
                {:photo-id (:id photo)}
                {:id profile-id})
    nil))

;; --- MUTATION: Request Email Change

(declare request-email-change)
(declare change-email-immediately)

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sv/defmethod ::request-email-change
  [{:keys [pool] :as cfg} {:keys [profile-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id)
          cfg     (assoc cfg :conn conn)
          params  (assoc params
                         :profile profile
                         :email (str/lower email))]
      (if (contains? cf/flags :smtp)
        (request-email-change cfg params)
        (change-email-immediately cfg params)))))

(defn- change-email-immediately
  [{:keys [conn]} {:keys [profile email] :as params}]
  (when (not= email (:email profile))
    (cmd.auth/check-profile-existence! conn params))
  (db/update! conn :profile
              {:email email}
              {:id (:id profile)})
  {:changed true})

(defn- request-email-change
  [{:keys [conn sprops] :as cfg} {:keys [profile email] :as params}]
  (let [token   (tokens/generate sprops
                                 {:iss :change-email
                                  :exp (dt/in-future "15m")
                                  :profile-id (:id profile)
                                  :email email})
        ptoken  (tokens/generate sprops
                                 {:iss :profile-identity
                                  :profile-id (:id profile)
                                  :exp (dt/in-future {:days 30})})]

    (when (not= email (:email profile))
      (cmd.auth/check-profile-existence! conn params))

    (when-not (eml/allow-send-emails? conn profile)
      (ex/raise :type :validation
                :code :profile-is-muted
                :hint "looks like the profile has reported repeatedly as spam or has permanent bounces."))

    (when (eml/has-bounce-reports? conn email)
      (ex/raise :type :validation
                :code :email-has-permanent-bounces
                :hint "looks like the email you invite has been repeatedly reported as spam or permanent bounce"))

    (eml/send! {::eml/conn conn
                ::eml/factory eml/change-email
                :public-uri (:public-uri cfg)
                :to (:email profile)
                :name (:fullname profile)
                :pending-email email
                :token token
                :extra-data ptoken})
    nil))


(defn select-profile-for-update
  [conn id]
  (db/get-by-id conn :profile id {:for-update true}))


;; --- MUTATION: Update Profile Props

(s/def ::props map?)
(s/def ::update-profile-props
  (s/keys :req-un [::profile-id ::props]))

(sv/defmethod ::update-profile-props
  [{:keys [pool] :as cfg} {:keys [profile-id props]}]
  (db/with-atomic [conn pool]
    (let [profile (profile/retrieve-profile-data conn profile-id)
          props   (reduce-kv (fn [props k v]
                               ;; We don't accept namespaced keys
                               (if (simple-ident? k)
                                 (if (nil? v)
                                   (dissoc props k)
                                   (assoc props k v))
                                 props))
                             (:props profile)
                             props)]

      (db/update! conn :profile
                  {:props (db/tjson props)}
                  {:id profile-id})

      (profile/filter-profile-props props))))


;; --- MUTATION: Delete Profile

(declare get-owned-teams-with-participants)
(declare check-can-delete-profile!)
(declare mark-profile-as-deleted!)

(s/def ::delete-profile
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::delete-profile
  [{:keys [pool session] :as cfg} {:keys [profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [teams      (get-owned-teams-with-participants conn profile-id)
          deleted-at (dt/now)]

      ;; If we found owned teams with participants, we don't allow
      ;; delete profile until the user properly transfer ownership or
      ;; explicitly removes all participants from the team
      (when (some pos? (map :participants teams))
        (ex/raise :type :validation
                  :code :owner-teams-with-people
                  :hint "The user need to transfer ownership of owned teams."
                  :context {:teams (mapv :id teams)}))

      (doseq [{:keys [id]} teams]
        (db/update! conn :team
                    {:deleted-at deleted-at}
                    {:id id}))

      (db/update! conn :profile
                  {:deleted-at deleted-at}
                  {:id profile-id})

      (with-meta {}
        {:transform-response (:delete session)}))))

(def sql:owned-teams
  "with owner_teams as (
      select tpr.team_id as id
        from team_profile_rel as tpr
       where tpr.is_owner is true
         and tpr.profile_id = ?
   )
   select tpr.team_id as id,
          count(tpr.profile_id) - 1 as participants
     from team_profile_rel as tpr
    where tpr.team_id in (select id from owner_teams)
      and tpr.profile_id != ?
    group by 1")

(defn- get-owned-teams-with-participants
  [conn profile-id]
  (db/exec! conn [sql:owned-teams profile-id profile-id]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEPRECATED METHODS (TO BE REMOVED ON 1.16.x)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- MUTATION: Login

(s/def ::login ::cmd.auth/login-with-password)

(sv/defmethod ::login
  {:auth false
   ::rsem/queue :auth
   ::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [cfg params]
  (cmd.auth/login-with-password cfg params))

;; --- MUTATION: Logout

(s/def ::logout ::cmd.auth/logout)

(sv/defmethod ::logout
  {:auth false
   ::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [session] :as cfg} _]
  (with-meta {}
    {:transform-response (:delete session)}))

;; --- MUTATION: Recover Profile

(s/def ::recover-profile ::cmd.auth/recover-profile)

(sv/defmethod ::recover-profile
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [cfg params]
  (cmd.auth/recover-profile cfg params))

;; --- MUTATION: Prepare Register

(s/def ::prepare-register-profile ::cmd.auth/prepare-register-profile)

(sv/defmethod ::prepare-register-profile
  {:auth false
   ::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [cfg params]
  (cmd.auth/prepare-register cfg params))

;; --- MUTATION: Register Profile

(s/def ::register-profile ::cmd.auth/register-profile)

(sv/defmethod ::register-profile
  {:auth false
   ::rsem/queue :auth
   ::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (-> (assoc cfg :conn conn)
        (cmd.auth/register-profile params))))

;; --- MUTATION: Request Profile Recovery

(s/def ::request-profile-recovery ::cmd.auth/request-profile-recovery)

(sv/defmethod ::request-profile-recovery
  {:auth false
   ::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [cfg params]
  (cmd.auth/request-profile-recovery cfg params))
