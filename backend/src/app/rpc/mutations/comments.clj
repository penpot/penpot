;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.comments
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.commands.comments :as cmd.comments]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.files :as files]
   [app.rpc.retry :as retry]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Mutation: Create Comment Thread

(s/def ::create-comment-thread ::cmd.comments/create-comment-thread)

(sv/defmethod ::create-comment-thread
  {::retry/max-retries 3
   ::retry/matches retry/conflict-db-insert?
   ::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id share-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (cmd.comments/create-comment-thread conn params)))

;; --- Mutation: Update Comment Thread Status

(s/def ::id ::us/uuid)
(s/def ::share-id (s/nilable ::us/uuid))

(s/def ::update-comment-thread-status ::cmd.comments/update-comment-thread-status)

(sv/defmethod ::update-comment-thread-status
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id share-id] :as params}]
  (db/with-atomic [conn pool]
    (let [cthr (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not cthr (ex/raise :type :not-found))
      (files/check-comment-permissions! conn profile-id (:file-id cthr) share-id)
      (cmd.comments/upsert-comment-thread-status! conn profile-id (:id cthr)))))


;; --- Mutation: Update Comment Thread

(s/def ::update-comment-thread ::cmd.comments/update-comment-thread)

(sv/defmethod ::update-comment-thread
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
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


;; --- Mutation: Add Comment

(s/def ::add-comment ::cmd.comments/create-comment)

(sv/defmethod ::add-comment
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.comments/create-comment conn params)))


;; --- Mutation: Update Comment

(s/def ::update-comment ::cmd.comments/update-comment)

(sv/defmethod ::update-comment
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.comments/update-comment conn params)))


;; --- Mutation: Delete Comment Thread

(s/def ::delete-comment-thread ::cmd.comments/delete-comment-thread)

(sv/defmethod ::delete-comment-thread
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [thread (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not (= (:owner-id thread) profile-id)
        (ex/raise :type :validation :code :not-allowed))
      (db/delete! conn :comment-thread {:id id})
      nil)))


;; --- Mutation: Delete comment

(s/def ::delete-comment ::cmd.comments/delete-comment)

(sv/defmethod ::delete-comment
  {::doc/added "1.0"
   ::doc/deprecated "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [comment (db/get-by-id conn :comment id {:for-update true})]
      (when-not (= (:owner-id comment) profile-id)
        (ex/raise :type :validation :code :not-allowed))
      (db/delete! conn :comment {:id id}))))
