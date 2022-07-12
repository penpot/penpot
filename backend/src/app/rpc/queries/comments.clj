;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.comments
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(defn decode-row
  [{:keys [participants position] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))))

;; --- Query: Comment Threads

(declare retrieve-comment-threads)

(s/def ::team-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::comment-threads
  (s/and (s/keys :req-un [::profile-id]
                 :opt-un [::file-id ::share-id ::team-id])
         #(or (:file-id %) (:team-id %))))

(sv/defmethod ::comment-threads
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

(defn- retrieve-comment-threads
  [conn {:keys [profile-id file-id share-id]}]
  (files/check-comment-permissions! conn profile-id file-id share-id)
  (->> (db/exec! conn [sql:comment-threads profile-id file-id])
       (into [] (map decode-row))))


;; --- Query: Unread Comment Threads

(declare retrieve-unread-comment-threads)

(s/def ::team-id ::us/uuid)
(s/def ::unread-comment-threads
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::unread-comment-threads
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


;; --- Query: Single Comment Thread

(s/def ::id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::comment-thread
  (s/keys :req-un [::profile-id ::file-id ::id]
          :opt-un [::share-id]))

(sv/defmethod ::comment-thread
  [{:keys [pool] :as cfg} {:keys [profile-id file-id id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (let [sql (str "with threads as (" sql:comment-threads ")"
                   "select * from threads where id = ?")]
      (-> (db/exec-one! conn [sql profile-id file-id id])
          (decode-row)))))

;; --- Query: Comments

(declare retrieve-comments)

(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::thread-id ::us/uuid)
(s/def ::comments
  (s/keys :req-un [::profile-id ::thread-id]
          :opt-un [::share-id]))

(sv/defmethod ::comments
  [{:keys [pool] :as cfg} {:keys [profile-id thread-id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (let [thread (db/get-by-id conn :comment-thread thread-id)]
      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)
      (retrieve-comments conn thread-id))))

(def sql:comments
  "select c.* from comment as c
    where c.thread_id = ?
    order by c.created_at asc")

(defn- retrieve-comments
  [conn thread-id]
  (->> (db/exec! conn [sql:comments thread-id])
       (into [] (map decode-row))))

;; file-comments-users

(declare retrieve-file-comments-users)

(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::file-comments-users
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::share-id]))

(sv/defmethod ::file-comments-users
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id]}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (retrieve-file-comments-users conn file-id profile-id)))

(def sql:file-comment-users
  "select p.id,
          p.email,
          p.fullname as name,
          p.fullname as fullname,
          p.photo_id,
          p.is_active
     from profile p
     where p.id in
       (select owner_id from comment
          where thread_id in
            (select id from comment_thread
              where file_id=?))
     or p.id=?
   ") ;; all the users that had comment the file, plus the current user

(defn retrieve-file-comments-users
  [conn file-id profile-id]
  (db/exec! conn [sql:file-comment-users file-id profile-id]))
