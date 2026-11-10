;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.tasks
  "Data layer for tasks: a piece of work on a file, assigned to someone
  and tied to a layer. Every task lives in a message of a comment thread,
  so its thread can go on (a thread may hold several tasks): a task made
  from a message keeps that thread, and a task created on the canvas gets
  a thread whose first message is its title. A task may also belong to a
  design review.

  The tasks of the open file live in `:workspace-tasks`, the events of a
  task (what the thread of the task shows between its comments) in
  `:workspace-task-events`, and what the user is doing with them (the
  task open on the canvas, the new task being written, the task being
  linked to another layer) in `:workspace-tasks-local`."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dcmt]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.viewport-wasm :as dwvw]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn enabled?
  "Tasks ride on the branching feature, like design reviews."
  []
  (contains? cf/flags :branching))

;; --- Task predicates

(def closed-statuses
  "A closed task is finished: verified, or dropped as won't do."
  #{"verified" "wont-do"})

(defn closed?
  [task]
  (contains? closed-statuses (:status task)))

(defn to-do?
  "The task needs work: open or reopened."
  [task]
  (contains? #{"open" "reopened"} (:status task)))

(defn to-verify?
  "The task is done and waits for someone to verify it."
  [task]
  (= "done" (:status task)))

(defn lost?
  "The layer of the task was deleted."
  [task]
  (some? (:lost-shape-name task)))

(defn history?
  "The task came with a merged branch and is already closed: it is part
  of the history of that branch, not of the work on this file."
  [task]
  (and (some? (:merge-id task)) (closed? task)))

(defn get-tasks-of-thread
  "The tasks a comment thread is attached to, oldest first."
  [tasks thread-id]
  (->> (vals tasks)
       (filter #(= thread-id (:thread-id %)))
       (sort-by :created-at)))

(defn get-task-of-comment
  "The task a message of a comment thread became, if any."
  [tasks comment-id]
  (d/seek #(= comment-id (:comment-id %)) (vals tasks)))

;; --- Locating a task on the canvas

(defn shape-at-position
  "The topmost visible layer under `position` that is not a board, or
  the innermost board when no other layer is there; nil on the empty
  canvas."
  [objects position]
  (let [hits (->> (vals objects)
                  (remove cfh/root?)
                  (remove :hidden)
                  (filter #(gsh/has-point? % position)))]
    (when (seq hits)
      (let [sorted (ctst/sort-z-index-objects objects hits {:bottom-frames? true})
            shapes (remove cfh/frame-shape? sorted)]
        (or (last shapes) (last sorted))))))

(defn anchor-shape
  "The layer a task points at, or its board when the layer is gone. None
  when it points at the canvas itself."
  [objects {:keys [shape-id frame-id]}]
  (->> [(get objects shape-id) (get objects frame-id)]
       (remove nil?)
       (remove cfh/root?)
       (first)))

(defn anchor-position
  "Where the mark of a task goes on the canvas, as a point: the top right
  corner of its layer, or of its board when the layer is gone, or the
  spot it was made on when it points at the canvas itself (`position`).
  The thread of a layer opens there too, and a thread keeps its position
  as a point."
  [objects {:keys [position] :as task}]
  (let [shape (anchor-shape objects task)]
    (if (some? shape)
      (let [{:keys [x y width]} (:selrect shape)]
        (gpt/point (+ x width) y))
      (some-> position (gpt/point)))))

;; --- Fetching

(defn- tasks-fetched
  [tasks]
  (ptk/reify ::tasks-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-tasks {:status :loaded
                                     :data (d/index-by :id tasks)}))))

(defn fetch-tasks
  "Load the tasks of the open file."
  []
  (ptk/reify ::fetch-tasks
    ptk/WatchEvent
    (watch [_ state _]
      (if-let [file-id (and (enabled?) (:current-file-id state))]
        (->> (rp/cmd! :get-file-tasks {:file-id file-id})
             (rx/map tasks-fetched)
             (rx/catch (fn [_] (rx/of (tasks-fetched [])))))
        (rx/empty)))))

(defn- task-events-fetched
  [task-id events]
  (ptk/reify ::task-events-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-task-events task-id] events))))

(defn fetch-task-events
  [task-id]
  (assert (uuid? task-id) "expected a valid uuid for `task-id`")
  (ptk/reify ::fetch-task-events
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-task-events {:id task-id})
           (rx/map (partial task-events-fetched task-id))
           (rx/catch (fn [_] (rx/empty)))))))

(defn- refresh
  "Reload the tasks and the events of the task open now, after a change
  made here or by somebody else."
  []
  (ptk/reify ::refresh
    ptk/WatchEvent
    (watch [_ state _]
      (let [open-id (get-in state [:workspace-tasks-local :open])]
        (rx/concat
         (rx/of (fetch-tasks))
         (if (some? open-id)
           (rx/of (fetch-task-events open-id))
           (rx/empty)))))))

(defn handle-tasks-change
  "Another session changed the tasks of the open file."
  [{:keys [file-id]}]
  (ptk/reify ::handle-tasks-change
    ptk/WatchEvent
    (watch [_ state _]
      (if (= file-id (:current-file-id state))
        (rx/of (refresh))
        (rx/empty)))))

;; --- Opening a task

(defn open-task
  "Open the task `task-id` on the canvas, as the thread it lives in."
  [task-id]
  (ptk/reify ::open-task
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-tasks-local assoc :open task-id)
          (update :workspace-tasks-local dissoc :new)
          (update :comments-local dissoc :open :draft :expanded)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [task   (get-in state [:workspace-tasks :data task-id])
            thread (some->> (:thread-id task) (get (:comment-threads state)))]
        (rx/concat
         (rx/of (fetch-task-events task-id))
         (if (some? thread)
           (rx/of (dcmt/open-thread thread))
           (rx/empty)))))))

(defn close-task
  []
  (ptk/reify ::close-task
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tasks-local dissoc :open))))

(declare center-to-task)

(defn navigate-to-task
  "Take the viewport to the layer of `task` and open it."
  [task]
  (ptk/reify ::navigate-to-task
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:page-id task)]
        (rx/concat
         (if (not= page-id (:current-page-id state))
           (rx/of (rt/nav :workspace
                          (-> (rt/get-params state)
                              (assoc :page-id page-id))
                          {::rt/replace true}))
           (rx/empty))
         (->> (rx/of (center-to-task task)
                     (open-task (:id task)))
              (rx/observe-on :async)))))))

(defn open-task-link
  "Open the task of a link (`task-id` query param): load the tasks, take
  the viewport to it, open it and drop the param."
  [task-id]
  (ptk/reify ::open-task-link
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-file-tasks {:file-id file-id})
             (rx/mapcat
              (fn [tasks]
                (let [task (d/seek #(= task-id (:id %)) tasks)]
                  (rx/concat
                   (rx/of (tasks-fetched tasks)
                          (rt/nav :workspace (dissoc (rt/get-params state) :task-id)
                                  {::rt/replace true}))
                   (if (some? task)
                     (rx/of (navigate-to-task task))
                     (rx/empty))))))
             (rx/catch (fn [_] (rx/empty))))))))

;; --- Writing a new task

(defn start-new-task
  "Open the form of a new task linked to a layer: `params` carries the
  page, board and layer (`page-id`, `frame-id`, `shape-id`) and,
  optionally, the message of a comment thread the task comes from
  (`thread-id` and `comment-id`, with its text as the first `title`) and
  the design review it belongs to."
  [params]
  (ptk/reify ::start-new-task
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-tasks-local assoc :new params)
          (update :workspace-tasks-local dissoc :open)))))

(defn cancel-new-task
  []
  (ptk/reify ::cancel-new-task
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tasks-local dissoc :new))))

(defn- task-created
  "Keep a new task right away: opening it needs to know its thread, before
  the tasks of the file are fetched again."
  [task]
  (ptk/reify ::task-created
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-tasks :data (:id task)] task))))

(defn- fetch-task-thread
  "The thread a new task created on the canvas got: keep it before the
  task opens as that thread."
  [{:keys [file-id thread-id]}]
  (->> (rp/cmd! :get-comment-thread {:file-id file-id :id thread-id})
       (rx/map #(dcmt/created-thread-on-workspace % false))))

(defn create-task
  "Create a task from the form. A task made from a message lives in it;
  any other gets a thread of its own, whose first message is its title,
  on the spot of its mark. It opens once created."
  [{:keys [title] :as params}]
  (assert (string? title) "expected a string `title`")
  (ptk/reify ::create-task
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            objects (dsh/lookup-page-objects state (:page-id params))
            params  (-> params
                        (select-keys [:title :page-id :frame-id :shape-id :assignee-id
                                      :pull-request-id :thread-id :comment-id :position])
                        ;; the root of the page is the canvas: no layer
                        (update :shape-id #(when (not= uuid/zero %) %))
                        (update :frame-id #(or % uuid/zero))
                        (assoc :file-id file-id))
            ;; a task on a selected layer comes with no spot: its thread
            ;; sits where its mark goes
            params  (-> params
                        (assoc :position (some-> (or (:position params)
                                                     (anchor-position objects params))
                                                 (gpt/point)))
                        (d/without-nils))
            thread? (some? (:thread-id params))]
        (rx/concat
         (rx/of (ev/event {::ev/name "create-task"
                           :from-thread thread?
                           :in-review (some? (:pull-request-id params))}))
         (->> (rp/cmd! :create-task params)
              (rx/mapcat (fn [task]
                           (rx/concat
                            (if thread?
                              (rx/empty)
                              (fetch-task-thread task))
                            (rx/of (task-created task)
                                   (cancel-new-task)
                                   (ntf/success (tr "workspace.tasks.created"))
                                   (fetch-tasks)
                                   (open-task (:id task))))))
              (rx/catch (fn [cause]
                          (if (= :comment-already-has-task (:code (ex-data cause)))
                            (rx/of (ntf/warn (tr "workspace.tasks.error.comment-has-task")))
                            (rx/of (ntf/error (tr "workspace.tasks.error.generic"))))))))))))

;; --- Changing a task

(defn update-task
  "Rename a task or change its assignee (`nil` leaves it without one)."
  [task-id params]
  (assert (uuid? task-id) "expected a valid uuid for `task-id`")
  (ptk/reify ::update-task
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-tasks :data task-id]
                        merge (select-keys params [:title :assignee-id])))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-task (-> (select-keys params [:title :assignee-id])
                                     (assoc :id task-id)))
           ;; a thread may show the task without having opened it
           (rx/mapcat (fn [_] (rx/of (refresh) (fetch-task-events task-id))))
           (rx/catch (fn [_] (rx/of (ntf/error (tr "workspace.tasks.error.generic"))
                                    (refresh))))))))

(defn- status-toast
  [state {:keys [created-by]} status]
  (let [profile-id (:profile-id state)
        creator    (get-in state [:profiles created-by :fullname])]
    (case status
      "done"     (if (and (some? creator) (not= created-by profile-id))
                   (tr "workspace.tasks.toast.done-verify" creator)
                   (tr "workspace.tasks.toast.done"))
      "verified" (tr "workspace.tasks.toast.verified")
      "reopened" (tr "workspace.tasks.toast.reopened")
      "wont-do"  (tr "workspace.tasks.toast.wont-do")
      nil)))

(defn set-task-status
  "Move a task to another state; the server records who did it."
  [task status]
  (assert (uuid? (:id task)) "expected a task with a valid `:id`")
  (ptk/reify ::set-task-status
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-tasks :data (:id task)] assoc :status status))

    ptk/WatchEvent
    (watch [_ state _]
      (rx/concat
       (rx/of (ev/event {::ev/name "set-task-status" :status status}))
       (->> (rp/cmd! :set-task-status {:id (:id task) :status status})
            (rx/mapcat (fn [_]
                         (let [message (status-toast state task status)]
                           (rx/concat
                            (if message
                              (rx/of (ntf/success message))
                              (rx/empty))
                            (rx/of (refresh))))))
            (rx/catch (fn [_]
                        (rx/of (ntf/error (tr "workspace.tasks.error.generic"))
                               (refresh)))))))))

(defn delete-task
  [task-id]
  (assert (uuid? task-id) "expected a valid uuid for `task-id`")
  (ptk/reify ::delete-task
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:workspace-tasks :data] dissoc task-id)
          (update :workspace-tasks-local
                  (fn [local]
                    (cond-> local
                      (= task-id (:open local)) (dissoc :open))))))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-task {:id task-id})
           (rx/map (fn [_] (fetch-tasks)))
           (rx/catch (fn [_] (rx/of (ntf/error (tr "workspace.tasks.error.generic"))
                                    (fetch-tasks))))))))

;; --- Linking a task to another layer

(defn start-relink
  "Wait for the user to pick the layer the task points at from now on."
  [task-id]
  (ptk/reify ::start-relink
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-tasks-local assoc :relink task-id)
          (update :workspace-tasks-local dissoc :open)))))

(defn cancel-relink
  []
  (ptk/reify ::cancel-relink
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tasks-local dissoc :relink))))

(defn relink-task
  "Point the task being linked (`:relink`) at `shape`."
  [shape]
  (ptk/reify ::relink-task
    ptk/WatchEvent
    (watch [_ state _]
      (if-let [task-id (get-in state [:workspace-tasks-local :relink])]
        (let [page-id (:current-page-id state)]
          (rx/concat
           (rx/of (cancel-relink))
           (->> (rp/cmd! :relink-task {:id task-id
                                       :page-id page-id
                                       :frame-id (if (cfh/frame-shape? shape)
                                                   (:id shape)
                                                   (:frame-id shape))
                                       :shape-id (:id shape)
                                       :shape-name (:name shape)})
                (rx/mapcat (fn [_]
                             (rx/of (ntf/success (tr "workspace.tasks.toast.relinked" (:name shape)))
                                    (refresh))))
                (rx/catch (fn [_] (rx/of (ntf/error (tr "workspace.tasks.error.generic"))))))))
        (rx/empty)))))

;; --- Deleted layers

(defn- surviving-frame
  "The board that keeps a task whose layer `shape-id` is deleted with
  `deleted-ids`: the nearest board above the layer that stays."
  [objects deleted-ids shape-id]
  (let [frame (->> (cfh/get-parent-ids objects shape-id)
                   (map #(get objects %))
                   (filter cfh/frame-shape?)
                   (d/seek #(not (contains? deleted-ids (:id %)))))]
    (or frame (get objects uuid/zero))))

(defn- subtree-objects
  "The layer and its children, parents before children, as they are now:
  what restoring the layer puts back."
  [objects shape-id]
  (into []
        (comp (map #(get objects %))
              (remove nil?)
              (map #(dissoc % :shapes)))
        (cfh/get-children-ids-with-self objects shape-id)))

(declare mark-layer-restored)
(declare layer-deleted-dialog)

(defn handle-deleted-shapes
  "Some layers are about to be deleted (`ids`, with their children) from
  `objects`: move their open tasks to the board that held them, and
  offer to undo the deletion or to mark the tasks as done, in case the
  deletion was the fix."
  [objects ids]
  (ptk/reify ::handle-deleted-shapes
    ptk/WatchEvent
    (watch [_ state _]
      (let [tasks       (vals (get-in state [:workspace-tasks :data]))
            deleted-ids (into #{} (mapcat #(cfh/get-children-ids-with-self objects %)) ids)
            affected    (->> tasks
                             (remove closed?)
                             (remove lost?)
                             (filter #(contains? deleted-ids (:shape-id %))))]
        (if (or (not (enabled?)) (empty? affected))
          (rx/empty)
          (let [file-id (:current-file-id state)
                layers  (->> affected
                             (map :shape-id)
                             (distinct)
                             (mapv (fn [shape-id]
                                     (let [shape (get objects shape-id)
                                           frame (surviving-frame objects deleted-ids shape-id)]
                                       {:shape-id shape-id
                                        :shape-name (:name shape)
                                        :frame-id (:id frame)
                                        :frame-name (when-not (cfh/root? frame) (:name frame))
                                        :objects (subtree-objects objects shape-id)}))))
                first-layer (first layers)
                message     (if (= 1 (count affected))
                              (tr "workspace.tasks.layer-deleted.one"
                                  (:shape-name first-layer)
                                  (or (:frame-name first-layer) (tr "workspace.tasks.canvas")))
                              (tr "workspace.tasks.layer-deleted.many" (count affected)))]
            (->> (rp/cmd! :mark-tasks-layer-deleted {:file-id file-id :layers layers})
                 (rx/mapcat
                  (fn [moved]
                    (rx/of (refresh)
                           (layer-deleted-dialog moved message))))
                 (rx/catch (fn [_] (rx/empty))))))))))

(defn- layer-deleted-dialog
  "The dialog that follows the deletion of layers with open tasks: undo
  the deletion, or mark the tasks as done."
  [moved message]
  (ptk/reify ::layer-deleted-dialog
    ptk/WatchEvent
    (watch [_ _ _]
      (let [output-s (rx/subject)
            close    (fn [& events]
                       (rx/push! output-s (ntf/hide :tag :task-layer-deleted))
                       (run! #(rx/push! output-s %) events)
                       (rx/end! output-s))]
        (rx/merge
         output-s
         (rx/of (ntf/dialog
                 :content message
                 :controls :inline-actions
                 :tag :task-layer-deleted
                 :cancel {:label (tr "workspace.tasks.layer-deleted.undo")
                          :callback #(apply close dwu/undo (map mark-layer-restored moved))}
                 :accept {:label (tr "workspace.tasks.actions.mark-done")
                          :callback #(apply close (map (fn [task] (set-task-status task "done")) moved))})))))))

(defn- mark-layer-restored
  "The layer of the task is back: the task points at it again."
  [task]
  (ptk/reify ::mark-layer-restored
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state (:page-id task))
            shape   (get objects (:shape-id task))]
        (->> (rp/cmd! :mark-task-layer-restored
                      {:id (:id task)
                       :frame-id (or (some-> shape :frame-id) uuid/zero)})
             (rx/map (fn [_] (refresh)))
             (rx/catch (fn [_] (rx/empty))))))))

(defn restore-task-layer
  "Put back the deleted layer of `task`, as it was when it was deleted
  (the event of the deletion keeps it), and point the task at it again.
  The layer goes back into its parent when the parent is still there,
  into the board that keeps the task otherwise."
  [task]
  (ptk/reify ::restore-task-layer
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:page-id task)
            objects (dsh/lookup-page-objects state page-id)
            events  (get-in state [:workspace-task-events (:id task)])
            deleted (->> events
                         (filter #(= "layer-deleted" (:type %)))
                         (last))
            [root & children :as shapes] (get-in deleted [:payload :objects])]
        (if (or (empty? shapes) (contains? objects (:id root)))
          (rx/of (mark-layer-restored task))
          (let [parent-id (if (contains? objects (:parent-id root))
                            (:parent-id root)
                            (:frame-id task))
                parent    (get objects parent-id)
                frame-id  (if (cfh/frame-shape? parent) parent-id (:frame-id parent))
                root      (assoc root :parent-id parent-id :frame-id frame-id)
                changes   (-> (pcb/empty-changes it page-id)
                              (pcb/with-objects objects)
                              (pcb/add-objects (into [root] children)))]
            (rx/of (dch/commit-changes changes)
                   (mark-layer-restored task)
                   (ntf/success (tr "workspace.tasks.toast.layer-restored" (:name root))))))))))

;; --- Viewport

(defn center-to-task
  "Center the viewport on the mark of `task`."
  [task]
  (ptk/reify ::center-to-task
    ptk/UpdateEvent
    (update [_ state]
      (let [objects  (dsh/lookup-page-objects state (:page-id task))
            position (anchor-position objects task)]
        (if (nil? position)
          state
          (update state :workspace-local
                  (fn [{:keys [vbox] :as local}]
                    (update local :vbox assoc
                            :x (- (:x position) (/ (:width vbox) 2))
                            :y (- (:y position) (/ (:height vbox) 2))))))))

    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

;; --- Counting

(defn tasks-for
  "The tasks that still need work from `profile-id`."
  [tasks profile-id]
  (filter #(and (to-do? %) (= profile-id (:assignee-id %))) tasks))

(defn review-tasks
  "The tasks of the design review `pull-request-id`."
  [tasks pull-request-id]
  (filter #(= pull-request-id (:pull-request-id %)) tasks))

(defn sort-tasks
  "Open work first (oldest first), then the tasks to verify, then the
  closed ones."
  [tasks]
  (sort-by (juxt #(cond (to-do? %) 0 (to-verify? %) 1 :else 2)
                 #(dm/get-prop % :created-at))
           tasks))

(defn search
  [tasks text]
  (if (str/blank? text)
    tasks
    (let [text (str/lower text)]
      (filter #(str/includes? (str/lower (:title %)) text) tasks))))
