;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.commands.comments
  (:require
   [app.common.spec :as us]
   [app.common.pages.changes :as changes]
   [app.db :as db]
   [app.http.doc :as doc]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

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

;; --- COMMAND: Comments

(declare retrieve-comments)

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
      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)
      (retrieve-comments conn thread-id))))

(def sql:comments
  "select c.* from comment as c
    where c.thread_id = ?
    order by c.created_at asc")

(defn retrieve-comments
  [conn thread-id]
  (->> (db/exec! conn [sql:comments thread-id])
       (into [] (map decode-row))))

;; --- COMMAND: Get file comments users

(declare retrieve-file-comments-users)

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
    (retrieve-file-comments-users conn file-id profile-id)))

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

(defn retrieve-file-comments-users
  [conn file-id profile-id]
  (db/exec! conn [sql:file-comment-users file-id profile-id]))
