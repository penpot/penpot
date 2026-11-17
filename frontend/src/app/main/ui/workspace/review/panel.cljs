;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.panel
  "The right panel of the review mode and the tasks tab of the comments
  panel: the comments of the review, grouped by board, and its tasks."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.time :as ct]
   [app.main.data.comments :as dcmt]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.review :as dwrv]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.comment-card :refer [comment-card*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.product.layer-chip :refer [layer-chip*]]
   [app.main.ui.workspace.review.common :as rvc]
   [app.main.ui.workspace.review.filter-menu :refer [filter-menu*]]
   [app.main.ui.workspace.review.tasks :as rvt]
   [app.main.ui.workspace.sidebar.pull-requests :as prui :refer [pr-state-badge*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; --- Comments

(mf/defc thread-card*
  "A comment thread of the review: its number and board, who wrote it and
  when, what it points at, its text and its replies. With `show-review`,
  it names the design review it belongs to."
  {::mf/private true}
  [{:keys [thread objects is-open show-review]}]
  (let [owner   (d/without-nils (dcmt/get-owner thread))
        replies (dec (or (:count-comments thread) 1))
        review? (some? (:pull-request-id thread))

        on-click
        (mf/use-fn
         (mf/deps thread)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dwcm/navigate-to-comment thread))))]

    [:> comment-card* {:location (tr "workspace.review.text-and-detail"
                                     (tr "workspace.review.comment-number" (:seqn thread))
                                     (rvc/board-name objects (:frame-id thread)))
                       :author (:fullname owner)
                       :time (ct/timeago (:modified-at thread))
                       :replies (case replies
                                  0 (tr "workspace.review.comments.no-replies")
                                  1 (tr "workspace.review.comments.one-reply")
                                  (tr "workspace.review.comments.replies" replies))
                       :avatar (mf/html [:> avatar* {:profile owner :variant "M"}])
                       :anchor (mf/html
                                [:> layer-chip* {:label (rvc/thread-anchor-label objects thread)
                                                 :icon (rvc/thread-anchor-icon thread)}])
                       :review (when (and show-review review?)
                                 (or (:pull-request-title thread)
                                     (tr "workspace.review.design-review")))
                       :is-review review?
                       :is-open is-open
                       :is-resolved (:is-resolved thread)
                       :on-click on-click}
     [:> cmt/comment-content* {:content (:content thread)}]]))

(mf/defc comments-tab*
  "The comment threads `threads`: filters and the cards grouped by board
  (by page and board with `show-page`). With `show-review`, each card names
  the design review it belongs to."
  [{:keys [threads show-page show-review]}]
  (let [profile  (mf/deref refs/profile)
        clocal   (mf/deref refs/comments-local)
        data     (mf/deref refs/workspace-data)
        mode     (get clocal :mode :all)

        ;; the same filters as the marks on the canvas
        shown    (mf/with-memo [threads clocal profile]
                   (->> (dcmt/apply-filters clocal profile threads)
                        (sort-by :seqn)))

        groups   (mf/with-memo [shown]
                   (->> shown
                        (group-by (juxt :page-id :frame-id))
                        (sort-by (fn [[_ items]] (:seqn (first items))))))

        options  [{:id :all :label (tr "labels.show-all-comments")}
                  {:id :yours :label (tr "labels.show-your-comments")}
                  {:id :mentions :label (tr "labels.show-mentions")}]

        on-select
        (mf/use-fn #(st/emit! (dcmt/update-filters {:mode %})))

        on-toggle
        (mf/use-fn
         (mf/deps clocal)
         (fn [_]
           (st/emit! (dcmt/update-filters {:show (if (= :pending (:show clocal)) :all :pending)}))))]

    [:div {:class (stl/css :tab-content)}
     [:> filter-menu* {:options options
                       :selected mode
                       :toggle-label (tr "labels.hide-resolved-comments")
                       :is-toggled (= :pending (:show clocal))
                       :on-select on-select
                       :on-toggle on-toggle}]

     (if (empty? groups)
       [:div {:class (stl/css :empty)}
        [:> empty-state* {:icon i/comments
                          :text (tr "workspace.review.comments.empty")}]]
       [:div {:class (stl/css :groups)}
        (for [[[page-id frame-id] items] groups]
          (let [objects (dm/get-in data [:pages-index page-id :objects])]
            [:section {:key (dm/str page-id frame-id) :class (stl/css :group)}
             [:> heading* {:level 3 :typography t/body-small :class (stl/css :group-title)}
              (if show-page
                (tr "workspace.review.text-and-detail"
                    (:page-name (first items))
                    (rvc/board-name objects frame-id))
                (rvc/board-name objects frame-id))]
             (for [thread items]
               [:> thread-card* {:key (dm/str (:id thread))
                                 :thread thread
                                 :objects objects
                                 :show-review show-review
                                 :is-open (= (:id thread) (:open clocal))}])]))])]))

;; --- Tasks

(mf/defc tasks-tab*
  "The tasks of the review (`pull-request-id`), or of the file when there
  is none: its progress, filters and list. With `show-review`, each task
  names the design review it belongs to."
  [{:keys [tasks pull-request-id grouped show-review]}]
  (let [profile  (mf/deref refs/profile)
        local    (mf/deref refs/review-local)
        selected (mf/deref refs/selected-shapes)
        objects  (mf/deref refs/workspace-page-objects)
        page-id  (mf/deref refs/current-page-id)
        kind     (get local :task-filter :all)
        hide?    (true? (:hide-closed-tasks local))

        shown    (mf/with-memo [tasks kind hide? profile]
                   (cond->> (rvt/filter-tasks tasks kind (:id profile))
                     hide? (remove dwtk/closed?)))

        options  [{:id :all :label (tr "workspace.tasks.view.all")}
                  {:id :mine :label (tr "workspace.tasks.view.mine")}
                  {:id :open :label (tr "workspace.tasks.view.open")}
                  {:id :to-verify :label (tr "workspace.tasks.view.to-verify")}]

        on-select
        (mf/use-fn #(st/emit! (dwrv/set-task-filter %)))

        on-toggle
        (mf/use-fn #(st/emit! (dwrv/toggle-hide-closed-tasks)))

        on-to-verify
        (mf/use-fn #(st/emit! (dwrv/set-task-filter :to-verify)))

        on-new-task
        (mf/use-fn
         (mf/deps selected objects page-id pull-request-id)
         (fn [_]
           (let [shape (some->> (first selected) (get objects))]
             (if (some? shape)
               (st/emit! (dwtk/start-new-task
                          {:page-id page-id
                           :frame-id (if (cfh/frame-shape? shape) (:id shape) (:frame-id shape))
                           :shape-id (:id shape)
                           :pull-request-id pull-request-id}))
               ;; no layer chosen yet: the task tool picks it on the canvas
               (st/emit! (dwrv/set-tool :task))))))]

    [:div {:class (stl/css :tab-content)}
     [:> rvt/progress* {:tasks tasks :on-to-verify on-to-verify}]

     [:> filter-menu* {:options options
                       :selected kind
                       :toggle-label (tr "workspace.tasks.view.hide-closed")
                       :is-toggled hide?
                       :on-select on-select
                       :on-toggle on-toggle}]

     (cond
       (empty? shown)
       [:div {:class (stl/css :empty)}
        [:> empty-state* {:icon i/view-as-list
                          :text (tr "workspace.tasks.empty")}]]

       grouped
       [:div {:class (stl/css :groups)}
        (for [{:keys [id title tasks]} (rvt/group-tasks shown)]
          [:section {:key (dm/str id) :class (stl/css :group)}
           [:> heading* {:level 3 :typography t/body-small :class (stl/css :group-title)}
            (if (= :other id)
              (tr "workspace.tasks.group.other")
              (tr "workspace.tasks.group.review" title))]
           [:> rvt/task-list* {:tasks tasks :show-review show-review}]])]

       :else
       [:> rvt/task-list* {:tasks shown :show-review show-review}])

     [:> button* {:variant "secondary"
                  :icon i/add
                  :class (stl/css :new-task)
                  :on-click on-new-task}
      (tr "workspace.tasks.new-task")]]))

;; --- The review panel

(mf/defc review-header*
  {::mf/private true}
  [{:keys [pr]}]
  (let [[approvals total] (prui/current-approvals pr)
        route (tr "workspace.review.route" (:branch-name pr) (tr "workspace.branches.main"))]
    [:div {:class (stl/css :review-header)}
     [:div {:class (stl/css :review-title-row)}
      [:> heading* {:level 2 :typography t/body-large :class (stl/css :review-title)}
       (:title pr)]
      [:> pr-state-badge* {:pr pr}]]
     [:> text* {:as "span" :typography t/body-small :class (stl/css :review-route)}
      (if (pos? total)
        (tr "workspace.review.text-and-detail" route (tr "workspace.review.approved-of" approvals total))
        route)]]))

(mf/defc review-panel*
  "The right panel of the review mode: the inspect of the selection
  (`inspect`, the element the workspace renders for it), and the
  comments and tasks of the review."
  [{:keys [inspect]}]
  (let [preview  (mf/deref refs/pull-request-preview)
        pr       (:info preview)
        pr-id    (:pr-id preview)
        threads* (mf/deref refs/threads)
        tasks*   (mf/deref refs/tasks)
        local    (mf/deref refs/review-local)
        ;; the review opens on the inspect of the selection
        tab      (get local :panel-tab :inspect)

        threads  (mf/with-memo [threads* pr-id]
                   (filterv #(= pr-id (:pull-request-id %)) (vals threads*)))
        tasks    (mf/with-memo [tasks* pr-id]
                   (vec (dwtk/sort-tasks (dwtk/review-tasks (vals tasks*) pr-id))))

        tabs     (mf/with-memo [threads tasks]
                   [{:id "inspect"
                     :label (tr "workspace.options.inspect")}
                    {:id "comments"
                     :label (tr "workspace.review.tab.comments" (count threads))}
                    {:id "tasks"
                     :label (tr "workspace.review.tab.tasks" (count tasks))}])

        ;; inspecting means picking layers: the move tool takes the clicks
        on-tab   (mf/use-fn
                  (fn [id]
                    (let [tab (keyword id)]
                      (st/emit! (dwrv/set-panel-tab tab))
                      (when (= tab :inspect)
                        (st/emit! (dwrv/set-tool :move))))))]

    (mf/with-effect [pr-id]
      (st/emit! (dwtk/fetch-tasks)))

    (when (some? pr)
      [:div {:class (stl/css :review-panel)}
       [:> review-header* {:pr pr}]
       ;; the inspect scrolls on its own: its tab only gives it the space
       [:> tab-switcher* {:tabs tabs
                          :selected (name tab)
                          :on-change on-tab
                          :scrollable-panel (not= tab :inspect)
                          :class (stl/css :review-tabs)}
        (if (= tab :inspect)
          [:div {:class (stl/css :inspect-tab)} inspect]
          [:div {:class (stl/css :review-tab-content)}
           (if (= tab :tasks)
             [:> tasks-tab* {:tasks tasks :pull-request-id pr-id}]
             [:> comments-tab* {:threads threads}])])]])))
