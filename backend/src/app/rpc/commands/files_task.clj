;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.files-task
  "RPC commands for tasks: a piece of work on a file, assigned to someone
  and tied to a layer (see the `file_task` migration).

  Every task lives in a message of a comment thread, so its thread can
  go on: a task made from a message keeps that thread, and a task
  created on the canvas gets a thread whose first message is its title.
  A task may also belong to a design review. Everybody who can read the
  file may create a task and move it between states; every move is
  recorded as an event of the task with who made it, which is what the
  thread of the task shows between its comments.

  The merge of a branch moves its tasks into main (`move-branch-tasks`,
  called from `app.rpc.commands.files-branch`): the open ones keep going
  on main and the closed ones stay as the history of the branch."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as uri]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.email :as eml]
   [app.loggers.webhooks :as-alias webhooks]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.comments :as comments]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.util.services :as sv]
   [clojure.string :as str]))

(def statuses
  #{"open" "done" "reopened" "verified" "wont-do"})

(def closed-statuses
  "A closed task is finished: verified, or dropped as won't do."
  #{"verified" "wont-do"})

(def ^:private transitions
  "The moves a task can make, from each state."
  {"open"     #{"done" "wont-do"}
   "reopened" #{"done" "wont-do"}
   "done"     #{"verified" "reopened"}
   "verified" #{"reopened"}
   "wont-do"  #{"reopened"}})

(def ^:private title-max-length 250)

;; --- Helpers

(defn- check-tasks-enabled
  "Tasks ride on the branching feature, like design reviews."
  []
  (when-not (contains? cf/flags :branching)
    (ex/raise :type :restriction
              :code :branching-disabled
              :hint "the branching feature is not enabled on this instance")))

(defn- decode-row
  [{:keys [payload position] :as row}]
  (cond-> row
    (db/pgobject? payload) (assoc :payload (db/decode-transit-pgobject payload))
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))))

(defn- get-task
  "Fetch a task row or raise :not-found."
  [conn id & {:as opts}]
  (let [task (db/get* conn :file-task {:id id} opts)]
    (when (or (nil? task) (some? (:deleted-at task)))
      (ex/raise :type :not-found
                :code :task-not-found
                :hint "unable to find a task with the provided id"
                :task-id id))
    (decode-row task)))

(defn- insert-event
  [conn task-id profile-id type payload]
  (db/insert! conn :file-task-event
              {:id (uuid/next)
               :task-id task-id
               :profile-id profile-id
               :type type
               :payload (some-> payload db/tjson)
               :created-at (ct/now)}
              {::db/return-keys false}))

(defn- check-assignee
  "Refuse an assignee who is not a member of the team of the file."
  [conn team-id assignee-id]
  (when (some? assignee-id)
    (let [members (into #{} (map :id) (teams/get-team-members conn team-id))]
      (when-not (contains? members assignee-id)
        (ex/raise :type :validation
                  :code :assignee-not-team-member
                  :hint "the assignee must be a member of the team"
                  :profile-id assignee-id)))))

(defn- run-and-notify
  "Run `f` in a transaction, then tell the open clients of `file-id` that
  its tasks changed: the message leaves once the transaction committed,
  so a client that reloads on it reads the committed state."
  [{:keys [::mbus/msgbus] :as cfg} file-id profile-id f]
  (let [result (db/tx-run! cfg f)]
    (mbus/pub! msgbus
               :topic file-id
               :message {:type :file-tasks-change
                         :file-id file-id
                         :profile-id profile-id})
    result))

;; --- Helpers: emails

(defn- format-task-url
  [{:keys [id file-id page-id]} team-id]
  (str/join [(cf/get :public-uri)
             "/#/workspace?"
             (uri/map->query-string {:file-id file-id
                                     :page-id page-id
                                     :team-id team-id
                                     :task-id id})]))

(defn- send-task-assigned-email
  "Tell `assignee-id` that `actor-id` assigned the task to them. Nobody is
  told about the tasks they assign to themselves."
  [{:keys [::db/conn] :as cfg} team-id actor-id {:keys [assignee-id title] :as task}]
  (when (and (some? assignee-id) (not= assignee-id actor-id))
    (let [users (->> (teams/get-users+props conn team-id)
                     (map profile/decode-row)
                     (d/index-by :id))]
      (when-let [{:keys [fullname email]} (get users assignee-id)]
        (eml/send-template
         cfg
         {::eml/reuse-conn true
          ::eml/template "task-assigned"
          :public-uri (cf/get :public-uri)
          :to email
          :name fullname
          :source-user (get-in users [actor-id :fullname] "")
          :task-title title
          :task-url (format-task-url task team-id)})))))

;; --- COMMAND QUERY: get-file-tasks

(def ^:private schema:get-file-tasks
  [:map {:title "get-file-tasks"}
   [:file-id ::sm/uuid]])

(def ^:private sql:get-file-tasks
  "SELECT t.id,
          t.file_id,
          t.pull_request_id,
          coalesce(fpr.title, t.review_title) AS review_title,
          fpr.status AS review_status,
          t.thread_id,
          t.comment_id,
          ct.seqn AS thread_seqn,
          t.title,
          t.status,
          t.assignee_id,
          t.created_by,
          t.page_id,
          t.frame_id,
          t.shape_id,
          t.lost_shape_name,
          t.position,
          t.merge_id,
          t.branch_name,
          t.status_changed_at,
          t.status_changed_by,
          t.created_at,
          t.updated_at
     FROM file_task AS t
     LEFT JOIN file_pull_request AS fpr ON (fpr.id = t.pull_request_id)
     LEFT JOIN comment_thread AS ct ON (ct.id = t.thread_id)
    WHERE t.file_id = ?
      AND t.deleted_at IS NULL
    ORDER BY t.created_at ASC")

(defn get-file-tasks
  [conn file-id]
  (into [] (map decode-row) (db/exec! conn [sql:get-file-tasks file-id])))

(sv/defmethod ::get-file-tasks
  "List the tasks of a file, oldest first: the open ones and the history
  of the closed ones, the tasks of the branches merged into it included
  (those carry the merge that moved them, `merge-id`)."
  {::doc/added "2.16"
   ::sm/params schema:get-file-tasks}
  [cfg {:keys [::rpc/profile-id file-id]}]
  (check-tasks-enabled)
  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 (files/check-read-permissions! cfg profile-id file-id)
                 (get-file-tasks conn file-id))))

;; --- COMMAND QUERY: get-task-events

(def ^:private schema:get-task-events
  [:map {:title "get-task-events"}
   [:id ::sm/uuid]])

(def ^:private sql:get-task-events
  "SELECT e.id, e.task_id, e.profile_id, e.type, e.payload, e.created_at
     FROM file_task_event AS e
    WHERE e.task_id = ?
    ORDER BY e.created_at ASC")

(sv/defmethod ::get-task-events
  "What happened to a task, oldest first."
  {::doc/added "2.16"
   ::sm/params schema:get-task-events}
  [cfg {:keys [::rpc/profile-id id]}]
  (check-tasks-enabled)
  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 (let [task (get-task conn id)]
                   (files/check-read-permissions! cfg profile-id (:file-id task))
                   (into [] (map decode-row) (db/exec! conn [sql:get-task-events id]))))))

;; --- COMMAND: create-task

(def ^:private schema:create-task
  [:map {:title "create-task" :closed true}
   [:file-id ::sm/uuid]
   [:title [:string {:max title-max-length}]]
   [:page-id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:shape-id {:optional true} [:maybe ::sm/uuid]]
   ;; where its mark goes when it points at no layer, and where its
   ;; thread sits
   [:position ::gpt/point]
   [:assignee-id {:optional true} [:maybe ::sm/uuid]]
   ;; the design review the task is created in: an open pull request
   ;; of this file (the branch file it reviews)
   [:pull-request-id {:optional true} [:maybe ::sm/uuid]]
   ;; the comment thread the task comes from, which stays attached, and
   ;; the message of that thread it was made from; without them the
   ;; task gets a thread of its own
   [:thread-id {:optional true} [:maybe ::sm/uuid]]
   [:comment-id {:optional true} [:maybe ::sm/uuid]]])

(def ^:private sql:get-open-review-of-file
  "SELECT id, title FROM file_pull_request
    WHERE id = ?
      AND source_file_id = ?
      AND status = 'open'
      AND deleted_at IS NULL")

(def ^:private sql:get-task-of-comment
  "SELECT id FROM file_task
    WHERE comment_id = ?
      AND deleted_at IS NULL")

(defn- check-thread
  "Refuse a thread of another file."
  [conn thread-id file-id]
  (let [thread (db/get* conn :comment-thread {:id thread-id})]
    (when (or (nil? thread) (not= file-id (:file-id thread)))
      (ex/raise :type :validation
                :code :thread-not-in-file
                :hint "the comment thread does not belong to the file"
                :thread-id thread-id))))

(defn- check-comment
  "Refuse a message of another thread, and a message that already has a
  task: each message of a thread may be one task."
  [conn comment-id thread-id]
  (let [comment (db/get* conn :comment {:id comment-id})]
    (when (or (nil? comment) (not= thread-id (:thread-id comment)))
      (ex/raise :type :validation
                :code :comment-not-in-thread
                :hint "the comment does not belong to the thread"
                :comment-id comment-id))
    (when (db/exec-one! conn [sql:get-task-of-comment comment-id])
      (ex/raise :type :validation
                :code :comment-already-has-task
                :hint "the comment already has a task"
                :comment-id comment-id))))

(defn- create-task-thread
  "The thread of a task created on the canvas: its first message is the
  title, it points at the layer of the task (at the spot of the canvas
  when there is none) and belongs to the design review of the task."
  [cfg profile-id {:keys [file-id title page-id frame-id shape-id position pull-request-id]}]
  (comments/create-comment-thread
   cfg
   (cond-> {::rpc/profile-id profile-id
            ::rpc/request-at (ct/now)
            :file-id file-id
            :page-id page-id
            :frame-id frame-id
            :position position
            :content title
            :anchor (if (some? shape-id)
                      {:type :layer :shape-id shape-id}
                      {:type :canvas})}
     (some? pull-request-id) (assoc :pull-request-id pull-request-id))))

(sv/defmethod ::create-task
  "Create an open task on a file, in a message of a comment thread: the
  message it was made from (`thread-id` and `comment-id`), or the first
  message of a new thread that carries its title. A task created in a
  design review belongs to it. The assignee, when there is one, must be
  a member of the team and is told by email."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:create-task}
  [cfg {:keys [::rpc/profile-id file-id title page-id frame-id shape-id position assignee-id
               pull-request-id thread-id comment-id] :as params}]
  (check-tasks-enabled)
  (when (str/blank? title)
    (ex/raise :type :validation
              :code :invalid-task-title
              :hint "task title cannot be blank"))
  (files/check-read-permissions! cfg profile-id file-id)
  (run-and-notify
   cfg file-id profile-id
   (fn [{:keys [::db/conn] :as cfg}]
     (let [team-id (perms/get-team-id-for-file conn file-id)
           review  (when pull-request-id
                     (or (db/exec-one! conn [sql:get-open-review-of-file pull-request-id file-id])
                         (ex/raise :type :validation
                                   :code :pull-request-not-open
                                   :hint "the task names a design review that is not open on this file"
                                   :pull-request-id pull-request-id)))
           _       (when thread-id
                     (check-thread conn thread-id file-id))
           _       (when comment-id
                     (check-comment conn comment-id thread-id))
           _       (check-assignee conn team-id assignee-id)
           thread  (when (nil? thread-id)
                     (create-task-thread cfg profile-id (update params :title str/trim)))
           ts      (ct/now)
           task    {:id (uuid/next)
                    :position position
                    :file-id file-id
                    :pull-request-id pull-request-id
                    :review-title (:title review)
                    :thread-id (or thread-id (:id thread))
                    :comment-id (or comment-id (:comment-id thread))
                    :title (str/trim title)
                    :status "open"
                    :assignee-id assignee-id
                    :created-by profile-id
                    :page-id page-id
                    :frame-id frame-id
                    :shape-id shape-id
                    :created-at ts
                    :updated-at ts}]
       (db/insert! conn :file-task
                   (assoc task :position (db/pgpoint position))
                   {::db/return-keys false})
       (insert-event conn (:id task) profile-id "created" nil)
       (when (some? assignee-id)
         (insert-event conn (:id task) profile-id "assigned" {:assignee-id assignee-id}))
       (send-task-assigned-email cfg team-id profile-id task)
       task))))

;; --- COMMAND: update-task

(def ^:private schema:update-task
  [:map {:title "update-task" :closed true}
   [:id ::sm/uuid]
   [:title {:optional true} [:string {:max title-max-length}]]
   ;; nil leaves the task without an assignee
   [:assignee-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-task
  "Rename a task or change its assignee. Each change is an event of the
  task; a new assignee is told by email."
  {::doc/added "2.16"
   ::sm/params schema:update-task}
  [cfg {:keys [::rpc/profile-id id title] :as params}]
  (check-tasks-enabled)
  (when (and (some? title) (str/blank? title))
    (ex/raise :type :validation
              :code :invalid-task-title
              :hint "task title cannot be blank"))
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg (:file-id task) profile-id
     (fn [{:keys [::db/conn] :as cfg}]
       (let [task      (get-task conn id {::sql/for-update true})
             team-id   (perms/get-team-id-for-file conn (:file-id task))
             reassign? (and (contains? params :assignee-id)
                            (not= (:assignee-id params) (:assignee-id task)))
             rename?   (and (some? title) (not= (str/trim title) (:title task)))
             changes   (cond-> {:updated-at (ct/now)}
                         rename?   (assoc :title (str/trim title))
                         reassign? (assoc :assignee-id (:assignee-id params)))
             task      (merge task changes)]
         (when reassign?
           (check-assignee conn team-id (:assignee-id params)))
         (db/update! conn :file-task changes {:id id} {::db/return-keys false})
         (when rename?
           (insert-event conn id profile-id "renamed" {:title (:title task)}))
         (when reassign?
           (insert-event conn id profile-id "assigned" {:assignee-id (:assignee-id task)})
           (send-task-assigned-email cfg team-id profile-id task))
         task)))))

;; --- COMMAND: set-task-status

(def ^:private schema:set-task-status
  [:map {:title "set-task-status" :closed true}
   [:id ::sm/uuid]
   [:status (into [:enum] statuses)]])

(sv/defmethod ::set-task-status
  "Move a task to another state (see `transitions`). Anybody who can read
  the file may do it; the event records who did."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:set-task-status}
  [cfg {:keys [::rpc/profile-id id status]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg (:file-id task) profile-id
     (fn [{:keys [::db/conn]}]
       (let [task (get-task conn id {::sql/for-update true})
             from (:status task)
             ts   (ct/now)]
         (when-not (contains? (get transitions from) status)
           (ex/raise :type :validation
                     :code :invalid-task-transition
                     :hint "the task cannot move to that state from the one it is in"
                     :from from
                     :to status))
         (db/update! conn :file-task
                     {:status status
                      :status-changed-at ts
                      :status-changed-by profile-id
                      :updated-at ts}
                     {:id id}
                     {::db/return-keys false})
         (insert-event conn id profile-id "status" {:from from :to status})
         (assoc task
                :status status
                :status-changed-at ts
                :status-changed-by profile-id))))))

;; --- COMMAND: relink-task

(def ^:private schema:relink-task
  [:map {:title "relink-task" :closed true}
   [:id ::sm/uuid]
   [:page-id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:shape-id ::sm/uuid]
   ;; the name the layer has now, kept in the event
   [:shape-name [:string {:max 250}]]])

(sv/defmethod ::relink-task
  "Point a task at another layer. A task whose layer was deleted stops
  being lost."
  {::doc/added "2.16"
   ::sm/params schema:relink-task}
  [cfg {:keys [::rpc/profile-id id page-id frame-id shape-id shape-name]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg (:file-id task) profile-id
     (fn [{:keys [::db/conn]}]
       (let [changes {:page-id page-id
                      :frame-id frame-id
                      :shape-id shape-id
                      :lost-shape-name nil
                      :updated-at (ct/now)}]
         (db/update! conn :file-task changes {:id id} {::db/return-keys false})
         (insert-event conn id profile-id "relinked" {:shape-id shape-id :shape-name shape-name})
         (merge (get-task conn id) changes))))))

;; --- COMMAND: mark-tasks-layer-deleted

(def ^:private schema:deleted-layer
  [:map {:title "DeletedTaskLayer"}
   [:shape-id ::sm/uuid]
   [:shape-name [:string {:max 250}]]
   ;; the board the task moves to, and its name for the event
   [:frame-id ::sm/uuid]
   [:frame-name {:optional true} [:maybe [:string {:max 250}]]]
   ;; the layer as it was (the shape and its children), what "restore
   ;; layer" puts back
   [:objects {:optional true} [:vector {:max 500} :map]]])

(def ^:private schema:mark-tasks-layer-deleted
  [:map {:title "mark-tasks-layer-deleted" :closed true}
   [:file-id ::sm/uuid]
   [:layers [:vector {:min 1 :max 200} schema:deleted-layer]]])

(def ^:private sql:get-tasks-of-layers
  "SELECT * FROM file_task
    WHERE file_id = ?
      AND shape_id = ANY(?)
      AND lost_shape_name IS NULL
      AND status NOT IN ('verified', 'wont-do')
      AND deleted_at IS NULL
      FOR UPDATE")

(sv/defmethod ::mark-tasks-layer-deleted
  "Tell the server that layers with open tasks were deleted: each task
  moves to the board that held its layer and keeps the name of the lost
  layer until it is linked to another one. The event of each task keeps
  the deleted layer, so it can be restored later. Returns the tasks it
  moved."
  {::doc/added "2.16"
   ::sm/params schema:mark-tasks-layer-deleted}
  [cfg {:keys [::rpc/profile-id file-id layers]}]
  (check-tasks-enabled)
  (files/check-read-permissions! cfg profile-id file-id)
  (run-and-notify
   cfg file-id profile-id
   (fn [{:keys [::db/conn]}]
     (let [by-shape (d/index-by :shape-id layers)
           ids      (db/create-array conn "uuid" (vec (keys by-shape)))
           tasks    (into [] (map decode-row) (db/exec! conn [sql:get-tasks-of-layers file-id ids]))
           ts       (ct/now)]
       (into []
             (map (fn [{:keys [id shape-id] :as task}]
                    (let [{:keys [shape-name frame-id frame-name objects]} (get by-shape shape-id)
                          changes {:lost-shape-name shape-name
                                   :frame-id frame-id
                                   :updated-at ts}]
                      (db/update! conn :file-task changes {:id id} {::db/return-keys false})
                      (insert-event conn id profile-id "layer-deleted"
                                    {:shape-id shape-id
                                     :shape-name shape-name
                                     :frame-id frame-id
                                     :frame-name frame-name
                                     :objects objects})
                      (merge task changes))))
             tasks)))))

;; --- COMMAND: mark-task-layer-restored

(def ^:private schema:mark-task-layer-restored
  [:map {:title "mark-task-layer-restored" :closed true}
   [:id ::sm/uuid]
   [:frame-id ::sm/uuid]])

(sv/defmethod ::mark-task-layer-restored
  "The deleted layer of a task is back (undone, or restored from the
  task): the task points at it again."
  {::doc/added "2.16"
   ::sm/params schema:mark-task-layer-restored}
  [cfg {:keys [::rpc/profile-id id frame-id]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg (:file-id task) profile-id
     (fn [{:keys [::db/conn]}]
       (let [changes {:lost-shape-name nil
                      :frame-id frame-id
                      :updated-at (ct/now)}]
         (db/update! conn :file-task changes {:id id} {::db/return-keys false})
         (insert-event conn id profile-id "layer-restored"
                       {:shape-id (:shape-id task)
                        :shape-name (:lost-shape-name task)})
         (merge task changes))))))

;; --- COMMAND: delete-task

(def ^:private schema:delete-task
  [:map {:title "delete-task" :closed true}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-task
  "Delete a task. Its thread stays: the message the task lived in goes
  on as a plain comment."
  {::doc/added "2.16"
   ::sm/params schema:delete-task}
  [cfg {:keys [::rpc/profile-id id]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg (:file-id task) profile-id
     (fn [{:keys [::db/conn]}]
       (db/update! conn :file-task
                   {:deleted-at (ct/now) :updated-at (ct/now)}
                   {:id id}
                   {::db/return-keys false})
       nil))))

;; --- Review counts

(def ^:private sql:get-review-task-counts
  "SELECT pull_request_id, status, count(*) AS total
     FROM file_task
    WHERE pull_request_id = ANY(?)
      AND deleted_at IS NULL
    GROUP BY pull_request_id, status")

(def ^:private sql:get-review-comment-counts
  "SELECT pull_request_id, is_resolved, count(*) AS total
     FROM comment_thread
    WHERE pull_request_id = ANY(?)
    GROUP BY pull_request_id, is_resolved")

(defn get-review-counts
  "{pull-request-id {:tasks {status count} :comments-open n
  :comments-resolved n}} for the given design reviews."
  [conn pull-request-ids]
  (if (empty? pull-request-ids)
    {}
    (let [ids      (db/create-array conn "uuid" (vec pull-request-ids))
          empty    {:tasks {} :comments-open 0 :comments-resolved 0}
          by-tasks (reduce (fn [acc {:keys [pull-request-id status total]}]
                             (assoc-in acc [pull-request-id :tasks status] total))
                           {}
                           (db/exec! conn [sql:get-review-task-counts ids]))]
      (reduce (fn [acc {:keys [pull-request-id is-resolved total]}]
                (update acc pull-request-id
                        (fn [counts]
                          (assoc (merge empty counts)
                                 (if is-resolved :comments-resolved :comments-open)
                                 total))))
              (update-vals by-tasks #(merge empty %))
              (db/exec! conn [sql:get-review-comment-counts ids])))))

;; --- Merge: move the tasks of a branch into main

(def ^:private sql:get-branch-tasks
  "SELECT * FROM file_task
    WHERE file_id = ?
      AND deleted_at IS NULL
    ORDER BY created_at ASC
      FOR UPDATE")

(def ^:private sql:lock-thread-seqn
  "SELECT comment_thread_seqn FROM file WHERE id = ? FOR UPDATE")

(defn- move-threads
  "Move the comment threads `thread-ids` of the branch into main. The
  number of a thread is unique in its file, so each one takes the next
  number of main."
  [conn main-id thread-ids]
  (when (seq thread-ids)
    (let [seqn (-> (db/exec-one! conn [sql:lock-thread-seqn main-id])
                   (:comment-thread-seqn 0))]
      (doseq [[index thread-id] (d/enumerate thread-ids)]
        (db/update! conn :comment-thread
                    {:file-id main-id
                     :seqn (+ seqn index 1)}
                    {:id thread-id}
                    {::db/return-keys false}))
      (db/update! conn :file
                  {:comment-thread-seqn (+ seqn (count thread-ids))}
                  {:id main-id}
                  {::db/return-keys false}))))

(defn- send-tasks-moved-emails
  [{:keys [::db/conn] :as cfg} {:keys [team-id main-id actor-id branch-name file-name]} tasks]
  (let [users (->> (teams/get-users+props conn team-id)
                   (map profile/decode-row)
                   (d/index-by :id))
        actor (get-in users [actor-id :fullname] "")]
    (doseq [[assignee-id tasks] (group-by :assignee-id tasks)]
      (when-let [{:keys [fullname email]} (and (some? assignee-id)
                                               (not= assignee-id actor-id)
                                               (get users assignee-id))]
        (eml/send-template
         cfg
         {::eml/reuse-conn true
          ::eml/template "tasks-moved"
          :public-uri (cf/get :public-uri)
          :to email
          :name fullname
          :source-user actor
          :branch-name branch-name
          :file-name file-name
          :task-titles (str/join "\n" (map #(str "- " (:title %)) tasks))
          :file-url (str/join [(cf/get :public-uri)
                               "/#/workspace?"
                               (uri/map->query-string {:file-id main-id
                                                       :team-id team-id})])})))))

(defn move-branch-tasks
  "Move every task of the branch file into main, inside the merge
  transaction: the open ones keep going on main, the closed ones stay as
  the history of the branch. Each task keeps the names it shows (the
  review title is refreshed from its pull request, which goes away with
  the branch), records the merge and gets a `moved-to-main` event. The
  threads of the tasks move with them. When `notify-assignees` is set,
  the assignees of the open tasks are told by email. Returns the moved
  tasks."
  [{:keys [::db/conn] :as cfg}
   {:keys [branch-file-id main-id merge-id branch-name profile-id notify-assignees]}]
  (let [tasks (db/exec! conn [sql:get-branch-tasks branch-file-id])]
    (when (seq tasks)
      (let [reviews (->> (keep :pull-request-id tasks)
                         (distinct)
                         (map #(db/get* conn :file-pull-request {:id %}))
                         (remove nil?)
                         (d/index-by :id))
            ts      (ct/now)]
        (move-threads conn main-id (into [] (comp (keep :thread-id) (distinct)) tasks))
        (doseq [{:keys [id pull-request-id review-title] :as task} tasks]
          (db/update! conn :file-task
                      {:file-id main-id
                       :merge-id merge-id
                       :branch-name branch-name
                       :review-title (or (get-in reviews [pull-request-id :title]) review-title)
                       :updated-at ts}
                      {:id id}
                      {::db/return-keys false})
          (insert-event conn (:id task) profile-id "moved-to-main"
                        {:merge-id merge-id :branch-name branch-name}))
        (when notify-assignees
          (let [main (db/get conn :file {:id main-id} {::sql/columns [:name]})]
            (send-tasks-moved-emails cfg
                                     {:team-id (perms/get-team-id-for-file conn main-id)
                                      :main-id main-id
                                      :actor-id profile-id
                                      :branch-name branch-name
                                      :file-name (:name main)}
                                     (remove #(contains? closed-statuses (:status %)) tasks))))
        tasks))))
