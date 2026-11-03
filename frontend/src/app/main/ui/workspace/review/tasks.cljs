;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.review.tasks
  "Tasks in the workspace: the list of tasks, the menu of a task, what a
  task shows inside its thread (its state, what happened to it and what
  to do next) and the form of a new task."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.pull-requests :as dwpr]
   [app.main.data.workspace.review :as dwrv]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.task-row :refer [task-row*]]
   [app.main.ui.ds.product.task-status :refer [task-status*]]
   [app.main.ui.workspace.review.common :as rvc :refer [layer-chip*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn page-objects
  [data page-id]
  (dm/get-in data [:pages-index page-id :objects]))

;; --- Menu of a task

(mf/defc task-menu*
  "The actions of a task, behind a menu trigger: the moves its state
  allows, linking it to another layer and deleting it."
  [{:keys [task]}]
  (let [open*   (mf/use-state false)
        open?   (deref open*)
        status  (:status task)

        on-toggle
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! open* not)))

        on-open-change (mf/use-fn #(reset! open* %))

        set-status
        (fn [status]
          (fn [_]
            (reset! open* false)
            (st/emit! (dwtk/set-task-status task status))))

        on-relink
        (mf/use-fn
         (mf/deps task)
         (fn [_]
           (reset! open* false)
           (st/emit! (dwtk/start-relink (:id task)))))

        on-delete
        (mf/use-fn
         (mf/deps task)
         (fn [_]
           (reset! open* false)
           (st/emit! (dwtk/delete-task (:id task)))))]

    [:div {:on-click dom/stop-propagation}
     [:> menu* {:is-open open?
                :on-open-change on-open-change
                :placement "bottom end"
                :trigger (mf/html
                          [:> icon-button* {:variant "ghost"
                                            :icon i/menu
                                            :aria-label (tr "labels.options")
                                            :aria-pressed open?
                                            :on-click on-toggle}])}
      (when (dwtk/to-do? task)
        [:> menu-item* {:id "done" :on-action (set-status "done")}
         (tr "workspace.tasks.actions.mark-done")])
      (when (= "done" status)
        [:> menu-item* {:id "verify" :on-action (set-status "verified")}
         (tr "workspace.tasks.actions.verify")])
      (when (or (= "done" status) (dwtk/closed? task))
        [:> menu-item* {:id "reopen" :on-action (set-status "reopened")}
         (tr "workspace.tasks.actions.reopen")])
      (when (dwtk/to-do? task)
        [:> menu-item* {:id "wont-do" :on-action (set-status "wont-do")}
         (tr "workspace.tasks.actions.wont-do")])
      (when-not (dwtk/closed? task)
        [:> menu-item* {:id "relink" :on-action on-relink}
         (tr "workspace.tasks.actions.relink")])
      [:> menu-item* {:id "delete" :on-action on-delete}
       (tr "workspace.tasks.actions.delete")]]]))

;; --- List of tasks

(mf/defc task-list-item*
  {::mf/private true}
  [{:keys [task data profiles is-selected on-open]}]
  (let [objects  (page-objects data (:page-id task))
        assignee (get profiles (:assignee-id task))

        on-click
        (mf/use-fn
         (mf/deps task on-open)
         (fn [_] (on-open task)))]

    [:> task-row* {:title (:title task)
                   :status (:status task)
                   :status-label (rvc/status-label (:status task))
                   :layer (rvc/layer-name objects task)
                   :is-layer-lost (dwtk/lost? task)
                   :is-selected is-selected
                   :on-click on-click
                   :assignee (when assignee
                               (mf/html [:> avatar* {:profile assignee :variant "S"}]))
                   :actions (mf/html [:> task-menu* {:task task}])}]))

(mf/defc task-list*
  "The tasks `tasks`, each one opening on the canvas when clicked."
  [{:keys [tasks]}]
  (let [data      (mf/deref refs/workspace-data)
        profiles  (mf/deref refs/profiles)
        local     (mf/deref refs/tasks-local)

        ;; a task opened from a dialog (the details of a review) shows on
        ;; the canvas: the dialog gets out of the way
        on-open
        (mf/use-fn
         (fn [task]
           (st/emit! (modal/hide)
                     (dwtk/navigate-to-task task))))]
    [:ul {:class (stl/css :task-list)}
     (for [task tasks]
       [:li {:key (dm/str (:id task))}
        [:> task-list-item* {:task task
                             :data data
                             :profiles profiles
                             :is-selected (= (:id task) (:open local))
                             :on-open on-open}]])]))

;; --- What happened to a task

(defn- actor-name
  [profiles profile-id me]
  (if (= profile-id me)
    (tr "workspace.tasks.you")
    (rvc/profile-name profiles profile-id)))

(defn- event-text
  [{:keys [type profile-id payload]} profiles me thread?]
  (let [actor (actor-name profiles profile-id me)]
    (case type
      "created"        (if thread?
                         (tr "workspace.tasks.event.created-from-thread" actor)
                         (tr "workspace.tasks.event.created" actor))
      "assigned"       (let [assignee-id (:assignee-id payload)]
                         (cond
                           (nil? assignee-id)
                           (tr "workspace.tasks.event.unassigned" actor)

                           (= assignee-id me)
                           (tr "workspace.tasks.event.assigned-to-you" actor)

                           :else
                           (tr "workspace.tasks.event.assigned" actor
                               (rvc/profile-name profiles assignee-id))))
      "status"         (case (:to payload)
                         "done"     (tr "workspace.tasks.event.done" actor)
                         "verified" (tr "workspace.tasks.event.verified" actor)
                         "reopened" (tr "workspace.tasks.event.reopened" actor)
                         "wont-do"  (tr "workspace.tasks.event.wont-do" actor)
                         (tr "workspace.tasks.event.status" actor))
      "renamed"        (tr "workspace.tasks.event.renamed" actor)
      "relinked"       (tr "workspace.tasks.event.relinked" actor (:shape-name payload))
      "layer-deleted"  (tr "workspace.tasks.event.layer-deleted" actor (:shape-name payload))
      "layer-restored" (tr "workspace.tasks.event.layer-restored" actor (:shape-name payload))
      "moved-to-main"  (tr "workspace.tasks.event.moved-to-main" (:branch-name payload))
      nil)))

(defn- event-icon
  [{:keys [type payload]}]
  (case type
    "layer-deleted" i/triangle-alert
    "status"        (if (= "done" (:to payload)) i/tick i/view-as-list)
    "moved-to-main" i/git-merge
    i/view-as-list))

(mf/defc task-event*
  "One line of what happened to a task."
  [{:keys [event thread]}]
  (let [profiles (mf/deref refs/profiles)
        profile  (mf/deref refs/profile)
        text     (event-text event profiles (:id profile) (some? thread))]
    (when text
      [:div {:class (stl/css-case :task-event true
                                  :is-warning (= "layer-deleted" (:type event)))}
       [:> icon* {:icon-id (event-icon event) :size "s" :class (stl/css :task-event-icon)}]
       [:> text* {:as "span" :typography t/body-small :class (stl/css :task-event-text)}
        text
        [:span {:class (stl/css :task-event-time)}
         " - " (ct/timeago (:created-at event))]]])))

;; --- State of a task and what to do next

(mf/defc task-state-line*
  "The state of a task, who it is for and the review it comes from."
  [{:keys [task]}]
  (let [profiles  (mf/deref refs/profiles)
        profile   (mf/deref refs/profile)
        preview   (mf/deref refs/pull-request-preview)
        for-me?   (= (:assignee-id task) (:id profile))
        reviewing (= (:pull-request-id task) (:pr-id preview))
        file-id   (mf/use-ctx ctx/current-file-id)

        on-open-review
        (mf/use-fn
         (mf/deps task file-id)
         (fn [_]
           (st/emit! (dwpr/open-pull-request {:id (:pull-request-id task)
                                              :source-file-id file-id}))))]

    [:div {:class (stl/css :task-state-line)}
     [:> task-status* {:status (:status task)
                       :label (rvc/status-label (:status task))}]
     [:> text* {:as "span" :typography t/body-small :class (stl/css :task-state-text)}
      (cond
        for-me?                  (tr "workspace.tasks.for-you")
        (some? (:assignee-id task)) (tr "workspace.tasks.assigned-to"
                                        (rvc/profile-name profiles (:assignee-id task)))
        :else                    (tr "workspace.tasks.unassigned"))]
     (when (and (some? (:pull-request-id task))
                (= "open" (:review-status task))
                (not reviewing))
       [:*
        [:> text* {:as "span" :typography t/body-small :class (stl/css :task-state-text)}
         (tr "workspace.tasks.from")]
        [:button {:class (stl/css :task-review-link)
                  :type "button"
                  :on-click on-open-review}
         [:> icon* {:icon-id i/git-pull-request-arrow :size "s"}]
         (:review-title task)]])]))

(mf/defc task-next-step*
  "What to do next with a task: verify a done task (or reopen it), and
  decide about a task whose layer was deleted."
  [{:keys [task]}]
  (let [profiles  (mf/deref refs/profiles)
        data      (mf/deref refs/workspace-data)
        preview   (mf/deref refs/pull-request-preview)
        objects   (page-objects data (:page-id task))
        status    (:status task)

        set-status
        (mf/use-fn
         (mf/deps task)
         (fn [event]
           (let [status (-> (dom/get-current-target event)
                            (dom/get-data "status"))]
             (st/emit! (dwtk/set-task-status task status)))))

        on-show-change
        (mf/use-fn
         (mf/deps task)
         (fn [_]
           (st/emit! (dwtk/close-task)
                     (dwrv/show-change-of (:shape-id task)))))

        on-restore
        (mf/use-fn
         (mf/deps task)
         (fn [_]
           (st/emit! (dwtk/restore-task-layer task))))

        on-relink
        (mf/use-fn
         (mf/deps task)
         (fn [_]
           (st/emit! (dwtk/start-relink (:id task)))))]

    (cond
      (and (dwtk/lost? task) (not (dwtk/closed? task)))
      [:div {:class (stl/css :task-next-step)}
       [:> text* {:as "p" :typography t/body-small}
        (tr "workspace.tasks.lost.message" (rvc/board-name objects (:frame-id task)))]
       [:div {:class (stl/css :task-next-step-actions)}
        ;; restoring and relinking edit the branch: not inside a review
        (when (nil? preview)
          [:> button* {:variant "ghost" :icon i/history :on-click on-restore}
           (tr "workspace.tasks.actions.restore-layer")])
        (when (nil? preview)
          [:> button* {:variant "secondary" :on-click on-relink}
           (tr "workspace.tasks.actions.relink-short")])
        [:> button* {:variant "primary" :data-status "done" :on-click set-status}
         (tr "workspace.tasks.actions.mark-done")]]]

      (= "done" status)
      [:div {:class (stl/css :task-next-step)}
       [:> text* {:as "p" :typography t/body-small}
        (if (some? (:assignee-id task))
          (tr "workspace.tasks.verify.message"
              (rvc/profile-name profiles (:assignee-id task)))
          (tr "workspace.tasks.verify.message-unassigned"))]
       [:div {:class (stl/css :task-next-step-actions)}
        (when (and (some? preview) (some? (:shape-id task)))
          [:> button* {:variant "ghost" :icon i/shown :on-click on-show-change}
           (tr "workspace.tasks.actions.show-change")])
        [:> button* {:variant "secondary" :data-status "reopened" :on-click set-status}
         (tr "workspace.tasks.actions.reopen")]
        [:> button* {:variant "primary" :data-status "verified" :on-click set-status}
         (tr "workspace.tasks.actions.verify")]]]

      (dwtk/to-do? task)
      [:div {:class (stl/css :task-next-step :is-inline)}
       [:> button* {:variant "primary" :data-status "done" :on-click set-status}
        (tr "workspace.tasks.actions.mark-done")]]

      :else nil)))

(defn use-task-events
  "The events of `task`, loaded once the task is shown."
  [task-id]
  (let [events (mf/deref refs/task-events)]
    (mf/with-effect [task-id]
      (when task-id
        (st/emit! (dwtk/fetch-task-events task-id))))
    (get events task-id)))

;; --- A task without a thread, open on the canvas

(mf/defc task-panel*
  "A task that has no comment thread, open on the canvas next to its
  mark: its title, state, what happened to it and what to do next."
  [{:keys [task style class]}]
  (let [data    (mf/deref refs/workspace-data)
        objects (page-objects data (:page-id task))
        events  (use-task-events (:id task))

        on-close
        (mf/use-fn #(st/emit! (dwtk/close-task)))

        on-key-down
        (mf/use-fn
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (st/emit! (dwtk/close-task)))))]

    [:div {:class [class (stl/css :floating-panel)]
           :style style
           :role "dialog"
           :aria-label (:title task)
           :on-key-down on-key-down
           :on-click dom/stop-propagation}
     [:div {:class (stl/css :floating-header)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :floating-title)}
       (tr "workspace.tasks.task") " - " (rvc/board-name objects (:frame-id task))]
      [:> task-menu* {:task task}]
      [:> icon-button* {:variant "ghost"
                        :icon i/close
                        :aria-label (tr "labels.close")
                        :on-click on-close}]]
     [:div {:class (stl/css :floating-body)}
      [:> task-state-line* {:task task}]
      [:> text* {:as "p" :typography t/body-medium :class (stl/css :task-title)} (:title task)]
      [:> layer-chip* {:label (rvc/layer-name objects task)
                       :is-lost (dwtk/lost? task)
                       :is-struck (dwtk/lost? task)}]
      (for [event events]
        [:> task-event* {:key (dm/str (:id event)) :event event}])
      [:> task-next-step* {:task task}]]]))

;; --- A new task

(mf/defc new-task-form*
  "The form of a new task, next to the layer it is linked to. From a
  comment thread it is \"Make it a task\": the title comes from the
  comment and the thread stays attached to the task."
  [{:keys [params style class]}]
  (let [team      (mf/deref refs/team)
        profile   (mf/deref refs/profile)
        data      (mf/deref refs/workspace-data)
        objects   (page-objects data (:page-id params))
        thread?   (some? (:thread-id params))

        title*    (mf/use-state (or (:title params) ""))
        title     (deref title*)
        assignee* (mf/use-state (or (some-> (:assignee-id params) str) ""))
        assignee  (deref assignee*)

        members   (:members team)
        options   (mf/with-memo [members profile]
                    (into [{:id "" :label (tr "workspace.tasks.unassigned")}]
                          (map (fn [{:keys [id fullname]}]
                                 {:id (str id)
                                  :label (if (= id (:id profile))
                                           (tr "workspace.tasks.assignee-me" fullname)
                                           fullname)}))
                          (sort-by :fullname members)))

        valid?    (not (str/blank? title))
        heading   (if thread?
                    (tr "workspace.tasks.make-task")
                    (tr "workspace.tasks.new-task"))

        on-title-change (mf/use-fn #(reset! title* (dom/get-target-val %)))
        on-assignee     (mf/use-fn #(reset! assignee* %))

        on-cancel
        (mf/use-fn #(st/emit! (dwtk/cancel-new-task)))

        on-submit
        (mf/use-fn
         (mf/deps params title assignee valid?)
         (fn [event]
           (dom/prevent-default event)
           (when valid?
             (st/emit! (dwtk/create-task
                        (-> params
                            (assoc :title (str/trim title))
                            (assoc :assignee-id (when-not (str/blank? assignee)
                                                  (uuid/parse assignee)))))))))

        on-key-down
        (mf/use-fn
         (mf/deps on-submit)
         (fn [event]
           (cond
             (kbd/esc? event)
             (do (dom/stop-propagation event)
                 (st/emit! (dwtk/cancel-new-task)))

             (and (kbd/enter? event) (kbd/mod? event))
             (on-submit event))))]

    [:form {:class [class (stl/css :floating-panel)]
            :style style
            :aria-label heading
            :on-submit on-submit
            :on-key-down on-key-down
            :on-click dom/stop-propagation}
     [:div {:class (stl/css :floating-header)}
      [:> icon* {:icon-id i/view-as-list :size "s" :class (stl/css :floating-header-icon)}]
      [:> text* {:as "span" :typography t/body-medium :class (stl/css :floating-title :is-strong)}
       heading]
      [:> icon-button* {:variant "ghost"
                        :type "button"
                        :icon i/close
                        :aria-label (tr "labels.close")
                        :on-click on-cancel}]]
     [:div {:class (stl/css :floating-body)}
      [:textarea {:class (stl/css :task-title-input)
                  :value title
                  :auto-focus true
                  :rows 3
                  :max-length 250
                  :aria-label (tr "workspace.tasks.title")
                  :placeholder (tr "workspace.tasks.title-placeholder")
                  :on-change on-title-change}]
      (when thread?
        [:> text* {:as "p" :typography t/body-small :class (stl/css :form-hint)}
         (tr "workspace.tasks.make-task.hint")])
      [:div {:class (stl/css :form-row)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :form-label)}
        (tr "workspace.tasks.assignee")]
       [:> select* {:options options
                    :default-selected assignee
                    :on-change on-assignee
                    :class (stl/css :form-select)}]]
      [:div {:class (stl/css :form-row)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :form-label)}
        (tr "workspace.tasks.linked-to")]
       [:> layer-chip* {:label (rvc/layer-name objects params)}]]
      [:div {:class (stl/css :form-actions)}
       [:> button* {:variant "ghost" :type "button" :on-click on-cancel}
        (tr "labels.cancel")]
       [:> button* {:variant "primary" :type "submit" :disabled (not valid?)}
        (tr "workspace.tasks.create")]]]]))

(defn filter-tasks
  "The tasks a filter of the task list keeps."
  [tasks kind profile-id]
  (case kind
    :open      (filter dwtk/to-do? tasks)
    :mine      (filter #(= profile-id (:assignee-id %)) tasks)
    :to-verify (filter dwtk/to-verify? tasks)
    tasks))

(defn closed-count
  [tasks]
  (count (filter dwtk/closed? tasks)))

(mf/defc progress*
  "\"2 of 7 closed\" over a bar, and the tasks waiting to be verified."
  [{:keys [tasks on-to-verify]}]
  (let [total     (count tasks)
        closed    (closed-count tasks)
        to-verify (count (filter dwtk/to-verify? tasks))]
    [:div {:class (stl/css :progress)}
     [:div {:class (stl/css :progress-head)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :progress-label)}
       (tr "workspace.tasks.closed-of" closed total)]
      (when (pos? to-verify)
        [:button {:class (stl/css :progress-link)
                  :type "button"
                  :on-click on-to-verify}
         (tr "workspace.tasks.to-verify" to-verify)])]
     [:div {:class (stl/css :progress-bar)
            :role "progressbar"
            :aria-valuemin 0
            :aria-valuemax total
            :aria-valuenow closed}
      [:div {:class (stl/css :progress-fill)
             :style #js {:width (dm/str (if (pos? total) (* 100 (/ closed total)) 0) "%")}}]]]))

(defn count-by
  [f tasks]
  (count (filter f tasks)))

(defn group-tasks
  "The tasks of the branch workspace list: those of each design review
  first (the open review first), then the others."
  [tasks]
  (let [[review other] ((juxt filter remove) (comp some? :pull-request-id) tasks)]
    (concat
     (->> (group-by :pull-request-id review)
          (sort-by (fn [[_ items]] (if (= "open" (:review-status (first items))) 0 1)))
          (map (fn [[id items]] {:id id :title (:review-title (first items)) :tasks items})))
     (when (seq other)
       [{:id :other :tasks other}]))))

(defn visible-tasks
  "The tasks of the file that are still work of this file: the history of
  merged branches stays out."
  [tasks]
  (->> (vals tasks)
       (remove dwtk/history?)
       (dwtk/sort-tasks)))

(defn task-of-thread
  [tasks thread-id]
  (d/seek #(= thread-id (:thread-id %)) (vals tasks)))
