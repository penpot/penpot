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
  created on the canvas gets a thread whose first message is its title
  (`created-thread` records which). A task may also belong to a design
  review. Everybody who can read the file may create a task and move it
  between states; every move is recorded as an event of the task with
  who made it, which is what the thread of the task shows between its
  comments.

  The merge of a branch moves its tasks into main (`move-branch-tasks`,
  called from `app.rpc.commands.files-branch`): the open ones keep going
  on main and the closed ones stay as the history of the branch."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
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

(def ^:private sql:select-tasks
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
          t.created_thread,
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
     LEFT JOIN comment_thread AS ct ON (ct.id = t.thread_id)")

(def ^:private sql:get-file-tasks
  (str sql:select-tasks
       " WHERE t.file_id = ?
           AND t.deleted_at IS NULL
         ORDER BY t.created_at ASC"))

(def ^:private sql:get-file-tasks-by-id
  (str sql:select-tasks
       " WHERE t.file_id = ?
           AND t.id = ANY(?)
           AND t.deleted_at IS NULL
         ORDER BY t.created_at ASC"))

(defn tasks-change-message
  "The message that tells the open clients of `file-id` that its tasks
  `task-ids` changed. It carries their rows as `get-file-tasks` lists
  them (a deleted task has none) and the session that made the change:
  that session skips it, the others patch their list with it. Read it
  inside the transaction of the change."
  [conn {:keys [file-id profile-id session-id task-ids]}]
  (let [task-ids (vec task-ids)]
    {:type :file-tasks-change
     :file-id file-id
     :profile-id profile-id
     :session-id session-id
     :task-ids task-ids
     :tasks (if (seq task-ids)
              (into []
                    (map decode-row)
                    (db/exec! conn [sql:get-file-tasks-by-id file-id
                                    (db/create-array conn "uuid" task-ids)]))
              [])}))

(defn- run-and-notify
  "Run `f` in a transaction, then tell the open clients of `file-id` that
  the tasks `get-ids` names in the result of `f` changed
  (`tasks-change-message`). The message is read in the transaction and
  leaves once it committed, so it carries the committed state."
  [{:keys [::mbus/msgbus] :as cfg} {:keys [file-id] :as context} get-ids f]
  (let [[result message]
        (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                          (let [result (f cfg)]
                            [result (tasks-change-message conn (assoc context :task-ids (get-ids result)))])))]
    (mbus/pub! msgbus :topic file-id :message message)
    result))

(def ^:private sql:lock-file
  "SELECT id, deleted_at FROM file WHERE id = ? FOR UPDATE")

(defn- lock-file
  "Lock the row of `file-id` and refuse a deleted file. Task creation and
  the move of the tasks of a merged branch (`move-branch-tasks`) take
  this lock: a task created during a merge either lands before the merge
  reads the tasks of the branch, or waits and finds the branch file
  deleted."
  [conn file-id]
  (let [file (db/exec-one! conn [sql:lock-file file-id])]
    (when (or (nil? file) (some? (:deleted-at file)))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "the file of the task does not exist or was deleted"
                :file-id file-id))))

;; --- Helpers: emails

(defn- format-task-url
  [{:keys [id file-id page-id]} team-id]
  (str/join [(cf/get :public-uri)
             "/#/workspace?"
             (uri/map->query-string {:file-id file-id
                                     :page-id page-id
                                     :team-id team-id
                                     :task-id id})]))

(defn- team-users
  "The users of `team-id` by id, with their props: the task emails read
  the notification setting of each one."
  [conn team-id]
  (->> (teams/get-users+props conn team-id)
       (map profile/decode-row)
       (d/index-by :id)))

(defn- task-emails?
  "Whether the task emails of a file reach the profile with `props`:
  `:none` refuses them all, and the rest (an absent setting counts as
  `:partial`) admit them."
  [props]
  (not= :none (-> props :notifications :email-tasks)))

(defn- send-task-assigned-email
  "Tell `assignee-id` that `actor-id` assigned the task to them. Nobody is
  told about the tasks they assign to themselves, and a profile that
  refuses the task emails is told nothing."
  [{:keys [::db/conn] :as cfg} team-id actor-id {:keys [assignee-id title] :as task}]
  (when (and (some? assignee-id) (not= assignee-id actor-id))
    (let [users (team-users conn team-id)]
      (when-let [{:keys [fullname email props]} (get users assignee-id)]
        (when (task-emails? props)
          (eml/send-template
           cfg
           {::eml/reuse-conn true
            ::eml/template "task-assigned"
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (get-in users [actor-id :fullname] "")
            :task-title title
            :task-url (format-task-url task team-id)}))))))

(def ^:private status-email
  "The mail a move of a task sends, and the person it tells: the creator
  when the task is done, the assignee when it is reopened. The other
  moves (verified, won't do) tell nobody."
  {"done"     {:recipient :created-by  :template "task-done"}
   "reopened" {:recipient :assignee-id :template "task-reopened"}})

(defn- send-task-status-email
  "Tell the person a move of `task` concerns about it: its creator when
  it is done, its assignee when it is reopened. Nobody is told about
  their own move, and a profile that refuses the task emails is told
  nothing."
  [{:keys [::db/conn] :as cfg} actor-id {:keys [status file-id] :as task}]
  (when-let [{:keys [recipient template]} (get status-email status)]
    (when-let [recipient-id (get task recipient)]
      (when (not= recipient-id actor-id)
        (let [team-id (perms/get-team-id-for-file conn file-id)
              users   (team-users conn team-id)]
          (when-let [{:keys [fullname email props]} (get users recipient-id)]
            (when (task-emails? props)
              (eml/send-template
               cfg
               {::eml/reuse-conn true
                ::eml/template template
                :public-uri (cf/get :public-uri)
                :to email
                :name fullname
                :source-user (get-in users [actor-id :fullname] "")
                :task-title (:title task)
                :task-url (format-task-url task team-id)}))))))))

;; --- COMMAND QUERY: get-file-tasks

(def ^:private schema:get-file-tasks
  [:map {:title "get-file-tasks"}
   [:file-id ::sm/uuid]])

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
  when there is none) and belongs to the design review of the task. The
  task tells its assignee by email, so the thread tells nobody that a
  comment was added."
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
            ::comments/skip-subscriber-emails true
            :anchor (if (some? shape-id)
                      {:type :layer :shape-id shape-id}
                      {:type :canvas})}
     (some? pull-request-id) (assoc :pull-request-id pull-request-id))))

(sv/defmethod ::create-task
  "Create an open task on a file, in a message of a comment thread: the
  message it was made from (`thread-id` and `comment-id`), or the first
  message of a new thread that carries its title. A task created in a
  design review belongs to it. The assignee, when there is one, must be
  a member of the team and is told by email. The thread created with the
  task sends no comment email of its own. A deleted file takes no task:
  a merge deletes its branch file (see `lock-file`)."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:create-task}
  [cfg {:keys [::rpc/profile-id ::rpc/session-id file-id title page-id frame-id shape-id position
               assignee-id pull-request-id thread-id comment-id] :as params}]
  (check-tasks-enabled)
  (when (str/blank? title)
    (ex/raise :type :validation
              :code :invalid-task-title
              :hint "task title cannot be blank"))
  (files/check-read-permissions! cfg profile-id file-id)
  (run-and-notify
   cfg {:file-id file-id :profile-id profile-id :session-id session-id}
   (comp vector :id)
   (fn [{:keys [::db/conn] :as cfg}]
     (lock-file conn file-id)
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
                    :created-thread (some? thread)
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

;; --- Helpers: the message of a task

(defn- rename-task-message
  "Rewrite the message of `task`, the first message of the thread the task
  created, so the thread reads with the new `title`. Only the profile that
  wrote the message renames it: anybody who can read the file may rename
  a task, and that must not let them rewrite somebody else's words. A
  task made from a message of an existing thread keeps that message as it
  is. The message and its thread take the new modified time, as
  `update-comment` makes them. The mentions stay: the message of a task
  created on the canvas carries none, and a rename adds none."
  [conn profile-id {:keys [comment-id thread-id created-thread title]} ts]
  (when (and created-thread (some? comment-id))
    (let [comment (db/get* conn :comment {:id comment-id} {::sql/for-update true})]
      (when (and (some? comment) (= profile-id (:owner-id comment)))
        (db/update! conn :comment
                    {:content title :modified-at ts}
                    {:id comment-id}
                    {::db/return-keys false})
        (when (some? thread-id)
          (db/update! conn :comment-thread
                      {:modified-at ts}
                      {:id thread-id}
                      {::db/return-keys false}))))))

;; --- COMMAND: update-task

(def ^:private schema:update-task
  [:map {:title "update-task" :closed true}
   [:id ::sm/uuid]
   [:title {:optional true} [:string {:max title-max-length}]]
   ;; nil leaves the task without an assignee
   [:assignee-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-task
  "Rename a task or change its assignee. Each change is an event of the
  task, and a new assignee is told by email. A rename also rewrites the
  first message of the thread the task created when the renamer wrote
  it, so the thread reads with the new title."
  {::doc/added "2.16"
   ::sm/params schema:update-task}
  [cfg {:keys [::rpc/profile-id ::rpc/session-id id title] :as params}]
  (check-tasks-enabled)
  (when (and (some? title) (str/blank? title))
    (ex/raise :type :validation
              :code :invalid-task-title
              :hint "task title cannot be blank"))
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg {:file-id (:file-id task) :profile-id profile-id :session-id session-id}
     (comp vector :id)
     (fn [{:keys [::db/conn] :as cfg}]
       (let [task      (get-task conn id {::sql/for-update true})
             team-id   (perms/get-team-id-for-file conn (:file-id task))
             reassign? (and (contains? params :assignee-id)
                            (not= (:assignee-id params) (:assignee-id task)))
             rename?   (and (some? title) (not= (str/trim title) (:title task)))
             ts        (ct/now)
             changes   (cond-> {:updated-at ts}
                         rename?   (assoc :title (str/trim title))
                         reassign? (assoc :assignee-id (:assignee-id params)))
             task      (merge task changes)]
         (when reassign?
           (check-assignee conn team-id (:assignee-id params)))
         (db/update! conn :file-task changes {:id id} {::db/return-keys false})
         (when rename?
           (insert-event conn id profile-id "renamed" {:title (:title task)})
           (rename-task-message conn profile-id task ts))
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
  the file may do it, and the event records who did. The creator of the
  task is told by email when it is done and its assignee when it is
  reopened."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:set-task-status}
  [cfg {:keys [::rpc/profile-id ::rpc/session-id id status]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg {:file-id (:file-id task) :profile-id profile-id :session-id session-id}
     (comp vector :id)
     (fn [{:keys [::db/conn] :as cfg}]
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
         (let [task (assoc task
                           :status status
                           :status-changed-at ts
                           :status-changed-by profile-id)]
           (send-task-status-email cfg profile-id task)
           task))))))

;; --- Helpers: the thread created with a task

(defn- move-task-thread
  "Point the thread created with `task` where the task points now: at the
  layer `shape-id`; with no layer, at the rectangle `area` of the canvas,
  or at the spot `position` when there is no area either. A thread the
  task was made from keeps its own place."
  [conn task {:keys [frame-id shape-id area position]}]
  (when (:created-thread task)
    (db/update! conn :comment-thread
                (cond
                  (some? shape-id)
                  {:frame-id frame-id
                   :shape-id shape-id
                   :anchor-type "layer"
                   :area nil}

                  (some? area)
                  {:frame-id frame-id
                   :shape-id nil
                   :anchor-type "area"
                   :area (db/tjson area)
                   :position (db/pgpoint position)}

                  :else
                  {:frame-id frame-id
                   :shape-id nil
                   :anchor-type "canvas"
                   :area nil
                   :position (db/pgpoint position)})
                {:id (:thread-id task)}
                {::db/return-keys false})))

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
  being lost. The thread created with the task follows it; a thread the
  task was made from keeps its place."
  {::doc/added "2.16"
   ::sm/params schema:relink-task}
  [cfg {:keys [::rpc/profile-id ::rpc/session-id id page-id frame-id shape-id shape-name]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg {:file-id (:file-id task) :profile-id profile-id :session-id session-id}
     (comp vector :id)
     (fn [{:keys [::db/conn]}]
       (let [task    (get-task conn id {::sql/for-update true})
             changes {:page-id page-id
                      :frame-id frame-id
                      :shape-id shape-id
                      :lost-shape-name nil
                      :updated-at (ct/now)}]
         (db/update! conn :file-task changes {:id id} {::db/return-keys false})
         (move-task-thread conn task changes)
         (insert-event conn id profile-id "relinked" {:shape-id shape-id :shape-name shape-name})
         (merge task changes))))))

;; --- COMMAND: mark-tasks-layer-deleted

(def ^:private schema:deleted-layer
  [:map {:title "DeletedTaskLayer"}
   [:shape-id ::sm/uuid]
   [:shape-name [:string {:max 250}]]
   ;; the board the task moves to, and its name for the event
   [:frame-id ::sm/uuid]
   [:frame-name {:optional true} [:maybe [:string {:max 250}]]]
   ;; the layer as it was (the shape and its children), what "restore
   ;; layer" puts back; kept only up to `max-restore-objects`
   [:objects {:optional true} [:vector :map]]])

(def ^:private schema:mark-tasks-layer-deleted
  [:map {:title "mark-tasks-layer-deleted" :closed true}
   [:file-id ::sm/uuid]
   [:layers [:vector {:min 1} schema:deleted-layer]]])

(def ^:private max-restore-objects
  "The most objects of a deleted layer its event keeps for a restore."
  500)

(def ^:private valid-restore-object?
  (sm/lazy-validator cts/schema:shape-attrs))

(defn- restore-objects
  "The objects of a deleted layer its event keeps, what a restore puts
  back as real layers: none above `max-restore-objects`, and none when
  one of them is not a valid shape. The client sends them without their
  children ids (`:shapes`), which a restore rebuilds. Without them the
  task is lost all the same and no restore is offered."
  [objects]
  (when (and (seq objects)
             (<= (count objects) max-restore-objects)
             (every? #(valid-restore-object? (update % :shapes (fnil identity []))) objects))
    objects))

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
  the deleted layer, so it can be restored later, when the layer is small
  and valid enough (`restore-objects`). Returns the tasks it moved."
  {::doc/added "2.16"
   ::sm/params schema:mark-tasks-layer-deleted}
  [cfg {:keys [::rpc/profile-id ::rpc/session-id file-id layers]}]
  (check-tasks-enabled)
  (files/check-read-permissions! cfg profile-id file-id)
  (run-and-notify
   cfg {:file-id file-id :profile-id profile-id :session-id session-id}
   #(map :id %)
   (fn [{:keys [::db/conn]}]
     (let [by-shape (d/index-by :shape-id layers)
           ids      (db/create-array conn "uuid" (vec (keys by-shape)))
           tasks    (into [] (map decode-row) (db/exec! conn [sql:get-tasks-of-layers file-id ids]))
           kept     (update-vals by-shape (comp restore-objects :objects))
           ts       (ct/now)]
       (into []
             (map (fn [{:keys [id shape-id] :as task}]
                    (let [{:keys [shape-name frame-id frame-name]} (get by-shape shape-id)
                          objects (get kept shape-id)
                          changes {:lost-shape-name shape-name
                                   :frame-id frame-id
                                   :updated-at ts}]
                      (db/update! conn :file-task changes {:id id} {::db/return-keys false})
                      (insert-event conn id profile-id "layer-deleted"
                                    (cond-> {:shape-id shape-id
                                             :shape-name shape-name
                                             :frame-id frame-id
                                             :frame-name frame-name}
                                      (some? objects) (assoc :objects objects)))
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
  [cfg {:keys [::rpc/profile-id ::rpc/session-id id frame-id]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (files/check-read-permissions! cfg profile-id (:file-id task))
    (run-and-notify
     cfg {:file-id (:file-id task) :profile-id profile-id :session-id session-id}
     (comp vector :id)
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

(defn- check-delete-permissions
  "Deleting a task is allowed to its author and to the team admins and
  owners of the file, which may clean up after someone who left the team.
  Everybody else gets the same refusal, and a profile that cannot read
  the file gets the usual not-found."
  [cfg profile-id {:keys [id created-by file-id]}]
  (let [perms (perms/get-file-read-permissions cfg profile-id file-id)]
    (files/check-read-permissions! perms)
    (when-not (or (= created-by profile-id) (:is-admin perms))
      (ex/raise :type :validation
                :code :not-allowed
                :hint "only the author of a task or a team admin may delete it"
                :task-id id))))

(sv/defmethod ::delete-task
  "Delete a task. Its author and the team admins and owners of the file
  may. Anybody who can read the file may still move it to won't do. A
  `deleted` event, written before the task goes, records who deleted it.
  Its thread stays: the message the task lived in goes on as a plain
  comment."
  {::doc/added "2.16"
   ::sm/params schema:delete-task}
  [cfg {:keys [::rpc/profile-id ::rpc/session-id id]}]
  (check-tasks-enabled)
  (let [task (db/run! cfg (fn [{:keys [::db/conn]}] (get-task conn id)))]
    (check-delete-permissions cfg profile-id task)
    (run-and-notify
     cfg {:file-id (:file-id task) :profile-id profile-id :session-id session-id}
     (constantly [id])
     (fn [{:keys [::db/conn]}]
       (let [task (get-task conn id {::sql/for-update true})
             ts   (ct/now)]
         ;; the event first: a deleted task is not found any more
         (insert-event conn id profile-id "deleted" {:title (:title task)})
         (db/update! conn :file-task
                     {:deleted-at ts :updated-at ts}
                     {:id id}
                     {::db/return-keys false})
         nil)))))

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
  (let [users (team-users conn team-id)
        actor (get-in users [actor-id :fullname] "")]
    (doseq [[assignee-id tasks] (group-by :assignee-id tasks)]
      (when-let [{:keys [fullname email props]} (and (some? assignee-id)
                                                     (not= assignee-id actor-id)
                                                     (get users assignee-id))]
        (when (task-emails? props)
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
                                                         :team-id team-id})])}))))))

(defn- find-shape
  [data page-id shape-id]
  (get-in data [:pages-index page-id :objects shape-id]))

(defn- task-landing
  "Where an open task of a merged branch lands on main when the merge did
  not carry its layer: on the immediate parent of the layer when main has
  it, otherwise on the area of the canvas the layer covered. The layer is
  read from the branch document, or from the merge base when the branch
  deleted it; when neither has it, the task keeps its spot of the canvas.
  Nil when the task stays as it is: it is closed, points at the canvas,
  its layer reached main, or its page did not."
  [{:keys [merged-data branch-data base-data]} {:keys [page-id shape-id status position]}]
  (when (and (some? shape-id)
             (not (contains? closed-statuses status))
             (contains? (:pages-index merged-data) page-id)
             (nil? (find-shape merged-data page-id shape-id)))
    (let [layer  (or (find-shape branch-data page-id shape-id)
                     (find-shape base-data page-id shape-id))
          parent (some->> (:parent-id layer) (find-shape merged-data page-id))]
      (cond
        (and (some? parent) (not (cfh/root? parent)))
        {:shape-id (:id parent)
         :shape-name (:name parent)
         :frame-id (if (cfh/frame-shape? parent) (:id parent) (:frame-id parent))}

        (some? layer)
        (let [{:keys [x y width height]} (:selrect layer)]
          {:frame-id uuid/zero
           :area {:x x :y y :width width :height height}
           ;; the top right corner, where the mark of a task goes
           :position (gpt/point (+ x width) y)})

        :else
        {:frame-id uuid/zero
         :position position}))))

(defn- land-task
  "Point `task` where `task-landing` puts it, its thread created with it
  too, and record it as a `relinked` event."
  [conn profile-id task {:keys [shape-id shape-name frame-id area position] :as landing}]
  (db/update! conn :file-task
              (cond-> {:shape-id shape-id
                       :frame-id frame-id
                       :lost-shape-name nil}
                (some? position) (assoc :position (db/pgpoint position)))
              {:id (:id task)}
              {::db/return-keys false})
  (move-task-thread conn task landing)
  (insert-event conn (:id task) profile-id "relinked"
                (if (some? shape-id)
                  {:shape-id shape-id :shape-name shape-name}
                  {:shape-id nil :area area})))

(defn move-branch-tasks
  "Move every task of the branch file into main, inside the merge
  transaction: the open ones keep going on main, the closed ones stay as
  the history of the branch. Each task keeps the names it shows (the
  review title is refreshed from its pull request, which goes away with
  the branch), records the merge and gets a `moved-to-main` event. The
  threads of the tasks move with them. An open task whose layer the merge
  did not carry lands where `task-landing` says, with a `relinked` event,
  reading the layer in `merged-data`, `branch-data` and `base-data`. When
  `notify-assignees` is set, the assignees of the open tasks are told by
  email. Returns the moved tasks.

  The branch file row is locked before the tasks are read, as task
  creation locks it (`lock-file`): a task created meanwhile is either
  read here or refused once the merge deleted the branch file."
  [{:keys [::db/conn] :as cfg}
   {:keys [branch-file-id main-id merge-id branch-name profile-id notify-assignees] :as params}]
  (db/exec-one! conn [sql:lock-file branch-file-id])
  (let [tasks (into [] (map decode-row) (db/exec! conn [sql:get-branch-tasks branch-file-id]))]
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
                        {:merge-id merge-id :branch-name branch-name})
          (when-let [landing (task-landing params task)]
            (land-task conn profile-id task landing)))
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
