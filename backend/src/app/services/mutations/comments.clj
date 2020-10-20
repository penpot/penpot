;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.mutations.comments
  (:require
   [clojure.spec.alpha :as s]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.services.mutations :as sm]
   [app.services.queries.projects :as proj]
   [app.services.queries.files :as files]
   [app.services.queries.comments :as comments]
   [app.tasks :as tasks]
   [app.util.blob :as blob]
   [app.util.storage :as ust]
   [app.util.transit :as t]
   [app.util.time :as dt]))

;; --- Mutation: Create Comment Thread

(declare upsert-comment-thread-status!)
(declare create-comment-thread)

(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::position ::us/point)
(s/def ::content ::us/string)
(s/def ::page-id ::us/uuid)

(s/def ::create-comment-thread
  (s/keys :req-un [::profile-id ::file-id ::position ::content ::page-id]))

(sm/defmutation ::create-comment-thread
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-read-permissions! conn profile-id file-id)
    (create-comment-thread conn params)))

(defn- retrieve-next-seqn
  [conn file-id]
  (let [sql "select (f.comment_thread_seqn + 1) as next_seqn from file as f where f.id = ?"
        res (db/exec-one! conn [sql file-id])]
    (:next-seqn res)))

(defn- create-comment-thread*
  [conn {:keys [profile-id file-id page-id position content] :as params}]
  (let [seqn (retrieve-next-seqn conn file-id)
        now  (dt/now)

        thread  (db/insert! conn :comment-thread
                           {:file-id file-id
                            :owner-id profile-id
                            :participants (db/tjson #{profile-id})
                            :page-id page-id
                            :created-at now
                            :modified-at now
                            :seqn seqn
                            :position (db/pgpoint position)})
        ;; Create a comment entry
        comment (db/insert! conn :comment
                            {:thread-id (:id thread)
                             :owner-id profile-id
                             :created-at now
                             :modified-at now
                             :content content})]

    ;; Make the current thread as read.
    (upsert-comment-thread-status! conn profile-id (:id thread))

    ;; Optimistic update of current seq number on file.
    (db/update! conn :file
                {:comment-thread-seqn seqn}
                {:id file-id})

    (-> (assoc thread
               :content content
               :comment comment)
        (comments/decode-row))))

(defn- create-comment-thread
  [conn params]
  (loop [sp (db/savepoint conn)
         rc 0]
    (let [res (ex/try (create-comment-thread* conn params))]
      (cond
        (and (instance? Throwable res)
             (< rc 3))
        (do
          (db/rollback! conn sp)
          (recur (db/savepoint conn)
                 (inc rc)))

        (instance? Throwable res)
        (throw res)

        :else res))))


;; --- Mutation: Update Comment Thread Status

(s/def ::id ::us/uuid)

(s/def ::update-comment-thread-status
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::update-comment-thread-status
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [cthr (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not cthr
        (ex/raise :type :not-found))

      (files/check-read-permissions! conn profile-id (:file-id cthr))
      (upsert-comment-thread-status! conn profile-id (:id cthr)))))

(def sql:upsert-comment-thread-status
  "insert into comment_thread_status (thread_id, profile_id)
   values (?, ?)
       on conflict (thread_id, profile_id)
       do update set modified_at = clock_timestamp()
   returning modified_at;")

(defn- upsert-comment-thread-status!
  [conn profile-id thread-id]
  (db/exec-one! conn [sql:upsert-comment-thread-status thread-id profile-id]))


;; --- Mutation: Update Comment Thread

(s/def ::is-resolved ::us/boolean)
(s/def ::update-comment-thread
  (s/keys :req-un [::profile-id ::id ::is-resolved]))

(sm/defmutation ::update-comment-thread
  [{:keys [profile-id id is-resolved] :as params}]
  (db/with-atomic [conn db/pool]
    (let [thread (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not thread
        (ex/raise :type :not-found)

      (files/check-read-permissions! conn profile-id (:file-id thread))

      (db/update! conn :comment-thread
                  {:is-resolved is-resolved}
                  {:id id})
      nil))))


;; --- Mutation: Add Comment

(s/def ::add-comment
  (s/keys :req-un [::profile-id ::thread-id ::content]))

(sm/defmutation ::add-comment
  [{:keys [profile-id thread-id content] :as params}]
  (db/with-atomic [conn db/pool]
    (let [thread (-> (db/get-by-id conn :comment-thread thread-id {:for-update true})
                     (comments/decode-row))]

      ;; Standard Checks
      (when-not thread
        (ex/raise :type :not-found))

      (files/check-read-permissions! conn profile-id (:file-id thread))

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
        ;; helper bacause currently the helper does not allow pass raw
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
        comment))))


;; --- Mutation: Update Comment

(s/def ::update-comment
  (s/keys :req-un [::profile-id ::id ::content]))

(sm/defmutation ::update-comment
  [{:keys [profile-id id content] :as params}]
  (db/with-atomic [conn db/pool]
    (let [comment (db/get-by-id conn :comment id {:for-update true})
          _       (when-not comment (ex/raise :type :not-found))
          thread  (db/get-by-id conn :comment-thread (:thread-id comment) {:for-update true})
          _       (when-not thread (ex/raise :type :not-found))]

      (files/check-read-permissions! conn profile-id (:file-id thread))
      (db/update! conn :comment
                  {:content content
                   :modified-at (dt/now)}
                  {:id (:id comment)})
      (db/update! conn :comment-thread
                  {:modified-at (dt/now)}
                  {:id (:id thread)})
      nil)))


;; --- Mutation: Delete Comment Thread

(s/def ::delete-comment-thread
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-comment-thread
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [cthr (db/get-by-id conn :comment-thread id {:for-update true})]
      (when-not (= (:owner-id cthr) profile-id)
        (ex/raise :type :validation
                  :code :not-allowed))
      (db/delete! conn :comment-thread {:id id})
      nil)))

;; --- Mutation: Delete comment

(s/def ::delete-comment
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-comment
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [comment (db/get-by-id conn :comment id {:for-update true})]
      (when-not (= (:owner-id comment) profile-id)
        (ex/raise :type :validation
                  :code :not-allowed))

      (db/delete! conn :comment {:id id}))))
