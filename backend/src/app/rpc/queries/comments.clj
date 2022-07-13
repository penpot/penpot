;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.comments
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.commands.comments :as cmd.comments]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(defn decode-row
  [{:keys [participants position] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))))

;; --- QUERY: Comment Threads

(s/def ::team-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::comment-threads
  (s/and (s/keys :req-un [::profile-id]
                 :opt-un [::file-id ::share-id ::team-id])
         #(or (:file-id %) (:team-id %))))

(sv/defmethod ::comment-threads
  {::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} params]
  (with-open [conn (db/open pool)]
    (cmd.comments/retrieve-comment-threads conn params)))


;; --- QUERY: Unread Comment Threads

(s/def ::team-id ::us/uuid)
(s/def ::unread-comment-threads
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::unread-comment-threads
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (cmd.comments/retrieve-unread-comment-threads conn params)))

;; --- QUERY: Single Comment Thread

(s/def ::id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::comment-thread
  (s/keys :req-un [::profile-id ::file-id ::id]
          :opt-un [::share-id]))

(sv/defmethod ::comment-thread
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (let [sql (str "with threads as (" cmd.comments/sql:comment-threads ")"
                   "select * from threads where id = ?")]
      (-> (db/exec-one! conn [sql profile-id file-id id])
          (decode-row)))))

;; --- QUERY: Comments

(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::thread-id ::us/uuid)
(s/def ::comments
  (s/keys :req-un [::profile-id ::thread-id]
          :opt-un [::share-id]))

(sv/defmethod ::comments
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id thread-id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (let [thread (db/get-by-id conn :comment-thread thread-id)]
      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id)
      (cmd.comments/retrieve-comments conn thread-id))))

;; --- QUERY: Get file comments users

(s/def ::file-id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::file-comments-users
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::share-id]))

(sv/defmethod ::file-comments-users
  {::doc/deprecated "1.15"
   ::doc/added "1.13"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id]}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (cmd.comments/retrieve-file-comments-users conn file-id profile-id)))
