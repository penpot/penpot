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
   [app.common.time :as ct]
   [app.common.types.plugins :refer [schema:plugin-registry]]
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
   [app.worker :as wrk]
   [cuerdas.core :as str]))

(declare check-profile-existence!)
(declare decode-row)
(declare filter-props)
(declare get-profile)
(declare strip-private-attrs)

(def schema:props-notifications
  [:map {:title "props-notifications"}
   [:dashboard-comments [::sm/one-of #{:all :partial :none}]]
   [:email-comments [::sm/one-of #{:all :partial :none}]]
   [:email-invites [::sm/one-of #{:all :none}]]])

(def schema:props
  [:map {:title "ProfileProps"}
   [:plugins {:optional true} schema:plugin-registry]
   [:newsletter-updates {:optional true} ::sm/boolean]
   [:newsletter-news {:optional true} ::sm/boolean]
   [:onboarding-team-id {:optional true} ::sm/uuid]
   [:onboarding-viewed {:optional true} ::sm/boolean]
   [:v2-info-shown {:optional true} ::sm/boolean]
   [:welcome-file-id {:optional true} [:maybe ::sm/boolean]]
   [:release-notes-viewed {:optional true}
    [::sm/text {:max 100}]]
   [:notifications {:optional true} schema:props-notifications]
   [:workspace-visited {:optional true} ::sm/boolean]])

(def schema:profile
  [:map {:title "Profile"}
   [:id ::sm/uuid]
   [:fullname [::sm/word-string {:max 250}]]
   [:email ::sm/email]
   [:is-active {:optional true} ::sm/boolean]
   [:is-blocked {:optional true} ::sm/boolean]
   [:is-demo {:optional true} ::sm/boolean]
   [:is-muted {:optional true} ::sm/boolean]
   [:created-at {:optional true} ::ct/inst]
   [:modified-at {:optional true} ::ct/inst]
   [:default-project-id {:optional true} ::sm/uuid]
   [:default-team-id {:optional true} ::sm/uuid]
   [:props {:optional true} schema:props]])

(defn clean-email
  "Clean and normalizes email address string"
  [email]
  (let [email (str/lower email)
        email (if (str/starts-with? email "mailto:")
                (subs email 7)
                email)
        email (if (or (str/starts-with? email "<")
                      (str/ends-with? email ">"))
                (str/trim email "<>")
                email)]
    email))

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
  ;; NOTE: We need to set ::db/remove-deleted to false because demo profiles
  ;; are created with a set deleted-at value
  (-> (db/get-by-id conn :profile id (assoc opts ::db/remove-deleted false))
      (decode-row)))

;; --- MUTATION: Update Profile (own)

(def ^:private
  schema:update-profile
  [:map {:title "update-profile"}
   [:fullname [::sm/word-string {:max 250}]]
   [:lang {:optional true} [:string {:max 8}]]
   [:theme {:optional true} [:string {:max 250}]]])

(sv/defmethod ::update-profile
  {::doc/added "1.0"
   ::sm/params schema:update-profile
   ::sm/result schema:profile
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id fullname lang theme] :as params}]
  ;; NOTE: we need to retrieve the profile independently if we use
  ;; it or not for explicit locking and avoid concurrent updates of
  ;; the same row/object.
  (let [profile (get-profile conn profile-id ::db/for-update true)
        ;; Update the profile map with direct params
        profile (-> profile
                    (assoc :fullname fullname)
                    (assoc :lang lang)
                    (assoc :theme theme))]

    (db/update! conn :profile
                {:fullname fullname
                 :lang lang
                 :theme theme}
                {:id profile-id}
                {::db/return-keys false})

    (-> profile
        (strip-private-attrs)
        (d/without-nils)
        (rph/with-meta {::audit/props (audit/profile->props profile)}))))


;; --- MUTATION: Update Password

(declare validate-password!)
(declare update-profile-password!)
(declare invalidate-profile-session!)

(def ^:private
  schema:update-profile-password
  [:map {:title "update-profile-password"}
   [:password [::sm/word-string {:max 500}]]
   ;; Social registered users don't have old-password
   [:old-password {:optional true} [:maybe [::sm/word-string {:max 500}]]]])

(sv/defmethod ::update-profile-password
  {::doc/added "1.0"
   ::sm/params schema:update-profile-password
   ::climit/id :auth/global
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id password] :as params}]
  (let [profile    (validate-password! cfg (assoc params :profile-id profile-id))
        session-id (::session/id params)]

    (when (= (:email profile) (str/lower (:password params)))
      (ex/raise :type :validation
                :code :email-as-password
                :hint "you can't use your email as password"))

    (update-profile-password! cfg (assoc profile :password password))
    (invalidate-profile-session! cfg profile-id session-id)
    nil))

(defn- invalidate-profile-session!
  "Removes all sessions except the current one."
  [{:keys [::db/conn]} profile-id session-id]
  (let [sql "delete from http_session where profile_id = ? and id != ?"]
    (:next.jdbc/update-count (db/exec-one! conn [sql profile-id session-id]))))

(defn- validate-password!
  [{:keys [::db/conn] :as cfg} {:keys [profile-id old-password] :as params}]
  (let [profile (db/get-by-id conn :profile profile-id ::sql/for-update true)]
    (when (and (not= (:password profile) "!")
               (not (:valid (auth/verify-password old-password (:password profile)))))
      (ex/raise :type :validation
                :code :old-password-not-match))
    profile))

(defn update-profile-password!
  [{:keys [::db/conn] :as cfg} {:keys [id password] :as profile}]
  (when-not (db/read-only? conn)
    (db/update! conn :profile
                {:password (auth/derive-password password)}
                {:id id})
    nil))


;; --- MUTATION: Update notifications

(def ^:private
  schema:update-profile-notifications
  [:map {:title "update-profile-notifications"}
   [:dashboard-comments [::sm/one-of #{:all :partial :none}]]
   [:email-comments [::sm/one-of #{:all :partial :none}]]
   [:email-invites [::sm/one-of #{:all :none}]]])

(declare update-notifications!)

(sv/defmethod ::update-profile-notifications
  {::doc/added "2.4.0"
   ::sm/params schema:update-profile-notifications
   ::climit/id :auth/global}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg update-notifications! (assoc params :profile-id profile-id)))

(defn- update-notifications!
  [{:keys [::db/conn] :as cfg} {:keys [profile-id dashboard-comments email-comments email-invites]}]
  (let [profile
        (get-profile conn profile-id ::db/for-update true)

        notifications
        {:dashboard-comments dashboard-comments
         :email-comments email-comments
         :email-invites email-invites}

        props
        (-> (get profile :props)
            (assoc :notifications notifications))]

    (db/update! conn :profile
                {:props (db/tjson props)}
                {:id profile-id}
                {::db/return-keys false})
    nil))

;; --- MUTATION: Update Photo

(declare upload-photo)
(declare update-profile-photo)

(def ^:private
  schema:update-profile-photo
  [:map {:title "update-profile-photo"}
   [:file media/schema:upload]])

(sv/defmethod ::update-profile-photo
  {:doc/added "1.1"
   ::sm/params schema:update-profile-photo
   ::sm/result :nil}
  [cfg {:keys [::rpc/profile-id file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (update-profile-photo cfg (assoc params :profile-id profile-id)))

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
  [_ file]
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
  [{:keys [::sto/storage] :as cfg} {:keys [file] :as params}]
  (let [params (-> cfg
                   (assoc ::climit/id [[:process-image/by-profile (:profile-id params)]
                                       [:process-image/global]])
                   (assoc ::climit/label "upload-photo")
                   (climit/invoke! generate-thumbnail! file))]
    (sto/put-object! storage params)))

;; --- MUTATION: Request Email Change

(declare ^:private request-email-change!)
(declare ^:private change-email-immediately!)

(def ^:private
  schema:request-email-change
  [:map {:title "request-email-change"}
   [:email ::sm/email]])

(sv/defmethod ::request-email-change
  {::doc/added "1.0"
   ::sm/params schema:request-email-change}
  [cfg {:keys [::rpc/profile-id email] :as params}]
  (db/tx-run! cfg
              (fn [cfg]
                (let [profile (db/get-by-id cfg :profile profile-id)
                      params  (assoc params
                                     :profile profile
                                     :email (clean-email email))]
                  (if (contains? cf/flags :smtp)
                    (request-email-change! cfg params)
                    (change-email-immediately! cfg params))))))

(defn- change-email-immediately!
  [{:keys [::db/conn]} {:keys [profile email] :as params}]
  (when (not= email (:email profile))
    (check-profile-existence! conn params))

  (db/update! conn :profile
              {:email email}
              {:id (:id profile)})

  {:changed true})

(defn- request-email-change!
  [{:keys [::db/conn] :as cfg} {:keys [profile email] :as params}]
  (let [token   (tokens/generate cfg
                                 {:iss :change-email
                                  :exp (ct/in-future "15m")
                                  :profile-id (:id profile)
                                  :email email})
        ptoken  (tokens/generate cfg
                                 {:iss :profile-identity
                                  :profile-id (:id profile)
                                  :exp (ct/in-future {:days 30})})]

    (when (not= email (:email profile))
      (check-profile-existence! conn params))

    (when-not (eml/allow-send-emails? conn profile)
      (ex/raise :type :validation
                :code :profile-is-muted
                :hint "looks like the profile has reported repeatedly as spam or has permanent bounces."))

    (when (eml/has-bounce-reports? conn email)
      (ex/raise :type :restriction
                :code :email-has-permanent-bounces
                :email email
                :hint "looks like the email has bounce reports"))

    (when (eml/has-complaint-reports? conn email)
      (ex/raise :type :restriction
                :code :email-has-complaints
                :email email
                :hint "looks like the email has spam complaint reports"))

    (when (eml/has-bounce-reports? conn (:email profile))
      (ex/raise :type :restriction
                :code :email-has-permanent-bounces
                :email (:email profile)
                :hint "looks like the email has bounce reports"))

    (when (eml/has-complaint-reports? conn (:email profile))
      (ex/raise :type :restriction
                :code :email-has-complaints
                :email (:email profile)
                :hint "looks like the email has spam complaint reports"))

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
  [:map {:title "update-profile-props"}
   [:props schema:props]])

(defn update-profile-props
  [{:keys [::db/conn] :as cfg} profile-id props]
  (let [profile (get-profile conn profile-id ::db/for-update true)
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
                {:id profile-id}
                {::db/return-keys false})

    (filter-props props)))

(sv/defmethod ::update-profile-props
  {::doc/added "1.0"
   ::sm/params schema:update-profile-props
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id props]}]
  (update-profile-props cfg profile-id props))

;; --- MUTATION: Delete Profile

(declare ^:private get-owned-teams)

(sv/defmethod ::delete-profile
  {::doc/added "1.0"
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (let [teams      (get-owned-teams conn profile-id)
        deleted-at (ct/now)]

    ;; If we found owned teams with participants, we don't allow
    ;; delete profile until the user properly transfer ownership or
    ;; explicitly removes all participants from the team
    (when (some pos? (map :participants teams))
      (ex/raise :type :validation
                :code :owner-teams-with-people
                :hint "The user need to transfer ownership of owned teams."
                :context {:teams (mapv :id teams)}))

    ;; Mark profile deleted immediatelly
    (db/update! conn :profile
                {:deleted-at deleted-at}
                {:id profile-id})

    ;; Schedule cascade deletion to a worker
    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :profile
                                :deleted-at deleted-at
                                :id profile-id}})


    (-> (rph/wrap nil)
        (rph/with-transform (session/delete-fn cfg)))))

(def sql:get-subscription-editors
  "SELECT DISTINCT
          p.id,
          p.fullname AS name,
          p.email AS email
     FROM team_profile_rel AS tpr1
     JOIN team as t
       ON tpr1.team_id = t.id
     JOIN team_profile_rel AS tpr2
       ON (tpr1.team_id = tpr2.team_id)
     JOIN profile AS p
       ON (tpr2.profile_id = p.id)
    WHERE tpr1.profile_id = ?
      AND tpr1.is_owner IS true
      AND tpr2.can_edit IS true
      AND t.deleted_at IS NULL")

(sv/defmethod ::get-subscription-usage
  {::doc/added "2.9"}
  [cfg {:keys [::rpc/profile-id]}]
  (let [editors (db/exec! cfg [sql:get-subscription-editors profile-id])]
    {:editors editors}))

;; --- HELPERS

(def sql:owned-teams
  "WITH owner_teams AS (
      SELECT tpr.team_id AS id
        FROM team_profile_rel AS tpr
        JOIN team AS t ON (t.id = tpr.team_id)
       WHERE tpr.is_owner IS TRUE
         AND tpr.profile_id = ?
         AND t.deleted_at IS NULL
   )
   SELECT tpr.team_id AS id,
          count(tpr.profile_id) - 1 AS participants
     FROM team_profile_rel AS tpr
    WHERE tpr.team_id IN (SELECT id from owner_teams)
    GROUP BY 1")

(defn get-owned-teams
  [conn profile-id]
  (db/exec! conn [sql:owned-teams profile-id]))

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

(defn decode-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props "jsonb")
    (assoc :props (db/decode-transit-pgobject props))))
