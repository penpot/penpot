;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.comments
  (:require
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.teams :as teams]
   [app.rpc.retry :as retry]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUERY COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn decode-row
  [{:keys [participants position] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))))

;; --- COMMAND: Get Comment Threads

(declare retrieve-comment-threads)

(s/def ::team-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::get-comment-threads
  (s/and (s/keys :req-un [::profile-id]
                 :opt-un [::file-id ::share-id ::team-id])
         #(or (:file-id %) (:team-id %))))

(sv/defmethod ::get-comment-threads
  [{:keys [pool] :as cfg} params]
  (with-open [conn (db/open pool)]
    (retrieve-comment-threads conn params)))

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

(defn retrieve-comment-threads
  [conn {:keys [profile-id file-id share-id]}]
  (files/check-comment-permissions! conn profile-id file-id share-id)
  (->> (db/exec! conn [sql:comment-threads profile-id file-id])
       (into [] (map decode-row))))

;; --- COMMAND: Get Unread Comment Threads

(declare retrieve-unread-comment-threads)

(s/def ::team-id ::us/uuid)
(s/def ::get-unread-comment-threads
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::get-unread-comment-threads
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (retrieve-unread-comment-threads conn params)))

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

(defn retrieve-unread-comment-threads
  [conn {:keys [profile-id team-id]}]
  (->> (db/exec! conn [sql:unread-comment-threads-by-team profile-id team-id])
       (into [] (map decode-row))))


;; --- COMMAND: Get Single Comment Thread

(s/def ::id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::get-comment-thread
  (s/keys :req-un [::profile-id ::file-id ::id]
          :opt-un [::share-id]))

(sv/defmethod ::get-comment-thread
  [{:keys [pool] :as cfg} {:keys [profile-id file-id id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (let [sql (str "with threads as (" sql:comment-threads ")"
                   "select * from threads where id = ?")]
      (-> (db/exec-one! conn [sql profile-id file-id id])
          (decode-row)))))

(defn get-comment-thread
  [conn {:keys [profile-id file-id id] :as params}]
  (let [sql (str "with threads as (" sql:comment-threads ")"
                 "select * from threads where id = ?")]
    (-> (db/exec-one! conn [sql profile-id file-id id])
        (decode-row))))

;; --- COMMAND: Retrieve Comments

(declare get-comments)

(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::thread-id ::us/uuid)
(s/def ::get-comments
  (s/keys :req-un [::profile-id ::thread-id]
          :opt-un [::share-id]))

(sv/defmethod ::get-comments
  [{:keys [pool] :as cfg} {:keys [profile-id thread-id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (let [thread (db/get-by-id conn :comment-thread thread-id)]
      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id))
    (get-comments conn thread-id)))

(def sql:comments
  "select c.* from comment as c
    where c.thread_id = ?
    order by c.created_at asc")

(defn get-comments
  [conn thread-id]
  (->> (db/query conn :comment
                 {:thread-id thread-id}
                 {:order-by [[:created-at :asc]]})
       (into [] (map decode-row))))

;; --- COMMAND: Get file comments users

(declare get-file-comments-users)

(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::get-profiles-for-file-comments
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::share-id]))

(sv/defmethod ::get-profiles-for-file-comments
  "Retrieves a list of profiles with limited set of properties of all
  participants on comment threads of the file."
  {::doc/added "1.15"
   ::doc/changes ["1.15" "Imported from queries and renamed."]}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id]}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (get-file-comments-users conn file-id profile-id)))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- COMMAND: Create Comment Thread

(declare upsert-comment-thread-status!)
(declare create-comment-thread)
(declare retrieve-page-name)

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::profile-id ::us/uuid)
(s/def ::position ::gpt/point)
(s/def ::content ::us/string)
(s/def ::frame-id ::us/uuid)

(s/def ::create-comment-thread
  (s/keys :req-un [::profile-id ::file-id ::position ::content ::page-id ::frame-id]
          :opt-un [::share-id]))

(sv/defmethod ::create-comment-thread
  {::retry/max-retries 3
   ::retry/matches retry/conflict-db-insert?
   ::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (create-comment-thread conn params)))

(defn- retrieve-next-seqn
  [conn file-id]
  (let [sql "select (f.comment_thread_seqn + 1) as next_seqn from file as f where f.id = ?"
        res (db/exec-one! conn [sql file-id])]
    (:next-seqn res)))

(defn create-comment-thread
  [conn {:keys [profile-id file-id page-id position content frame-id] :as params}]
  (let [seqn    (retrieve-next-seqn conn file-id)
        now     (dt/now)
        pname   (retrieve-page-name conn params)
        thread  (db/insert! conn :comment-thread
                           {:file-id file-id
                            :owner-id profile-id
                            :participants (db/tjson #{profile-id})
                            :page-name pname
                            :page-id page-id
                            :created-at now
                            :modified-at now
                            :seqn seqn
                            :position (db/pgpoint position)
                            :frame-id frame-id})]


    ;; Create a comment entry
    (db/insert! conn :comment
                {:thread-id (:id thread)
                 :owner-id profile-id
                 :created-at now
                 :modified-at now
                 :content content})

    ;; Make the current thread as read.
    (upsert-comment-thread-status! conn profile-id (:id thread))

    ;; Optimistic update of current seq number on file.
    (db/update! conn :file
                {:comment-thread-seqn seqn}
                {:id file-id})

    (select-keys thread [:id :file-id :page-id])))

(defn- retrieve-page-name
  [conn {:keys [file-id page-id]}]
  (let [{:keys [data]} (db/get-by-id conn :file file-id)
        data           (blob/decode data)]
    (get-in data [:pages-index page-id :name])))


;; --- COMMAND: Update Comment Thread Status

(s/def ::id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::update-comment-thread-status
  (s/keys :req-un [::profile-id ::id]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread-status
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [cthr (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not cthr
        (ex/raise :type :not-found))

      (files/check-comment-permissions! conn profile-id (:file-id cthr) share-id)
      (upsert-comment-thread-status! conn profile-id (:id cthr)))))

(def sql:upsert-comment-thread-status
  "insert into comment_thread_status (thread_id, profile_id)
   values (?, ?)
       on conflict (thread_id, profile_id)
       do update set modified_at = clock_timestamp()
   returning modified_at;")

(defn upsert-comment-thread-status!
  [conn profile-id thread-id]
  (db/exec-one! conn [sql:upsert-comment-thread-status thread-id profile-id]))


;; --- COMMAND: Update Comment Thread

(s/def ::is-resolved ::us/boolean)
(s/def ::update-comment-thread
  (s/keys :req-un [::profile-id ::id ::is-resolved]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id is-resolved share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [thread (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not thread
        (ex/raise :type :not-found))

      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)

      (db/update! conn :comment-thread
                  {:is-resolved is-resolved}
                  {:id id})
      nil)))


;; --- COMMAND: Add Comment

(declare create-comment)

(s/def ::create-comment
  (s/keys :req-un [::profile-id ::thread-id ::content]
          :opt-un [::share-id]))

(sv/defmethod ::create-comment
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (create-comment conn params)))

(defn create-comment
  [conn {:keys [profile-id thread-id content share-id] :as params}]
  (let [thread (-> (db/get-by-id conn :comment-thread thread-id {:for-update true})
                   (decode-row))
        pname  (retrieve-page-name conn thread)]

    ;; Standard Checks
    (when-not thread (ex/raise :type :not-found))

    ;; Permission Checks
    (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)

    ;; Update the page-name cachedattribute on comment thread table.
    (when (not= pname (:page-name thread))
      (db/update! conn :comment-thread
                  {:page-name pname}
                  {:id thread-id}))

    ;; NOTE: is important that all timestamptz related fields are
    ;; created or updated on the database level for avoid clock
    ;; inconsistencies (some user sees something read that is not
    ;; read, etc...)
    (let [ppants  (:participants thread #{})
          comment (db/insert! conn :comment
                              {:thread-id thread-id
                               :owner-id profile-id
                               :content content})]

      ;; NOTE: this is done in SQL instead of using db/update!
      ;; helper because currently the helper does not allow pass raw
      ;; function call parameters to the underlying prepared
      ;; statement; in a future when we fix/improve it, this can be
      ;; changed to use the helper.

      ;; Update thread modified-at attribute and assoc the current
      ;; profile to the participant set.
      (let [ppants (conj ppants profile-id)
            sql    "update comment_thread
                         set modified_at = clock_timestamp(),
                             participants = ?
                       where id = ?"]
        (db/exec-one! conn [sql (db/tjson ppants) thread-id]))

      ;; Update the current profile status in relation to the
      ;; current thread.
      (upsert-comment-thread-status! conn profile-id thread-id)

      ;; Return the created comment object.
      comment)))

;; --- COMMAND: Update Comment

(declare update-comment)

(s/def ::update-comment
  (s/keys :req-un [::profile-id ::id ::content]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (update-comment conn params)))

(defn update-comment
  [conn {:keys [profile-id id content share-id] :as params}]
  (let [comment (db/get-by-id conn :comment id {:for-update true})
        _       (when-not comment (ex/raise :type :not-found))
        thread  (db/get-by-id conn :comment-thread (:thread-id comment) {:for-update true})
        _       (when-not thread (ex/raise :type :not-found))
        pname   (retrieve-page-name conn thread)]

    (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)

    ;; Don't allow edit comments to not owners
    (when-not (= (:owner-id thread) profile-id)
      (ex/raise :type :validation
                :code :not-allowed))

    (db/update! conn :comment
                {:content content
                 :modified-at (dt/now)}
                {:id (:id comment)})

    (db/update! conn :comment-thread
                {:modified-at (dt/now)
                 :page-name pname}
                {:id (:id thread)})
    nil))


;; --- COMMAND: Delete Comment Thread

(s/def ::delete-comment-thread
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::delete-comment-thread
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [thread (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not (= (:owner-id thread) profile-id)
        (ex/raise :type :validation
                  :code :not-allowed))
      (db/delete! conn :comment-thread {:id id})
      nil)))


;; --- COMMAND: Delete comment

(s/def ::delete-comment
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::delete-comment
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [comment (db/get-by-id conn :comment id {:for-update true})]
      (when-not (= (:owner-id comment) profile-id)
        (ex/raise :type :validation
                  :code :not-allowed))

      (db/delete! conn :comment {:id id}))))

;; --- COMMAND: Update comment thread position

(s/def ::update-comment-thread-position
  (s/keys :req-un [::profile-id ::id ::position ::frame-id]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread-position
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id position frame-id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [thread (db/get-by-id conn :comment-thread id {:for-update true})]
      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)
      (db/update! conn :comment-thread
                  {:modified-at (dt/now)
                   :position (db/pgpoint position)
                   :frame-id frame-id}
                  {:id (:id thread)})
      nil)))

;; --- COMMAND: Update comment frame

(s/def ::update-comment-thread-frame
  (s/keys :req-un [::profile-id ::id ::frame-id]
          :opt-un [::share-id]))

(sv/defmethod ::update-comment-thread-frame
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id frame-id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [thread (db/get-by-id conn :comment-thread id {:for-update true})]
      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)
      (db/update! conn :comment-thread
                  {:modified-at (dt/now)
                   :frame-id frame-id}
                  {:id (:id thread)})
      nil)))

