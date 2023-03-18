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
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
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
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

;; --- GENERAL PURPOSE INTERNAL HELPERS

(defn decode-row
  [{:keys [participants position] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))))

(def sql:get-file
  "select f.id, f.modified_at, f.revn, f.features,
          f.project_id, p.team_id, f.data
     from file as f
     join project as p on (p.id = f.project_id)
    where f.id = ?
      and f.deleted_at is null")

(defn- get-file
  "A specialized version of get-file for comments module."
  [conn file-id page-id]
  (binding [pmap/*load-fn* (partial files/load-pointer conn file-id)]
    (if-let [{:keys [data] :as file} (some-> (db/exec-one! conn [sql:get-file file-id]) (files/decode-row))]
      (-> file
          (assoc :page-name (dm/get-in data [:pages-index page-id :name]))
          (assoc :page-id page-id))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "file not found"))))

(defn- get-comment-thread
  [conn thread-id & {:as opts}]
  (-> (db/get-by-id conn :comment-thread thread-id opts)
      (decode-row)))

(defn- get-comment
  [conn comment-id & {:keys [for-update?]}]
  (db/get-by-id conn :comment comment-id {:for-update for-update?}))

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

(s/def ::team-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::get-comment-threads
  (s/and (s/keys :req [::rpc/profile-id]
                 :opt-un [::file-id ::share-id ::team-id])
         #(or (:file-id %) (:team-id %))))

(sv/defmethod ::get-comment-threads
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id share-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (get-comment-threads conn profile-id file-id)))

(def sql:comment-threads
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
       (into [] (map decode-row))))

;; --- COMMAND: Get Unread Comment Threads

(declare ^:private get-unread-comment-threads)

(s/def ::team-id ::us/uuid)
(s/def ::get-unread-comment-threads
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(sv/defmethod ::get-unread-comment-threads
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (get-unread-comment-threads conn profile-id team-id)))

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
       (into [] (map decode-row))))


;; --- COMMAND: Get Single Comment Thread

(s/def ::get-comment-thread
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::us/id]
          :opt-un [::share-id]))

(sv/defmethod ::get-comment-thread
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id id share-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (let [sql (str "with threads as (" sql:comment-threads ")"
                   "select * from threads where id = ?")]
      (-> (db/exec-one! conn [sql profile-id file-id id])
          (decode-row)))))

;; --- COMMAND: Retrieve Comments

(declare ^:private get-comments)

(s/def ::thread-id ::us/uuid)
(s/def ::get-comments
  (s/keys :req [::rpc/profile-id]
          :req-un [::thread-id]
          :opt-un [::share-id]))

(sv/defmethod ::get-comments
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id thread-id share-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (let [{:keys [file-id] :as thread} (get-comment-thread conn thread-id)]
      (files/check-comment-permissions! conn profile-id file-id share-id)
      (get-comments conn thread-id))))

(def sql:comments
  "select c.* from comment as c
    where c.thread_id = ?
    order by c.created_at asc")

(defn- get-comments
  [conn thread-id]
  (->> (db/query conn :comment
                 {:thread-id thread-id}
                 {:order-by [[:created-at :asc]]})
       (into [] (map decode-row))))

;; --- COMMAND: Get file comments users

;; All the profiles that had comment the file, plus the current
;; profile.

(def sql:file-comment-users
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

(s/def ::get-profiles-for-file-comments
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::share-id]))

(sv/defmethod ::get-profiles-for-file-comments
  "Retrieves a list of profiles with limited set of properties of all
  participants on comment threads of the file."
  {::doc/added "1.15"
   ::doc/changes ["1.15" "Imported from queries and renamed."]}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id share-id]}]
  (dm/with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (get-file-comments-users conn file-id profile-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private create-comment-thread)

;; --- COMMAND: Create Comment Thread

(s/def ::page-id ::us/uuid)
(s/def ::position ::gpt/point)
(s/def ::content ::us/string)
(s/def ::frame-id ::us/uuid)

(s/def ::create-comment-thread
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::position ::content ::page-id ::frame-id]
          :opt-un [::share-id]))

(sv/defmethod ::create-comment-thread
  {::doc/added "1.15"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg}
   {:keys [::rpc/profile-id ::rpc/request-at file-id page-id share-id position content frame-id]}]

  (db/with-atomic [conn pool]
    (let [{:keys [team-id project-id page-name] :as file} (get-file conn file-id page-id)]
      (files/check-comment-permissions! conn profile-id file-id share-id)

      (run! (partial quotes/check-quote! conn)
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

      (rtry/with-retry {::rtry/when rtry/conflict-exception?
                        ::rtry/max-retries 3
                        ::rtry/label "create-comment-thread"
                        ::db/conn conn}
        (create-comment-thread conn
                               {:created-at request-at
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

(s/def ::id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::update-comment-thread-status
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread-status
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::db/for-update? true)]
      (files/check-comment-permissions! conn profile-id file-id share-id)
      (upsert-comment-thread-status! conn profile-id id))))


;; --- COMMAND: Update Comment Thread

(s/def ::is-resolved ::us/boolean)
(s/def ::update-comment-thread
  (s/keys :req [::rpc/profile-id]
          :req-un [::id ::is-resolved]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id is-resolved share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::db/for-update? true)]
      (files/check-comment-permissions! conn profile-id file-id share-id)
      (db/update! conn :comment-thread
                  {:is-resolved is-resolved}
                  {:id id})
      nil)))


;; --- COMMAND: Add Comment

(declare get-comment-thread)
(declare create-comment)

(s/def ::create-comment
  (s/keys :req [::rpc/profile-id]
          :req-un [::thread-id ::content]
          :opt-un [::share-id]))

(sv/defmethod ::create-comment
  {::doc/added "1.15"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id ::rpc/request-at thread-id share-id content] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [file-id page-id] :as thread} (get-comment-thread conn thread-id ::db/for-update? true)
          {:keys [team-id project-id page-name] :as file} (get-file conn file-id page-id)]

      (files/check-comment-permissions! conn profile-id (:id file) share-id)
      (quotes/check-quote! conn
                           {::quotes/id ::quotes/comments-per-file
                            ::quotes/profile-id profile-id
                            ::quotes/team-id team-id
                            ::quotes/project-id project-id
                            ::quotes/file-id (:id file)})

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

        (vary-meta comment assoc ::audit/props props)))))

;; --- COMMAND: Update Comment

(s/def ::update-comment
  (s/keys :req [::rpc/profile-id]
          :req-un [::id ::content]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id ::rpc/request-at id share-id content] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [thread-id] :as comment} (get-comment conn id ::db/for-update? true)
          {:keys [file-id page-id owner-id] :as thread} (get-comment-thread conn thread-id ::db/for-update? true)]

      (files/check-comment-permissions! conn profile-id file-id share-id)

      ;; Don't allow edit comments to not owners
      (when-not (= owner-id profile-id)
        (ex/raise :type :validation
                  :code :not-allowed))

      (let [{:keys [page-name] :as file} (get-file conn file-id page-id)]
        (db/update! conn :comment
                    {:content content
                     :modified-at request-at}
                    {:id id})

        (db/update! conn :comment-thread
                    {:modified-at request-at
                     :page-name page-name}
                    {:id thread-id})
        nil))))

;; --- COMMAND: Delete Comment Thread

(s/def ::delete-comment-thread
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]
          :opt-un [::share-id]))

(sv/defmethod ::delete-comment-thread
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [owner-id file-id] :as thread} (get-comment-thread conn id ::db/for-update? true)]
      (files/check-comment-permissions! conn profile-id file-id share-id)
      (when-not (= owner-id profile-id)
        (ex/raise :type :validation
                  :code :not-allowed))

      (db/delete! conn :comment-thread {:id id})
      nil)))

;; --- COMMAND: Delete comment

(s/def ::delete-comment
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]
          :opt-un [::share-id]))

(sv/defmethod ::delete-comment
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [owner-id thread-id] :as comment} (get-comment conn id ::db/for-update? true)
          {:keys [file-id] :as thread} (get-comment-thread conn thread-id)]
      (files/check-comment-permissions! conn profile-id file-id share-id)
      (when-not (= owner-id profile-id)
        (ex/raise :type :validation
                  :code :not-allowed))
      (db/delete! conn :comment {:id id}))))


;; --- COMMAND: Update comment thread position

(s/def ::update-comment-thread-position
  (s/keys :req [::rpc/profile-id]
          :req-un [::id ::position ::frame-id]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread-position
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id position frame-id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::db/for-update? true)]
      (files/check-comment-permissions! conn profile-id file-id share-id)
      (db/update! conn :comment-thread
                  {:modified-at (::rpc/request-at params)
                   :position (db/pgpoint position)
                   :frame-id frame-id}
                  {:id (:id thread)})
      nil)))

;; --- COMMAND: Update comment frame

(s/def ::update-comment-thread-frame
  (s/keys :req [::rpc/profile-id]
          :req-un [::id ::frame-id]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread-frame
  {::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id frame-id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [file-id] :as thread} (get-comment-thread conn id ::db/for-update? true)]
      (files/check-comment-permissions! conn profile-id file-id share-id)
      (db/update! conn :comment-thread
                  {:modified-at (::rpc/request-at params)
                   :frame-id frame-id}
                  {:id id})
      nil)))
