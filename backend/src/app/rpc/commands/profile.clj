;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.profile
  (:require
   [app.auth :as auth]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.email :as eml]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.setup :as-alias setup]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [cuerdas.core :as str]
   [promesa.exec :as px]))

(declare check-profile-existence!)
(declare decode-row)
(declare derive-password)
(declare filter-props)
(declare get-profile)
(declare strip-private-attrs)
(declare verify-password)

(defn clean-email
  "Clean and normalizes email address string"
  [email]
  (let [email (str/lower email)
        email (if (str/starts-with? email "mailto:")
                (subs email 7)
                email)]
    email))

(def ^:private
  schema:profile
  (sm/define
    [:map {:title "Profile"}
     [:id ::sm/uuid]
     [:fullname [::sm/word-string {:max 250}]]
     [:email ::sm/email]
     [:is-active {:optional true} :boolean]
     [:is-blocked {:optional true} :boolean]
     [:is-demo {:optional true} :boolean]
     [:is-muted {:optional true} :boolean]
     [:created-at {:optional true} ::sm/inst]
     [:modified-at {:optional true} ::sm/inst]
     [:default-project-id {:optional true} ::sm/uuid]
     [:default-team-id {:optional true} ::sm/uuid]
     [:props {:optional true}
      [:map-of {:title "ProfileProps"} :keyword :any]]]))

;; --- QUERY: Get profile (own)

(sv/defmethod ::get-profile
  {::rpc/auth false
   ::doc/added "1.18"
   ::sm/params [:map]
   ::sm/result schema:profile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id]}]
  ;; We need to return the anonymous profile object in two cases, when
  ;; no profile-id is in session, and when db call raises not found. In all other
  ;; cases we need to reraise the exception.
  (try
    (-> (get-profile pool profile-id)
        (strip-private-attrs)
        (update :props filter-props))
    (catch Throwable _
      {:id uuid/zero :fullname "Anonymous User"})))

(defn get-profile
  "Get profile by id. Throws not-found exception if no profile found."
  [conn id & {:as opts}]
  (-> (db/get-by-id conn :profile id opts)
      (decode-row)))

;; --- MUTATION: Update Profile (own)

(def ^:private
  schema:update-profile
  (sm/define
    [:map {:title "update-profile"}
     [:fullname [::sm/word-string {:max 250}]]
     [:lang {:optional true} [:string {:max 5}]]
     [:theme {:optional true} [:string {:max 250}]]]))

(sv/defmethod ::update-profile
  {::doc/added "1.0"
   ::sm/params schema:update-profile
   ::sm/result schema:profile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id fullname lang theme] :as params}]

  (db/with-atomic [conn pool]
    ;; NOTE: we need to retrieve the profile independently if we use
    ;; it or not for explicit locking and avoid concurrent updates of
    ;; the same row/object.
    (let [profile (-> (db/get-by-id conn :profile profile-id ::sql/for-update true)
                      (decode-row))

          ;; Update the profile map with direct params
          profile (-> profile
                      (assoc :fullname fullname)
                      (assoc :lang lang)
                      (assoc :theme theme))]

      (db/update! conn :profile
                  {:fullname fullname
                   :lang lang
                   :theme theme
                   :props (db/tjson (:props profile))}
                  {:id profile-id})

      (-> profile
          (strip-private-attrs)
          (d/without-nils)
          (rph/with-meta {::audit/props (audit/profile->props profile)})))))


;; --- MUTATION: Update Password

(declare validate-password!)
(declare update-profile-password!)
(declare invalidate-profile-session!)

(def ^:private
  schema:update-profile-password
  (sm/define
    [:map {:title "update-profile-password"}
     [:password [::sm/word-string {:max 500}]]
     ;; Social registered users don't have old-password
     [:old-password {:optional true} [:maybe [::sm/word-string {:max 500}]]]]))

(sv/defmethod ::update-profile-password
  {::doc/added "1.0"
   ::sm/params schema:update-profile-password
   ::climit/id :auth/global}
  [cfg {:keys [::rpc/profile-id password] :as params}]

  (db/tx-run! cfg (fn [cfg]
                    (let [profile    (validate-password! cfg (assoc params :profile-id profile-id))
                          session-id (::session/id params)]

                      (when (= (:email profile) (str/lower (:password params)))
                        (ex/raise :type :validation
                                  :code :email-as-password
                                  :hint "you can't use your email as password"))

                      (update-profile-password! cfg (assoc profile :password password))
                      (invalidate-profile-session! cfg profile-id session-id)
                      nil))))

(defn- invalidate-profile-session!
  "Removes all sessions except the current one."
  [{:keys [::db/conn]} profile-id session-id]
  (let [sql "delete from http_session where profile_id = ? and id != ?"]
    (:next.jdbc/update-count (db/exec-one! conn [sql profile-id session-id]))))

(defn- validate-password!
  [{:keys [::db/conn] :as cfg} {:keys [profile-id old-password] :as params}]
  (let [profile (db/get-by-id conn :profile profile-id ::sql/for-update true)]
    (when (and (not= (:password profile) "!")
               (not (:valid (verify-password cfg old-password (:password profile)))))
      (ex/raise :type :validation
                :code :old-password-not-match))
    profile))

(defn update-profile-password!
  [{:keys [::db/conn] :as cfg} {:keys [id password] :as profile}]
  (when-not (db/read-only? conn)
    (db/update! conn :profile
                {:password (derive-password cfg password)}
                {:id id})
    nil))

;; --- MUTATION: Update Photo

(declare upload-photo)
(declare update-profile-photo)

(def ^:private
  schema:update-profile-photo
  (sm/define
    [:map {:title "update-profile-photo"}
     [:file ::media/upload]]))

(sv/defmethod ::update-profile-photo
  {:doc/added "1.1"
   ::sm/params schema:update-profile-photo
   ::sm/result :nil}
  [cfg {:keys [::rpc/profile-id file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (update-profile-photo cfg (assoc params :profile-id profile-id))))

(defn update-profile-photo
  [{:keys [::db/pool ::sto/storage] :as cfg} {:keys [profile-id file] :as params}]

  (let [photo   (upload-photo cfg params)
        profile (db/get-by-id pool :profile profile-id ::sql/for-update true)]

    ;; Schedule deletion of old photo
    (when-let [id (:photo-id profile)]
      (sto/touch-object! storage id))

    ;; Save new photo
    (db/update! pool :profile
                {:photo-id (:id photo)}
                {:id profile-id})

    (-> (rph/wrap)
        (rph/with-meta {::audit/replace-props
                        {:file-name (:filename file)
                         :file-size (:size file)
                         :file-path (str (:path file))
                         :file-mtype (:mtype file)}}))))

(defn- generate-thumbnail!
  [file]
  (let [input   (media/run {:cmd :info :input file})
        thumb   (media/run {:cmd :profile-thumbnail
                            :format :jpeg
                            :quality 85
                            :width 256
                            :height 256
                            :input input})
        hash    (sto/calculate-hash (:data thumb))
        content (-> (sto/content (:data thumb) (:size thumb))
                    (sto/wrap-with-hash hash))]
    {::sto/content content
     ::sto/deduplicate? true
     :bucket "profile"
     :content-type (:mtype thumb)}))

(defn upload-photo
  [{:keys [::sto/storage ::wrk/executor] :as cfg} {:keys [file]}]
  (let [params (-> cfg
                   (assoc ::climit/id :process-image/global)
                   (assoc ::climit/label "upload-photo")
                   (assoc ::climit/executor executor)
                   (climit/invoke! generate-thumbnail! file))]
    (sto/put-object! storage params)))


;; --- MUTATION: Request Email Change

(declare ^:private request-email-change!)
(declare ^:private change-email-immediately!)

(def ^:private
  schema:request-email-change
  (sm/define
    [:map {:title "request-email-change"}
     [:email ::sm/email]]))

(sv/defmethod ::request-email-change
  {::doc/added "1.0"
   ::sm/params schema:request-email-change}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id)
          cfg     (assoc cfg ::conn conn)
          params  (assoc params
                         :profile profile
                         :email (clean-email email))]
      (if (contains? cf/flags :smtp)
        (request-email-change! cfg params)
        (change-email-immediately! cfg params)))))

(defn- change-email-immediately!
  [{:keys [::conn]} {:keys [profile email] :as params}]
  (when (not= email (:email profile))
    (check-profile-existence! conn params))

  (db/update! conn :profile
              {:email email}
              {:id (:id profile)})

  {:changed true})

(defn- request-email-change!
  [{:keys [::conn] :as cfg} {:keys [profile email] :as params}]
  (let [token   (tokens/generate (::setup/props cfg)
                                 {:iss :change-email
                                  :exp (dt/in-future "15m")
                                  :profile-id (:id profile)
                                  :email email})
        ptoken  (tokens/generate (::setup/props cfg)
                                 {:iss :profile-identity
                                  :profile-id (:id profile)
                                  :exp (dt/in-future {:days 30})})]

    (when (not= email (:email profile))
      (check-profile-existence! conn params))

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
                :public-uri (cf/get :public-uri)
                :to (:email profile)
                :name (:fullname profile)
                :pending-email email
                :token token
                :extra-data ptoken})
    nil))


;; --- MUTATION: Update Profile Props

(def ^:private
  schema:update-profile-props
  (sm/define
    [:map {:title "update-profile-props"}
     [:props [:map-of :keyword :any]]]))

(sv/defmethod ::update-profile-props
  {::doc/added "1.0"
   ::sm/params schema:update-profile-props}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id props]}]
  (db/with-atomic [conn pool]
    (let [profile (get-profile conn profile-id ::sql/for-update true)
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

      (filter-props props))))

;; --- MUTATION: Delete Profile

(declare ^:private get-owned-teams-with-participants)

(sv/defmethod ::delete-profile
  {::doc/added "1.0"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
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

      (rph/with-transform {} (session/delete-fn cfg)))))


;; --- HELPERS

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

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = ?
                     and deleted_at is null) as val")

(defn- check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [result (db/exec-one! conn [sql:profile-existence email])]
    (when (:val result)
      (ex/raise :type :validation
                :code :email-already-exists))
    params))

(def ^:private sql:profile-by-email
  "select p.* from profile as p
    where p.email = ?
      and (p.deleted_at is null or
           p.deleted_at > now())")

(defn get-profile-by-email
  "Returns a profile looked up by email or `nil` if not match found."
  [conn email]
  (->> (db/exec! conn [sql:profile-by-email (clean-email email)])
       (map decode-row)
       (first)))

(defn strip-private-attrs
  "Only selects a publicly visible profile attrs."
  [row]
  (dissoc row :password :deleted-at))

(defn filter-props
  "Removes all namespace qualified props from `props` attr."
  [props]
  (into {} (filter (fn [[k _]] (simple-ident? k))) props))

(defn derive-password
  [{:keys [::wrk/executor]} password]
  (when password
    (px/invoke! executor (partial auth/derive-password password))))

(defn verify-password
  [{:keys [::wrk/executor]} password password-data]
  (px/invoke! executor (partial auth/verify-password password password-data)))

(defn decode-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props "jsonb")
    (assoc :props (db/decode-transit-pgobject props))))
