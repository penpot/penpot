;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.comments
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as uri]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.email :as eml]
   [app.features.fdata :as feat.fdata]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.rpc.retry :as rtry]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;; --- GENERAL PURPOSE INTERNAL HELPERS

(def r-mentions-split #"@\[[^\]]*\]\([^\)]*\)")
(def r-mentions #"@\[([^\]]*)\]\(([^\)]*)\)")

(def comment-max-length 750)

(defn- format-comment
  [{:keys [content]}]
  (->> (d/interleave-all
        (str/split content r-mentions-split)
        (->> (re-seq r-mentions content)
             (map (fn [[_ user _]] user))))
       (str/join "")))

(defn- format-comment-url
  [thread file]
  (str/ffmt "%/#/workspace?%"
            (cf/get :public-uri)
            (uri/map->query-string
             {:file-id (:id file)
              :page-id (:page-id file)
              :team-id (:team-id file)
              :comment-id (:id thread)})))

(defn- format-comment-ref
  [thread file]
  (str/ffmt "#%, %, %"
            (:seqn thread)
            (:name file)
            (:page-name file)))

(defn- get-team-users
  [conn team-id]
  (->> (teams/get-users+props conn team-id)
       (map profile/decode-row)
       (d/index-by :id)))

(defn- notification-email?
  [profile-id owner-id props]
  (if (= profile-id owner-id)
    (not= :none (-> props :notifications :email-comments))
    (= :all (-> props :notifications :email-comments))))

(defn- mention-email?
  [props]
  (not= :none (-> props :notifications :email-comments)))

(defn send-comment-emails!
  [conn profile comment thread file]
  (let [team-users        (get-team-users conn (:team-id file))
        comment-reference (format-comment-ref thread file)
        comment-content   (format-comment comment)
        comment-url       (format-comment-url thread file)
        profile-id        (get profile :id)

        ;; Users mentioned in this comment
        comment-mentions
        (-> (:mentions comment)
            (disj profile-id))

        ;; Users mentioned in this thread
        thread-mentions
        (-> (:mentions thread)
            ;; Remove the mentions in the comment because we're already sending a
            ;; notification
            (set/difference comment-mentions)
            (disj profile-id))

        ;; All users
        notificate-users-ids
        (-> (set (keys team-users))
            (set/difference comment-mentions)
            (set/difference thread-mentions)
            (disj profile-id))]

    (doseq [mention comment-mentions]
      (let [{:keys [fullname email props]} (get team-users mention)]
        (when (mention-email? props)
          (eml/send!
           {::eml/conn conn
            ::eml/factory eml/comment-mention
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (:fullname profile)
            :comment-reference comment-reference
            :comment-content comment-content
            :comment-url comment-url}))))

    ;; Send to the thread users
    (doseq [mention thread-mentions]
      (let [{:keys [fullname email props]} (get team-users mention)]
        (when (mention-email? props)
          (eml/send!
           {::eml/conn conn
            ::eml/factory eml/comment-thread
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (:fullname profile)
            :comment-reference comment-reference
            :comment-content comment-content
            :comment-url comment-url}))))

    ;; Send to users with the "all" flag activated
    (doseq [user-id notificate-users-ids]
      (let [{:keys [id fullname email props]} (get team-users user-id)]
        (when (notification-email? id (:owner-id thread) props)
          (eml/send!
           {::eml/conn conn
            ::eml/factory eml/comment-notification
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (:fullname profile)
            :comment-reference comment-reference
            :comment-content comment-content
            :comment-url comment-url}))))))

(defn- decode-row
  [{:keys [participants position mentions] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))
    (db/pgarray? mentions) (assoc :mentions (db/decode-pgarray mentions #{}))))

(def xf-decode-row
  (map decode-row))

(def ^:private
  sql:get-file
  "SELECT f.id, f.modified_at, f.revn, f.features, f.name,
          f.project_id, p.team_id, f.data,
          f.data_ref_id, f.data_backend
     FROM file as f
    INNER JOIN project as p on (p.id = f.project_id)
    WHERE f.id = ?
      AND (f.deleted_at IS NULL OR f.deleted_at > now())")

(defn- get-file
  "A specialized version of get-file for comments module."
  [cfg file-id page-id]
  (let [file (db/exec-one! cfg [sql:get-file file-id])]
    (when-not file
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "file not found"))

    (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
      (let [file (->> file
                      (files/decode-row)
                      (feat.fdata/resolve-file-data cfg))
            data (get file :data)]
        (-> file
            (assoc :page-name (dm/get-in data [:pages-index page-id :name]))
            (assoc :page-id page-id)
            (dissoc :data))))))

;; FIXME: rename
(defn- get-comment-thread
  [conn thread-id & {:as opts}]
  (-> (db/get-by-id conn :comment-thread thread-id opts)
      (decode-row)))

(defn- get-comment
  [conn comment-id & {:as opts}]
  (db/get-by-id conn :comment comment-id opts))

(def ^:private sql:get-next-seqn
  "SELECT (f.comment_thread_seqn + 1) AS next_seqn
     FROM file AS f
    WHERE f.id = ?
      FOR UPDATE")

(defn- get-next-seqn
  [conn file-id]
  (let [res (db/exec-one! conn [sql:get-next-seqn file-id])]
    (:next-seqn res)))

(def sql:upsert-comment-thread-status
  "insert into comment_thread_status (thread_id, profile_id, modified_at)
   values (?, ?, ?)
       on conflict (thread_id, profile_id)
       do update set modified_at = ?
   returning modified_at;")

(defn upsert-comment-thread-status!
  ([conn profile-id thread-id]
   (upsert-comment-thread-status! conn profile-id thread-id (ct/in-future "1s")))
  ([conn profile-id thread-id mod-at]
   (db/exec-one! conn [sql:upsert-comment-thread-status thread-id profile-id mod-at mod-at])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUERY COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- COMMAND: Get Comment Threads

(declare ^:private get-comment-threads)

(def ^:private
  schema:get-comment-threads
  [:and
   [:map {:title "get-comment-threads"}
    [:file-id {:optional true} ::sm/uuid]
    [:team-id {:optional true} ::sm/uuid]
    [:share-id {:optional true} [:maybe ::sm/uuid]]]
   [::sm/contains-any #{:file-id :team-id}]])

(sv/defmethod ::get-comment-threads
  {::doc/added "1.15"
   ::sm/params schema:get-comment-threads}
  [cfg {:keys [::rpc/profile-id file-id share-id] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-comment-permissions! conn profile-id file-id share-id)
                 (get-comment-threads conn profile-id file-id))))

(def ^:private sql:comment-threads
  "SELECT DISTINCT ON (ct.id)
          ct.*,
          pf.fullname AS owner_fullname,
          pf.email AS owner_email,
          pf.photo_id AS owner_photo_id,
          p.team_id AS team_id,
          f.name AS file_name,
          f.project_id AS project_id,
          first_value(c.content) OVER w AS content,
          (SELECT count(1)
             FROM comment AS c
            WHERE c.thread_id = ct.id) AS count_comments,
          (SELECT count(1)
             FROM comment AS c
            WHERE c.thread_id = ct.id
              AND c.created_at >= coalesce(cts.modified_at, ct.created_at)) AS count_unread_comments
     FROM comment_thread AS ct
    INNER JOIN comment AS c ON (c.thread_id = ct.id)
    INNER JOIN file AS f ON (f.id = ct.file_id)
    INNER JOIN project AS p ON (p.id = f.project_id)
     LEFT JOIN comment_thread_status AS cts ON (cts.thread_id = ct.id AND cts.profile_id = ?)
     LEFT JOIN profile AS pf ON (ct.owner_id = pf.id)
   WINDOW w AS (PARTITION BY c.thread_id ORDER BY c.created_at ASC)")

(def ^:private sql:comment-threads-by-file-id
  (str "WITH threads AS (" sql:comment-threads ")"
       "SELECT * FROM threads WHERE file_id = ?"))

(defn- get-comment-threads
  [conn profile-id file-id]
  (->> (db/exec! conn [sql:comment-threads-by-file-id profile-id file-id])
       (into [] xf-decode-row)))

;; --- COMMAND: Get Unread Comment Threads

(declare ^:private get-unread-comment-threads)

(def ^:private
  schema:get-unread-comment-threads
  [:map {:title "get-unread-comment-threads"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-unread-comment-threads
  {::doc/added "1.15"
   ::sm/params schema:get-unread-comment-threads}
  [cfg {:keys [::rpc/profile-id team-id] :as params}]
  (db/run!
   cfg
   (fn [{:keys [::db/conn]}]
     (teams/check-read-permissions! conn profile-id team-id)
     (get-unread-comment-threads conn profile-id team-id))))

(def sql:unread-all-comment-threads-by-team
  (str "WITH threads AS (" sql:comment-threads ")"
       "SELECT * FROM threads WHERE count_unread_comments > 0 AND team_id = ?"))

;; The partial configuration will retrieve only comments created by the user and
;; threads that have a mention to the user.
(def sql:unread-partial-comment-threads-by-team
  (str "WITH threads AS (" sql:comment-threads ")"
       "SELECT * FROM threads
         WHERE count_unread_comments > 0
           AND team_id = ?
           AND (owner_id = ? OR ? = ANY(mentions))"))

(defn- get-unread-comment-threads
  [conn profile-id team-id]
  (let [profile (-> (db/get conn :profile {:id profile-id})
                    (profile/decode-row))
        notify  (or (-> profile :props :notifications :dashboard-comments) :all)]

    (case notify
      :all
      (->> (db/exec! conn [sql:unread-all-comment-threads-by-team profile-id team-id])
           (into [] xf-decode-row))

      :partial
      (->> (db/exec! conn [sql:unread-partial-comment-threads-by-team profile-id team-id profile-id profile-id])
           (into [] xf-decode-row))

      [])))

;; --- COMMAND: Get Single Comment Thread

(def ^:private
  schema:get-comment-thread
  [:map {:title "get-comment-thread"}
   [:file-id ::sm/uuid]
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::get-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:get-comment-thread}
  [cfg {:keys [::rpc/profile-id file-id id share-id] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-comment-permissions! conn profile-id file-id share-id)
                 (let [sql (str "WITH threads AS (" sql:comment-threads ")"
                                "SELECT * FROM threads WHERE id = ? AND file_id = ?")]
                   (-> (db/exec-one! conn [sql profile-id id file-id])
                       (decode-row))))))

;; --- COMMAND: Retrieve Comments

(declare ^:private get-comments)

(def ^:private
  schema:get-comments
  [:map {:title "get-comments"}
   [:thread-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::get-comments
  {::doc/added "1.15"
   ::sm/params schema:get-comments}
  [cfg {:keys [::rpc/profile-id thread-id share-id]}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [{:keys [file-id]} (get-comment-thread conn thread-id)]
                   (files/check-comment-permissions! conn profile-id file-id share-id)
                   (get-comments conn thread-id)))))

(def sql:get-comments
  "SELECT c.*,
          ct.file_id AS file_id,
          pf.fullname AS owner_fullname,
          pf.email AS owner_email,
          pf.photo_id AS owner_photo_id
     FROM comment AS c
    INNER JOIN comment_thread AS ct ON (ct.id = c.thread_id)
     LEFT JOIN profile AS pf ON (c.owner_id = pf.id)
    WHERE c.thread_id = ?
    ORDER BY c.created_at ASC")

(defn- get-comments
  [conn thread-id]
  (->> (db/exec! conn [sql:get-comments thread-id])
       (into [] xf-decode-row)))

;; --- COMMAND: Get file comments users

;; All the profiles that had comment the file, plus the current
;; profile.

(def ^:private sql:file-comment-users
  "WITH available_profiles AS (
     SELECT DISTINCT owner_id AS id
       FROM comment
      WHERE thread_id IN (SELECT id FROM comment_thread WHERE file_id=?)
  )
  SELECT p.id,
         p.email,
         p.fullname AS name,
         p.fullname AS fullname,
         p.photo_id,
         p.is_active
    FROM profile AS p
   WHERE p.id IN (SELECT id FROM available_profiles) OR p.id=?")

(defn get-file-comments-users
  [conn file-id profile-id]
  (db/exec! conn [sql:file-comment-users file-id profile-id]))

(def ^:private
  schema:get-profiles-for-file-comments
  [:map {:title "get-profiles-for-file-comments"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::get-profiles-for-file-comments
  "Retrieves a list of profiles with limited set of properties of all
  participants on comment threads of the file."
  {::doc/added "1.15"
   ::doc/changes ["1.15" "Imported from queries and renamed."]
   ::sm/params schema:get-profiles-for-file-comments}
  [cfg {:keys [::rpc/profile-id file-id share-id]}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-comment-permissions! conn profile-id file-id share-id)
                 (get-file-comments-users conn file-id profile-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private create-comment-thread)

;; --- COMMAND: Create Comment Thread

(def ^:private
  schema:create-comment-thread
  [:map {:title "create-comment-thread"}
   [:file-id ::sm/uuid]
   [:position ::gpt/point]
   [:content [:string {:max comment-max-length}]]
   [:page-id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:mentions {:optional true} [::sm/set ::sm/uuid]]])

(defn- update-thread-seqn
  [conn file-id seqn]
  (db/update! conn :file
              {:comment-thread-seqn seqn}
              {:id file-id}
              {::db/return-keys false}))

(defn add-owner
  [thread-or-comment profile]
  (-> thread-or-comment
      (assoc :owner-fullname (:fullname profile))
      (assoc :owner-email (:email profile))
      (assoc :owner-photo-id (:photo-id profile))))

(sv/defmethod ::create-comment-thread
  {::doc/added "1.15"
   ::webhooks/event? true
   ::rtry/enabled true
   ::rtry/when rtry/conflict-exception?
   ::sm/params schema:create-comment-thread}
  [cfg {:keys [::rpc/profile-id file-id page-id share-id] :as params}]
  (files/check-comment-permissions! cfg profile-id file-id share-id)

  (let [{:keys [team-id project-id] :as file} (get-file cfg file-id page-id)]
    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/team-id team-id)
        (assoc ::quotes/project-id project-id)
        (assoc ::quotes/file-id file-id)
        (quotes/check! {::quotes/id ::quotes/comment-threads-per-file}
                       {::quotes/id ::quotes/comments-per-file}))

    (let [params (assoc params ::file file)
          thread (db/tx-run! cfg create-comment-thread params)]

      (vary-meta thread assoc ::audit/props thread))))

(defn- create-comment-thread
  [{:keys [::db/conn] :as cfg}
   {:keys [::rpc/profile-id ::rpc/request-at ::file position content mentions frame-id] :as params}]

  (let [;; NOTE: we take the next seq number from a separate query
        ;; because we need to lock the file for avoid race conditions

        ;; FIXME: this method touches and locks the file table,which
        ;; is already heavy-update tablel; we need to think on move
        ;; the sequence state management to a different table or
        ;; different storage (example: redis) for alivate the update
        ;; pression on the file table

        profile   (profile/get-profile conn profile-id)
        seqn      (get-next-seqn conn (:id file))

        file-id   (get file :id)
        thread-id (uuid/next)

        thread    (-> (db/insert! conn :comment-thread
                                  {:id thread-id
                                   :file-id file-id
                                   :page-name (:page-name file)
                                   :page-id (:page-id file)
                                   :owner-id profile-id
                                   :participants (db/tjson #{profile-id})
                                   :created-at request-at
                                   :modified-at request-at
                                   :seqn seqn
                                   :frame-id frame-id
                                   :position (db/pgpoint position)
                                   :mentions (db/encode-pgarray mentions conn "uuid")})
                      (decode-row))
        comment   (-> (db/insert! conn :comment
                                  {:id (uuid/next)
                                   :thread-id thread-id
                                   :owner-id profile-id
                                   :created-at request-at
                                   :modified-at request-at
                                   :mentions (db/encode-pgarray mentions conn "uuid")
                                   :content content})
                      (decode-row))]

    ;; Make the current thread as read.
    (upsert-comment-thread-status! conn profile-id thread-id request-at)

    ;; Optimistic update of current seq number on file.
    (update-thread-seqn conn file-id seqn)

    ;; Send mentions emails
    (send-comment-emails! conn profile comment thread file)

    (-> thread
        (add-owner profile)
        (assoc :comment-id (:id comment)))))

;; --- COMMAND: Update Comment Thread Status

(def ^:private
  schema:update-comment-thread-status
  [:map {:title "update-comment-thread-status"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread-status
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-status
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (upsert-comment-thread-status! conn profile-id id)))

;; --- COMMAND: Update Comment Thread

(def ^:private
  schema:update-comment-thread
  [:map {:title "update-comment-thread"}
   [:id ::sm/uuid]
   [:is-resolved :boolean]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id is-resolved share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (db/update! conn :comment-thread
                {:is-resolved is-resolved}
                {:id id})
    nil))

;; --- COMMAND: Add Comment

(def ^:private
  schema:create-comment
  [:map {:title "create-comment"}
   [:thread-id ::sm/uuid]
   [:content [:string {:max comment-max-length}]]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:mentions {:optional true} [::sm/set ::sm/uuid]]])

(sv/defmethod ::create-comment
  {::doc/added "1.15"
   ::webhooks/event? true
   ::sm/params schema:create-comment
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id ::rpc/request-at thread-id share-id content mentions]}]
  (let [{:keys [file-id page-id] :as thread}
        (get-comment-thread conn thread-id ::sql/for-update true)

        {:keys [team-id project-id] :as file}
        (get-file cfg file-id page-id)]

    (files/check-comment-permissions! conn profile-id file-id share-id)

    (quotes/check! cfg {::quotes/id ::quotes/comments-per-file
                        ::quotes/profile-id profile-id
                        ::quotes/team-id team-id
                        ::quotes/project-id project-id
                        ::quotes/file-id file-id})

    (let [profile  (profile/get-profile conn profile-id)
          mentions (into #{} mentions)
          params   {:id (uuid/next)
                    :created-at request-at
                    :modified-at request-at
                    :thread-id thread-id
                    :owner-id profile-id
                    :content content
                    :mentions (db/encode-pgarray mentions conn "uuid")}

          comment (-> (db/insert! conn :comment params)
                      (decode-row)
                      (assoc :file-id file-id)
                      (add-owner profile))]

      ;; Update thread modified-at attribute and assoc the current
      ;; profile to the participant set.
      (let [mentions     (into (:mentions thread) mentions)
            participants (-> (:participants thread #{})
                             (conj profile-id))
            params       {:modified-at request-at
                          :participants (db/tjson participants)
                          :mentions (db/encode-pgarray mentions conn "uuid")}

            ;; Update the page-name cached attribute on comment thread table.
            params       (cond-> params
                           (not= (:page-name file) (:page-name thread))
                           (assoc :page-name (:page-name file)))]

        (db/update! conn :comment-thread params
                    {:id thread-id}
                    {::db/return-keys false}))

      ;; Update the current profile status in relation to the current thread
      (upsert-comment-thread-status! conn profile-id thread-id)

      (send-comment-emails! conn profile comment thread file)

      (vary-meta comment assoc ::audit/props comment))))

;; --- COMMAND: Update Comment

(def ^:private
  schema:update-comment
  [:map {:title "update-comment"}
   [:id ::sm/uuid]
   [:content [:string {:max comment-max-length}]]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:mentions {:optional true} [::sm/set ::sm/uuid]]])

;; TODO: Check if there are new mentions, if there are send the new emails.

(sv/defmethod ::update-comment
  {::doc/added "1.15"
   ::sm/params schema:update-comment
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id ::rpc/request-at id share-id content mentions]}]
  (let [{:keys [thread-id owner-id] :as comment}
        (get-comment conn id ::sql/for-update true)

        {:keys [file-id page-id] :as thread}
        (get-comment-thread conn thread-id ::sql/for-update true)]

    (files/check-comment-permissions! conn profile-id file-id share-id)

    ;; Don't allow edit comments to not owners
    (when-not (= owner-id profile-id)
      (ex/raise :type :validation
                :code :not-allowed))

    (let [{:keys [page-name]} (get-file cfg file-id page-id)]
      (db/update! conn :comment
                  {:content content
                   :modified-at request-at
                   :mentions (db/encode-pgarray mentions conn "uuid")}
                  {:id id})

      (db/update! conn :comment-thread
                  {:modified-at request-at
                   :page-name page-name
                   :mentions
                   (-> (:mentions thread)
                       (into mentions)
                       (db/encode-pgarray conn "uuid"))}
                  {:id thread-id}
                  {::db/return-keys false})
      nil)))

;; --- COMMAND: Delete Comment Thread

(def ^:private
  schema:delete-comment-thread
  [:map {:title "delete-comment-thread"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::delete-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:delete-comment-thread
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id share-id]}]
  (let [{:keys [owner-id file-id] :as thread} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (when-not (= owner-id profile-id)
      (ex/raise :type :validation
                :code :not-allowed))

    (db/delete! conn :comment-thread {:id id}
                {::db/return-keys false})
    nil))

;; --- COMMAND: Delete comment

(def ^:private
  schema:delete-comment
  [:map {:title "delete-comment"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::delete-comment
  {::doc/added "1.15"
   ::sm/params schema:delete-comment
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id share-id]}]
  (let [{:keys [owner-id thread-id] :as comment}
        (get-comment conn id ::sql/for-update true)

        {:keys [file-id]}
        (get-comment-thread conn thread-id)]

    (files/check-comment-permissions! conn profile-id file-id share-id)
    (when-not (= owner-id profile-id)
      (ex/raise :type :validation
                :code :not-allowed))

    (db/delete! conn :comment {:id id}
                {::db/return-keys false})
    nil))

;; --- COMMAND: Update comment thread position

(def ^:private
  schema:update-comment-thread-position
  [:map {:title "update-comment-thread-position"}
   [:id ::sm/uuid]
   [:position ::gpt/point]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread-position
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-position
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id ::rpc/request-at id position frame-id share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (db/update! conn :comment-thread
                {:modified-at request-at
                 :position (db/pgpoint position)
                 :frame-id frame-id}
                {:id id}
                {::db/return-keys false})
    nil))

;; --- COMMAND: Update comment frame

(def ^:private
  schema:update-comment-thread-frame
  [:map {:title "update-comment-thread-frame"}
   [:id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread-frame
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-frame
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id ::rpc/request-at id frame-id share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (db/update! conn :comment-thread
                {:modified-at request-at
                 :frame-id frame-id}
                {:id id}
                {::db/return-keys false})
    nil))

(def ^:private
  schema:mark-all-threads-as-read
  [:map {:title "mark-all-threads-as-read"}
   [:threads [:vector ::sm/uuid]]])

(sv/defmethod ::mark-all-threads-as-read
  {::doc/added "1.15"
   ::sm/params schema:mark-all-threads-as-read}
  [cfg {:keys [::rpc/profile-id threads] :as params}]
  (db/tx-run!
   cfg
   (fn [{:keys [::db/conn]}]
     (doseq [thread-id threads]
       (upsert-comment-thread-status! conn profile-id thread-id)))))
