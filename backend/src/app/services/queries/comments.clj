;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.queries.comments
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.services.queries :as sq]
   [app.services.queries.files :as files]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [promesa.core :as p]))

(defn decode-row
  [{:keys [participants position] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))))

;; --- Query: Comment Threads

(declare retrieve-comment-threads)

(s/def ::file-id ::us/uuid)
(s/def ::comment-threads
  (s/keys :req-un [::profile-id ::file-id]))

(sq/defquery ::comment-threads
  [{:keys [profile-id file-id] :as params}]
  (with-open [conn (db/open)]
    (files/check-read-permissions! conn profile-id file-id)
    (retrieve-comment-threads conn params)))

(def sql:comment-threads
  "select distinct on (ct.id)
          ct.*,
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
     left join comment_thread_status as cts
            on (cts.thread_id = ct.id and
                cts.profile_id = ?)
    where ct.file_id = ?
   window w as (partition by c.thread_id order by c.created_at asc)")

(defn- retrieve-comment-threads
  [conn {:keys [profile-id file-id]}]
  (->> (db/exec! conn [sql:comment-threads profile-id file-id])
       (into [] (map decode-row))))

;; --- Query: Single Comment Thread

(s/def ::id ::us/uuid)
(s/def ::comment-thread
  (s/keys :req-un [::profile-id ::file-id ::id]))

(sq/defquery ::comment-thread
  [{:keys [profile-id file-id id] :as params}]
  (with-open [conn (db/open)]
    (files/check-read-permissions! conn profile-id file-id)
    (let [sql (str "with threads as (" sql:comment-threads ")"
                   "select * from threads where id = ?")]
      (-> (db/exec-one! conn [sql profile-id file-id id])
          (decode-row)))))


;; --- Query: Comments

(declare retrieve-comments)

(s/def ::file-id ::us/uuid)
(s/def ::thread-id ::us/uuid)
(s/def ::comments
  (s/keys :req-un [::profile-id ::thread-id]))

(sq/defquery ::comments
  [{:keys [profile-id thread-id] :as params}]
  (with-open [conn (db/open)]
    (let [thread (db/get-by-id conn :comment-thread thread-id)]
      (files/check-read-permissions! conn profile-id (:file-id thread))
      (retrieve-comments conn thread-id))))

(def sql:comments
  "select c.* from comment as c
    where c.thread_id = ?
    order by c.created_at asc")

(defn- retrieve-comments
  [conn thread-id]
  (->> (db/exec! conn [sql:comments thread-id])
       (into [] (map decode-row))))
