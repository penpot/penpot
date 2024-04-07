;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.comments
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as feat.fdata]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.rpc.retry :as rtry]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]))

;; --- GENERAL PURPOSE INTERNAL HELPERS

(defn- decode-row
  [{:keys [participants position] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))))

(def xf-decode-row
  (map decode-row))

(def ^:privateqpage-name
  sql:get-file
  "select f.id, f.modified_at, f.revn, f.features,
          f.project_id, p.team_id, f.data
     from file as f
     join project as p on (p.id = f.project_id)
    where f.id = ?
      and f.deleted_at is null")

(defn- get-file
  "A specialized version of get-file for comments module."
  [cfg file-id page-id]
  (let [file (db/exec-one! cfg [sql:get-file file-id])]
    (when-not file
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "file not found"))

    (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
      (let [{:keys [data] :as file} (files/decode-row file)]
        (-> file
            (assoc :page-name (dm/get-in data [:pages-index page-id :name]))
            (assoc :page-id page-id)
            (dissoc :data))))))

(defn- get-comment-thread
  [conn thread-id & {:as opts}]
  (-> (db/get-by-id conn :comment-thread thread-id opts)
      (decode-row)))

(defn- get-comment
  [conn comment-id & {:as opts}]
  (db/get-by-id conn :comment comment-id opts))

(defn- get-next-seqn
  [conn file-id]
  (let [sql "select (f.comment_thread_seqn + 1) as next_seqn from file as f where f.id = ?"
        res (db/exec-one! conn [sql file-id])]
    (:next-seqn res)))

(def sql:upsert-comment-thread-status
  "insert into comment_thread_status (thread_id, profile_id, modified_at)
   values (?, ?, ?)
       on conflict (thread_id, profile_id)
       do update set modified_at = ?
   returning modified_at;")

(defn upsert-comment-thread-status!
  ([conn profile-id thread-id]
   (upsert-comment-thread-status! conn profile-id thread-id (dt/now)))
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
  "select distinct on (ct.id)
          ct.*,
          f.name as file_name,
          f.project_id as project_id,
          first_value(c.content) over w as content,
          (select count(1)
             from comment as c
            where c.thread_id = ct.id) as count_comments,
          (select count(1)
             from comment as c
            where c.thread_id = ct.id
              and c.created_at >= coalesce(cts.modified_at, ct.created_at)) as count_unread_comments
     from comment_thread as ct
    inner join comment as c on (c.thread_id = ct.id)
    inner join file as f on (f.id = ct.file_id)
     left join comment_thread_status as cts
            on (cts.thread_id = ct.id and
                cts.profile_id = ?)
    where ct.file_id = ?
   window w as (partition by c.thread_id order by c.created_at asc)")

(defn- get-comment-threads
  [conn profile-id file-id]
  (->> (db/exec! conn [sql:comment-threads profile-id file-id])
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
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (teams/check-read-permissions! conn profile-id team-id)
                 (get-unread-comment-threads conn profile-id team-id))))

(def sql:comment-threads-by-team
  "select distinct on (ct.id)
          ct.*,
          f.name as file_name,
          f.project_id as project_id,
          first_value(c.content) over w as content,
          (select count(1)
             from comment as c
            where c.thread_id = ct.id) as count_comments,
          (select count(1)
             from comment as c
            where c.thread_id = ct.id
              and c.created_at >= coalesce(cts.modified_at, ct.created_at)) as count_unread_comments
     from comment_thread as ct
    inner join comment as c on (c.thread_id = ct.id)
    inner join file as f on (f.id = ct.file_id)
    inner join project as p on (p.id = f.project_id)
     left join comment_thread_status as cts
            on (cts.thread_id = ct.id and
                cts.profile_id = ?)
    where p.team_id = ?
   window w as (partition by c.thread_id order by c.created_at asc)")

(def sql:unread-comment-threads-by-team
  (str "with threads as (" sql:comment-threads-by-team ")"
       "select * from threads where count_unread_comments > 0"))

(defn- get-unread-comment-threads
  [conn profile-id team-id]
  (->> (db/exec! conn [sql:unread-comment-threads-by-team profile-id team-id])
       (into [] xf-decode-row)))

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
                 (let [sql (str "with threads as (" sql:comment-threads ")"
                                "select * from threads where id = ?")]
                   (-> (db/exec-one! conn [sql profile-id file-id id])
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
                 (let [{:keys [file-id] :as thread} (get-comment-thread conn thread-id)]
                   (files/check-comment-permissions! conn profile-id file-id share-id)
                   (get-comments conn thread-id)))))

(defn- get-comments
  [conn thread-id]
  (->> (db/query conn :comment
                 {:thread-id thread-id}
                 {:order-by [[:created-at :asc]]})
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
   [:content :string]
   [:page-id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::create-comment-thread
  {::doc/added "1.15"
   ::webhooks/event? true
   ::rtry/enabled true
   ::rtry/when rtry/conflict-exception?
   ::sm/params schema:create-comment-thread}
  [cfg {:keys [::rpc/profile-id ::rpc/request-at file-id page-id share-id position content frame-id]}]

  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (files/check-comment-permissions! cfg profile-id file-id share-id)
                    (let [{:keys [team-id project-id page-name]} (get-file conn file-id page-id)]

                      (run! (partial quotes/check-quote! cfg)
                            (list {::quotes/id ::quotes/comment-threads-per-file
                                   ::quotes/profile-id profile-id
                                   ::quotes/team-id team-id
                                   ::quotes/project-id project-id
                                   ::quotes/file-id file-id}
                                  {::quotes/id ::quotes/comments-per-file
                                   ::quotes/profile-id profile-id
                                   ::quotes/team-id team-id
                                   ::quotes/project-id project-id
                                   ::quotes/file-id file-id}))

                      (create-comment-thread conn {:created-at request-at
                                                   :profile-id profile-id
                                                   :file-id file-id
                                                   :page-id page-id
                                                   :page-name page-name
                                                   :position position
                                                   :content content
                                                   :frame-id frame-id})))))

(defn- create-comment-thread
  [conn {:keys [profile-id file-id page-id page-name created-at position content frame-id]}]

  (let [;; NOTE: we take the next seq number from a separate query because the whole
        ;; operation can be retried on conflict, and in this case the new seq shold be
        ;; retrieved from the database.
        seqn      (get-next-seqn conn file-id)
        thread-id (uuid/next)
        thread    (db/insert! conn :comment-thread
                              {:id thread-id
                               :file-id file-id
                               :owner-id profile-id
                               :participants (db/tjson #{profile-id})
                               :page-name page-name
                               :page-id page-id
                               :created-at created-at
                               :modified-at created-at
                               :seqn seqn
                               :position (db/pgpoint position)
                               :frame-id frame-id})
        comment   (db/insert! conn :comment
                              {:id (uuid/next)
                               :thread-id thread-id
                               :owner-id profile-id
                               :created-at created-at
                               :modified-at created-at
                               :content content})]

    ;; Make the current thread as read.
    (upsert-comment-thread-status! conn profile-id thread-id created-at)

    ;; Optimistic update of current seq number on file.
    (db/update! conn :file
                {:comment-thread-seqn seqn}
                {:id file-id})

    (-> thread
        (select-keys [:id :file-id :page-id])
        (assoc :comment-id (:id comment)))))

;; --- COMMAND: Update Comment Thread Status

(def ^:private
  schema:update-comment-thread-status
  [:map {:title "update-comment-thread-status"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread-status
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-status}
  [cfg {:keys [::rpc/profile-id id share-id]}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::sql/for-update true)]
                      (files/check-comment-permissions! conn profile-id file-id share-id)
                      (upsert-comment-thread-status! conn profile-id id)))))


;; --- COMMAND: Update Comment Thread

(def ^:private
  schema:update-comment-thread
  [:map {:title "update-comment-thread"}
   [:id ::sm/uuid]
   [:is-resolved :boolean]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread}
  [cfg {:keys [::rpc/profile-id id is-resolved share-id]}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::sql/for-update true)]
                      (files/check-comment-permissions! conn profile-id file-id share-id)
                      (db/update! conn :comment-thread
                                  {:is-resolved is-resolved}
                                  {:id id})
                      nil))))


;; --- COMMAND: Add Comment

(declare ^:private get-comment-thread)

(def ^:private
  schema:create-comment
  [:map {:title "create-comment"}
   [:thread-id ::sm/uuid]
   [:content :string]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::create-comment
  {::doc/added "1.15"
   ::webhooks/event? true
   ::sm/params schema:create-comment}
  [cfg {:keys [::rpc/profile-id ::rpc/request-at thread-id share-id content]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (let [{:keys [file-id page-id] :as thread} (get-comment-thread conn thread-id ::sql/for-update true)
                      {:keys [team-id project-id page-name] :as file} (get-file cfg file-id page-id)]

                  (files/check-comment-permissions! conn profile-id file-id share-id)
                  (quotes/check-quote! conn
                                       {::quotes/id ::quotes/comments-per-file
                                        ::quotes/profile-id profile-id
                                        ::quotes/team-id team-id
                                        ::quotes/project-id project-id
                                        ::quotes/file-id file-id})

                  ;; Update the page-name cached attribute on comment thread table.
                  (when (not= page-name (:page-name thread))
                    (db/update! conn :comment-thread
                                {:page-name page-name}
                                {:id thread-id}))

                  (let [comment (db/insert! conn :comment
                                            {:id (uuid/next)
                                             :created-at request-at
                                             :modified-at request-at
                                             :thread-id thread-id
                                             :owner-id profile-id
                                             :content content})
                        props    {:file-id file-id
                                  :share-id nil}]

                    ;; Update thread modified-at attribute and assoc the current
                    ;; profile to the participant set.
                    (db/update! conn :comment-thread
                                {:modified-at request-at
                                 :participants (-> (:participants thread #{})
                                                   (conj profile-id)
                                                   (db/tjson))}
                                {:id thread-id})

                    ;; Update the current profile status in relation to the
                    ;; current thread.
                    (upsert-comment-thread-status! conn profile-id thread-id request-at)

                    (vary-meta comment assoc ::audit/props props))))))


;; --- COMMAND: Update Comment

(def ^:private
  schema:update-comment
  [:map {:title "update-comment"}
   [:id ::sm/uuid]
   [:content :string]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment
  {::doc/added "1.15"
   ::sm/params schema:update-comment}
  [cfg {:keys [::rpc/profile-id ::rpc/request-at id share-id content]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (let [{:keys [thread-id owner-id] :as comment} (get-comment conn id ::sql/for-update true)
                      {:keys [file-id page-id] :as thread} (get-comment-thread conn thread-id ::sql/for-update true)]

                  (files/check-comment-permissions! conn profile-id file-id share-id)

                  ;; Don't allow edit comments to not owners
                  (when-not (= owner-id profile-id)
                    (ex/raise :type :validation
                              :code :not-allowed))

                  (let [{:keys [page-name]} (get-file cfg file-id page-id)]
                    (db/update! conn :comment
                                {:content content
                                 :modified-at request-at}
                                {:id id})

                    (db/update! conn :comment-thread
                                {:modified-at request-at
                                 :page-name page-name}
                                {:id thread-id})
                    nil)))))

;; --- COMMAND: Delete Comment Thread

(def ^:private
  schema:delete-comment-thread
  [:map {:title "delete-comment-thread"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::delete-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:delete-comment-thread}
  [cfg {:keys [::rpc/profile-id id share-id]}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (let [{:keys [owner-id file-id] :as thread} (get-comment-thread conn id ::sql/for-update true)]
                      (files/check-comment-permissions! conn profile-id file-id share-id)
                      (when-not (= owner-id profile-id)
                        (ex/raise :type :validation
                                  :code :not-allowed))

                      (db/delete! conn :comment-thread {:id id})
                      nil))))

;; --- COMMAND: Delete comment

(def ^:private
  schema:delete-comment
  [:map {:title "delete-comment"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::delete-comment
  {::doc/added "1.15"
   ::sm/params schema:delete-comment}
  [cfg {:keys [::rpc/profile-id id share-id]}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (let [{:keys [owner-id thread-id] :as comment} (get-comment conn id ::sql/for-update true)
                          {:keys [file-id] :as thread} (get-comment-thread conn thread-id)]
                      (files/check-comment-permissions! conn profile-id file-id share-id)
                      (when-not (= owner-id profile-id)
                        (ex/raise :type :validation
                                  :code :not-allowed))
                      (db/delete! conn :comment {:id id})
                      nil))))

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
   ::sm/params schema:update-comment-thread-position}
  [cfg {:keys [::rpc/profile-id ::rpc/request-at id position frame-id share-id]}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::sql/for-update true)]
                      (files/check-comment-permissions! conn profile-id file-id share-id)
                      (db/update! conn :comment-thread
                                  {:modified-at request-at
                                   :position (db/pgpoint position)
                                   :frame-id frame-id}
                                  {:id (:id thread)})
                      nil))))

;; --- COMMAND: Update comment frame

(def ^:private
  schema:update-comment-thread-frame
  [:map {:title "update-comment-thread-frame"}
   [:id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread-frame
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-frame}
  [cfg {:keys [::rpc/profile-id ::rpc/request-at id frame-id share-id]}]
  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::sql/for-update true)]
                      (files/check-comment-permissions! conn profile-id file-id share-id)
                      (db/update! conn :comment-thread
                                  {:modified-at request-at
                                   :frame-id frame-id}
                                  {:id id})
                      nil))))
