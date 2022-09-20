;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.comments
  (:require
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

(s/def ::comment-threads ::cmd.comments/get-comment-threads)

(sv/defmethod ::comment-threads
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} params]
  (with-open [conn (db/open pool)]
    (cmd.comments/retrieve-comment-threads conn params)))

;; --- QUERY: Unread Comment Threads

(s/def ::unread-comment-threads ::cmd.comments/get-unread-comment-threads)

(sv/defmethod ::unread-comment-threads
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (cmd.comments/retrieve-unread-comment-threads conn params)))

;; --- QUERY: Single Comment Thread

(s/def ::comment-thread ::cmd.comments/get-comment-thread)

(sv/defmethod ::comment-thread
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (cmd.comments/get-comment-thread conn params)))

;; --- QUERY: Comments

(s/def ::comments ::cmd.comments/get-comments)

(sv/defmethod ::comments
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id thread-id share-id] :as params}]
  (with-open [conn (db/open pool)]
    (let [thread (db/get-by-id conn :comment-thread thread-id)]
      (files/check-comment-permissions! conn profile-id (:file-id thread) share-id))
    (cmd.comments/get-comments conn thread-id)))


;; --- QUERY: Get file comments users

(s/def ::file-comments-users ::cmd.comments/get-profiles-for-file-comments)

(sv/defmethod ::file-comments-users
  {::doc/deprecated "1.15"
   ::doc/added "1.13"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id]}]
  (with-open [conn (db/open pool)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (cmd.comments/get-file-comments-users conn file-id profile-id)))
