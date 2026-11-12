;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.details
  "The details of a design review: an overview (who opened it, where it
  stands, its description and reviewers), its activity (what the saves
  of the branch did since it opened) and its tasks."
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
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.link-button :refer [link-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.textarea :refer [textarea*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.modal :refer [modal* modal-content* modal-footer*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.change-row :refer [change-row*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.product.filter-chip :refer [filter-chip*]]
   [app.main.ui.ds.product.progress-bar :refer [progress-bar*]]
   [app.main.ui.ds.product.tag :refer [tag*]]
   [app.main.ui.ds.product.task-status :refer [task-status*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.review.common :as rvc]
   [app.main.ui.workspace.review.tasks :as rvt]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.main.ui.workspace.sidebar.branch-review.previews :as brp]
   [app.main.ui.workspace.sidebar.pull-requests :as prui :refer [pr-state-badge* dialog-header*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- review-tasks
  [tasks pr-id]
  (vec (dwtk/sort-tasks (dwtk/review-tasks (vals tasks) pr-id))))

;; --- Overview

(mf/defc stat-card*
  {::mf/private true}
  [{:keys [label children]}]
  [:div {:class (stl/css :stat-card)}
   [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)} label]
   [:div {:class (stl/css :stat-value)} children]])

(mf/defc description-block*
  "The description of the review; its author and the admins edit it."
  {::mf/private true}
  [{:keys [pr can-edit]}]
  (let [editing* (mf/use-state false)
        editing? (deref editing*)
        value*   (mf/use-state (or (:description pr) ""))
        value    (deref value*)

        on-edit   (mf/use-fn #(reset! editing* true))
        on-change (mf/use-fn #(reset! value* (dom/get-target-val %)))
        on-cancel (mf/use-fn (mf/deps pr)
                             (fn [_]
                               (reset! value* (or (:description pr) ""))
                               (reset! editing* false)))
        on-save   (mf/use-fn
                   (mf/deps pr value)
                   (fn [_]
                     (reset! editing* false)
                     (st/emit! (dwpr/update-pull-request (:id pr) {:description (str/trim value)}))))]

    [:section {:class (stl/css :section)}
     [:div {:class (stl/css :section-head)}
      [:> heading* {:level 3 :typography t/body-small :class (stl/css :section-title)}
       (tr "workspace.pull-requests.info.description")]
      (when (and can-edit (not editing?))
        [:> button* {:variant "ghost" :on-click on-edit}
         (tr "labels.edit")])]
     ;; the same look as the description of a branch (its info dialog)
     (cond
       editing?
       [:div {:class (stl/css :edit-block)}
        [:> textarea* {:class (stl/css :description-input)
                       :value value
                       :rows 3
                       :max-length 4000
                       :auto-focus true
                       :aria-label (tr "workspace.pull-requests.info.description")
                       :on-change on-change}]
        [:div {:class (stl/css :edit-actions)}
         [:> button* {:variant "secondary" :on-click on-cancel} (tr "labels.cancel")]
         [:> button* {:variant "primary" :on-click on-save} (tr "labels.save")]]]

       (str/blank? (:description pr))
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :description-empty)}
        (tr "workspace.review.details.no-description")]

       :else
       [:> text* {:as "p" :typography t/body-medium :class (stl/css :description)}
        (:description pr)])]))

(defn- verdict-label
  [{:keys [state stale]}]
  (cond
    stale                         (tr "workspace.pull-requests.review-state.stale")
    (= "approved" state)          (tr "workspace.pull-requests.review-state.approved")
    (= "changes-requested" state) (tr "workspace.pull-requests.review-state.changes-requested")
    :else                         (tr "workspace.pull-requests.review-state.pending")))

(defn- verdict-tone
  [{:keys [state stale]}]
  (cond
    stale                         "neutral"
    (= "approved" state)          "success"
    (= "changes-requested" state) "warning"
    :else                         "neutral"))

(mf/defc reviewers-block*
  "The reviewers of the review and their verdicts; its author and the
  admins add reviewers."
  {::mf/private true}
  [{:keys [pr can-edit]}]
  (let [profiles (mf/deref refs/profiles)
        team     (mf/deref refs/team)
        adding*  (mf/use-state false)
        adding?  (deref adding*)

        current  (into #{} (map :profile-id) (:reviews pr))
        options  (mf/with-memo [team current pr]
                   (->> (:members team)
                        (remove #(contains? current (:id %)))
                        (remove #(= (:created-by pr) (:id %)))
                        (sort-by :fullname)
                        (mapv (fn [{:keys [id fullname]}] {:id (str id) :label fullname}))))

        on-add    (mf/use-fn #(reset! adding* true))
        on-select (mf/use-fn
                   (mf/deps pr current)
                   (fn [id]
                     (reset! adding* false)
                     (st/emit! (dwpr/update-pull-request
                                (:id pr)
                                {:reviewers (conj (vec current) (uuid/parse id))}))))]

    [:section {:class (stl/css :section)}
     [:div {:class (stl/css :section-head)}
      [:> heading* {:level 3 :typography t/body-small :class (stl/css :section-title)}
       (tr "workspace.pull-requests.info.reviewers")]
      (when (and can-edit (seq options) (not adding?))
        [:> link-button* {:icon i/add :on-click on-add}
         (tr "workspace.review.details.add-reviewer")])]
     (when adding?
       [:> select* {:options (into [{:id "" :label (tr "workspace.review.details.pick-reviewer")}] options)
                    :default-selected ""
                    :on-change on-select
                    :has-portal true
                    :wrapper-class (stl/css :reviewer-picker)}])
     (if (empty? (:reviews pr))
       [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)}
        (tr "workspace.review.details.no-reviewers")]
       [:ul {:class (stl/css :reviewers)}
        (for [review (:reviews pr)]
          (let [reviewer (get profiles (:profile-id review))]
            [:li {:key (dm/str (:profile-id review)) :class (stl/css :reviewer)}
             [:div {:class (stl/css :reviewer-head)}
              (when reviewer [:> avatar* {:profile reviewer :variant "S"}])
              [:> text* {:as "span" :typography t/body-small :class (stl/css :strong)}
               (rvc/profile-name profiles (:profile-id review))]
              [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
               (ct/format-inst (:updated-at review) :localized-date-time)]
              [:> tag* {:variant "pill"
                        :tone (verdict-tone review)
                        :class (stl/css :push-end)}
               (verdict-label review)]]
             (when-not (str/blank? (:comment review))
               [:> text* {:as "p" :typography t/body-small :class (stl/css :reviewer-comment)}
                (tr "workspace.review.details.quoted-comment" (:comment review))])]))])]))

(mf/defc overview-tab*
  {::mf/private true}
  [{:keys [pr tasks activity]}]
  (let [profiles (mf/deref refs/profiles)
        profile  (mf/deref refs/profile)
        team     (mf/deref refs/team)
        author   (get profiles (:created-by pr))
        can-edit (or (= (:created-by pr) (:id profile))
                     (boolean (get-in team [:permissions :is-admin])))

        [approvals total] (prui/current-approvals pr)
        missing  (- total approvals)
        open     (count (remove dwtk/closed? tasks))
        closed   (count (filter dwtk/closed? tasks))
        counts   (:counts pr)
        last-row (first (:rows activity))]

    [:div {:class (stl/css :tab-body)}
     [:div {:class (stl/css :facts)}
      [:div {:class (stl/css :fact)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
        (tr "workspace.pull-requests.info.author")]
       [:div {:class (stl/css :fact-value)}
        (when author [:> avatar* {:profile author :variant "S"}])
        [:> text* {:as "span" :typography t/body-small} (rvc/profile-name profiles (:created-by pr))]]]
      [:div {:class (stl/css :fact)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
        (tr "workspace.review.details.opened")]
       [:> text* {:as "span" :typography t/body-small :class (stl/css :fact-value)}
        (ct/format-inst (:created-at pr) :localized-date-time)]]
      [:div {:class (stl/css :fact)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
        (tr "workspace.review.details.last-change")]
       [:> text* {:as "span" :typography t/body-small :class (stl/css :fact-value)}
        (if (some? last-row)
          (tr "workspace.review.details.last-change-by"
              (ct/timeago (:created-at last-row))
              (rvc/profile-name profiles (:profile-id last-row)))
          (ct/timeago (:review-updated-at pr)))]]]

     (when (and (pos? missing) (= "open" (:status pr)))
       [:> context-notification* {:level :warning :appearance :ghost}
        (dm/str (tr "workspace.review.details.needs-approvals" (i18n/c missing))
                (when (pos? open)
                  (dm/str " " (tr "workspace.review.details.open-tasks-dont-block" (i18n/c open)))))])

     [:div {:class (stl/css :stats)}
      [:> stat-card* {:label (tr "workspace.pull-requests.info.approvals")}
       [:span (tr "workspace.review.details.x-of-y" approvals total)]
       [:span {:class (stl/css :avatars)}
        (for [review (:reviews pr)]
          (when-let [reviewer (get profiles (:profile-id review))]
            [:> avatar* {:key (dm/str (:profile-id review)) :profile reviewer :variant "S"}]))]]
      [:> stat-card* {:label (tr "workspace.review.details.comments")}
       [:span (tr "workspace.review.details.comments-open" (:comments-open counts 0))]
       [:span {:class (stl/css :muted)}
        (tr "workspace.review.details.comments-resolved" (:comments-resolved counts 0))]]
      [:> stat-card* {:label (tr "workspace.review.tab.tasks-plain")}
       [:span (tr "workspace.review.details.x-of-y" closed (count tasks))]
       [:> progress-bar* {:value closed :total (count tasks) :class (stl/css :mini-bar)}]]
      [:> stat-card* {:label (tr "workspace.review.details.branch")}
       (cond
         (pos? (or (:conflicts pr) 0))
         [:span {:class (stl/css :warning)} (tr "workspace.pull-requests.badge.conflicts" (dm/str (:conflicts pr)))]

         (pos? (or (:behind pr) 0))
         [:span {:class (stl/css :warning)} (tr "workspace.review.details.behind" (i18n/c (:behind pr)))]

         :else
         [:span {:class (stl/css :ok)}
          [:span {:class (stl/css :dot)}]
          (tr "workspace.review.details.up-to-date")])]]

     [:> description-block* {:pr pr :can-edit can-edit}]
     [:> reviewers-block* {:pr pr :can-edit can-edit}]]))

;; --- Activity

(defn- day-label
  "Today, yesterday, or the date of `inst`: the heading of the changes
  made that day."
  [inst]
  (let [day       (fn [ms] (.toDateString (js/Date. ms)))
        now       (js/Date.now)
        this-day  (day (inst-ms inst))]
    (cond
      (= this-day (day now))              (tr "workspace.review.activity.today")
      (= this-day (day (- now 86400000))) (tr "workspace.review.activity.yesterday")
      :else                               (ct/format-inst inst :localized-date))))

(defn- kind-tone
  [kind]
  (case kind :added "success" :deleted "error" "info"))

(defn- display-attr
  "A property of a change as the hover card shows it: its label and its
  values before and after, the token behind a value when there is one."
  [attr [before after] [tokens-before tokens-after]]
  (let [token-key (case attr :fills :fill :strokes :stroke-color nil)]
    [(brc/attr-label attr)
     (or (get tokens-before token-key) before)
     (or (get tokens-after token-key) after)]))

(def ^:private card-width
  "The width of the card of a change, as its style sets it."
  320)

(defn- card-style
  "Put the card beside the row box `rect` (viewport coordinates), on its
  right when it fits there and on its left otherwise, inside the window."
  [{:keys [left right top]}]
  (let [gap       8
        win-w     (.-innerWidth js/window)
        win-h     (.-innerHeight js/window)
        x         (if (< (+ right gap card-width) win-w)
                    (+ right gap)
                    (max gap (- left gap card-width)))
        y         (min top (- win-h 360))]
    #js {:left (dm/str x "px")
         :top (dm/str (max gap y) "px")}))

(mf/defc change-card*
  "What a change did, beside its row. It goes in a portal so the scroll of
  the dialog never cuts it."
  {::mf/private true}
  [{:keys [entry tasks rect]}]
  (let [profiles (mf/deref refs/profiles)
        data     (mf/deref refs/workspace-data)
        objects  (dm/get-in data [:pages-index (:page-id entry) :objects])
        shape    (get objects (:id entry))
        attrs    (:attrs entry)
        tokens   (:applied-tokens attrs)
        linked   (filterv #(= (:id entry) (:shape-id %)) tasks)]

    [:div {:class (stl/css :change-card) :role "tooltip" :style (card-style rect)}
     [:div {:class (stl/css :change-card-head)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :strong)} (:name entry)]
      [:> tag* {:tone (kind-tone (:kind entry))} (rvc/change-kind-label (:kind entry))]]
     [:div {:class (stl/css :change-card-preview)}
      (if (and (some? shape) (not= :deleted (:kind entry)))
        [:> brp/shape-preview* {:shape shape :objects objects}]
        [:div {:class (stl/css :deleted-preview)}])]
     (when (seq attrs)
       [:dl {:class (stl/css :attrs)}
        (for [[attr diff] (dissoc attrs :applied-tokens :parent-id)]
          (let [[label before after] (display-attr attr diff tokens)]
            [:* {:key (name attr)}
             [:dt {:class (stl/css :muted)} label]
             [:dd {:class (stl/css :attr-value)}
              [:> brl/value-chip* {:value before :is-old true}]
              [:> icon* {:icon-id i/arrow-long-right :size "s" :class (stl/css :attr-arrow)}]
              [:> brl/value-chip* {:value after}]]]))])
     (when (seq linked)
       [:div {:class (stl/css :linked)}
        [:> text* {:as "span" :typography t/body-small :class (stl/css :section-title)}
         (tr "workspace.review.activity.linked-tasks" (i18n/c (count linked)))]
        (for [task linked]
          [:div {:key (dm/str (:id task)) :class (stl/css :linked-task)}
           [:> text* {:as "span" :typography t/body-small} (:title task)]
           [:> task-status* {:status (:status task) :label (rvc/status-label (:status task))}]
           (when (dwtk/lost? task)
             [:> text* {:as "span" :typography t/body-small :class (stl/css :warning)}
              (tr "workspace.review.activity.now-points-to"
                  (rvc/board-name objects (:frame-id task)))])])])
     [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
      (tr "workspace.review.text-and-detail"
          (rvc/profile-name profiles (:profile-id entry))
          (ct/format-inst (:changed-at entry) :localized-time))]
     [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
      (if (seq linked)
        (tr "workspace.review.activity.open-task-to-relink")
        (tr "workspace.review.activity.click-to-show"))]]))

(def ^:private rows-shown
  "The changes a group lists before it folds the rest."
  4)

(mf/defc change-item*
  "One change of a group: a click shows it on the canvas, and hovering it
  shows what it did."
  {::mf/private true}
  [{:keys [entry tasks portal]}]
  (let [id       (:id entry)
        tone     (kind-tone (:kind entry))
        rect*    (mf/use-state nil)
        rect     (deref rect*)

        on-enter (mf/use-fn #(reset! rect* (dom/get-bounding-rect (dom/get-current-target %))))
        on-leave (mf/use-fn #(reset! rect* nil))
        on-show  (mf/use-fn (mf/deps id) #(st/emit! (dwrv/show-change-of id)))]

    [:li {:class (stl/css :change-item)
          :on-pointer-enter on-enter
          :on-pointer-leave on-leave}
     [:> change-row* {:title (or (:name entry) (tr "workspace.review.unknown-name"))
                      :subtitle (:frame-name entry)
                      :tone tone
                      :is-muted (= :deleted (:kind entry))
                      :end (mf/html [:> tag* {:tone tone}
                                     (rvc/change-kind-label (:kind entry))])
                      :on-click on-show}]
     (when (some? rect)
       (mf/portal
        (mf/html [:> change-card* {:entry entry :tasks tasks :rect rect}])
        portal))]))

(mf/defc change-group*
  {::mf/private true}
  [{:keys [session tasks is-open]}]
  (let [profiles  (mf/deref refs/profiles)
        open*     (mf/use-state is-open)
        open?     (deref open*)
        all*      (mf/use-state false)
        all?      (deref all*)
        portal    (hooks/use-portal-container :tooltip)
        entries   (:entries session)
        shown     (if all? entries (take rows-shown entries))
        person    (get profiles (:profile-id session))

        on-toggle (mf/use-fn #(swap! open* not))
        on-all    (mf/use-fn #(reset! all* true))]

    [:div {:class (stl/css :change-group)}
     [:button {:class (stl/css :change-group-head)
               :type "button"
               :aria-expanded open?
               :on-click on-toggle}
      [:> icon* {:icon-id (if open? i/arrow-down i/arrow-right) :size "s"}]
      (when person [:> avatar* {:profile person :variant "S"}])
      [:> text* {:as "span" :typography t/body-small :class (stl/css :strong)}
       (rvc/profile-name profiles (:profile-id session))]
      [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
       (tr "workspace.review.activity.changed" (i18n/c (count entries)))]
      [:> text* {:as "span" :typography t/body-small :class (stl/css :muted :push-end)}
       (ct/format-inst (:end session) :localized-time)]]
     (when open?
       [:ul {:class (stl/css :change-rows)}
        (for [entry shown]
          [:> change-item* {:key (dm/str (:id entry))
                            :entry entry
                            :tasks tasks
                            :portal portal}])
        (when (and (not all?) (> (count entries) rows-shown))
          [:li
           [:> link-button* {:on-click on-all}
            (tr "workspace.review.activity.more" (i18n/c (- (count entries) rows-shown)))]])])]))

(mf/defc day-group*
  "The changes of one day: only the group of its last session opens."
  {::mf/private true}
  [{:keys [sessions tasks]}]
  (let [sessions (mf/with-memo [sessions]
                   (into [] d/xf:add-index sessions))]
    [:section {:class (stl/css :section)}
     [:> heading* {:level 3 :typography t/body-small :class (stl/css :section-title)}
      (day-label (:end (first sessions)))]
     (for [session sessions]
       [:> change-group* {:key (dm/str (inst-ms (:start session)) (:profile-id session))
                          :session session
                          :tasks tasks
                          :is-open (zero? (::d/index session))}])]))

(mf/defc activity-tab*
  {::mf/private true}
  [{:keys [pr activity tasks]}]
  (let [profiles (mf/deref refs/profiles)
        local    (mf/deref refs/review-local)
        kind     (get local :change-filter :all)
        entries  (:entries activity)
        people   (count (into #{} (map :profile-id) (:rows activity)))
        counts   (frequencies (map :kind entries))
        sessions (mf/with-memo [activity kind]
                   (->> (:sessions activity)
                        (keep (fn [s]
                                (let [es (cond->> (:entries s)
                                           (not= :all kind) (filterv #(= kind (:kind %))))]
                                  (when (seq es) (assoc s :entries es)))))))
        by-day   (mf/with-memo [sessions]
                   (partition-by #(day-label (:end %)) sessions))

        on-filter
        (mf/use-fn
         (fn [event]
           (let [kind (-> (dom/get-current-target event) (dom/get-data "filter") keyword)]
             (st/emit! (dwrv/set-change-filter kind)))))

        on-show (mf/use-fn #(st/emit! (dwrv/show-change 0)))]

    (case (:status activity)
      :loaded
      [:div {:class (stl/css :tab-body)}
       [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)}
        (tr "workspace.review.activity.summary" (count entries) people)]
       [:div {:class (stl/css :activity-bar)}
        [:div {:class (stl/css :filters)}
         (for [[id label n] [[:all (tr "workspace.review.activity.all") (count entries)]
                             [:added (tr "workspace.review.change.added") (:added counts 0)]
                             [:modified (tr "workspace.review.change.modified") (:modified counts 0)]
                             [:deleted (tr "workspace.review.change.deleted") (:deleted counts 0)]]]
           [:> filter-chip* {:key (name id)
                             :label (tr "workspace.review.label-count" label n)
                             :tone (case id :added "success" :deleted "error" :modified "info" "neutral")
                             :is-selected (= kind id)
                             :data-filter (name id)
                             :on-click on-filter}])]
        [:> button* {:variant "ghost" :icon i/shown :on-click on-show
                     :disabled (empty? entries)}
         (tr "workspace.review.activity.show-on-canvas")]]

       (if (empty? sessions)
         [:> empty-state* {:icon i/history
                           :text (tr "workspace.review.activity.empty")}]
         (for [day by-day]
           [:> day-group* {:key (day-label (:end (first day)))
                           :sessions day
                           :tasks tasks}]))

       [:div {:class (stl/css :opened-line)}
        [:> icon* {:icon-id i/git-pull-request-arrow :size "s"}]
        [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
         (tr "workspace.review.activity.opened"
             (rvc/profile-name profiles (:created-by pr))
             (ct/format-inst (:created-at pr) :localized-date-time))]]]

      :error
      [:> empty-state* {:icon i/triangle-alert
                        :text (tr "workspace.review.activity.error")}]

      [:div {:class (stl/css :tab-body)}
       [:> text* {:as "p" :typography t/body-small :class (stl/css :muted)}
        (tr "labels.loading")]])))

;; --- Tasks

(mf/defc tasks-tab*
  "The tasks of the review: filters by state, as the activity has by kind
  of change, how many are closed and the list. With every state shown,
  the closed tasks fold under the open ones."
  {::mf/private true}
  [{:keys [tasks]}]
  (let [local        (mf/deref refs/review-local)
        status       (get local :task-status-filter :all)
        closed-open* (mf/use-state false)
        closed-open? (deref closed-open*)
        counts       (frequencies (map :status tasks))
        open-tasks   (remove dwtk/closed? tasks)
        closed-tasks (filter dwtk/closed? tasks)
        shown        (mf/with-memo [tasks status]
                       (filterv #(= status (:status %)) tasks))

        on-filter
        (mf/use-fn
         (fn [event]
           (let [value (-> (dom/get-current-target event) (dom/get-data "filter"))]
             (st/emit! (dwrv/set-task-status-filter
                        (if (= "all" value) :all value))))))

        on-toggle    (mf/use-fn #(swap! closed-open* not))]

    [:div {:class (stl/css :tab-body)}
     [:div {:class (stl/css :tasks-head)}
      [:div {:class (stl/css :filters)}
       [:> filter-chip* {:label (tr "workspace.review.label-count"
                                    (tr "workspace.review.activity.all")
                                    (count tasks))
                         :is-selected (= :all status)
                         :data-filter "all"
                         :on-click on-filter}]
       (for [id dwrv/task-statuses]
         [:> filter-chip* {:key id
                           :label (tr "workspace.review.label-count"
                                      (rvc/status-label id)
                                      (get counts id 0))
                           :tone (rvc/status-tone id)
                           :is-selected (= status id)
                           :data-filter id
                           :on-click on-filter}])]
      [:> text* {:as "span" :typography t/body-small :class (stl/css :strong)}
       (tr "workspace.tasks.closed-of" (count closed-tasks) (count tasks))]]
     [:> progress-bar* {:value (count closed-tasks) :total (count tasks) :class (stl/css :wide-bar)}]

     (cond
       (empty? tasks)
       [:> empty-state* {:icon i/view-as-list :text (tr "workspace.tasks.empty")}]

       (not= :all status)
       (if (empty? shown)
         [:> empty-state* {:icon i/view-as-list :text (tr "workspace.tasks.empty")}]
         [:> rvt/task-list* {:tasks shown}])

       :else
       [:*
        [:> rvt/task-list* {:tasks (vec open-tasks)}]
        (when (seq closed-tasks)
          [:section {:class (stl/css :section)}
           [:> link-button* {:icon (if closed-open? i/arrow-down i/arrow-right)
                             :tone "neutral"
                             :class (stl/css :closed-toggle)
                             :aria-expanded closed-open?
                             :on-click on-toggle}
            (tr "workspace.review.details.closed" (count closed-tasks))]
           (when closed-open?
             [:> rvt/task-list* {:tasks (vec closed-tasks)}])])])]))

;; --- Dialog

(mf/defc review-details-dialog*
  {::mf/register modal/components
   ::mf/register-as :review-details}
  [{:keys [pr tab]}]
  (let [preview  (mf/deref refs/pull-request-preview)
        ;; the review open now is fresher than the row the dialog got
        pr       (if (= (:id pr) (:pr-id preview)) (or (:info preview) pr) pr)
        profile  (mf/deref refs/profile)
        team     (mf/deref refs/team)
        tasks*   (mf/deref refs/tasks)
        activity (mf/deref refs/review-activity)
        tasks    (mf/with-memo [tasks* pr] (review-tasks tasks* (:id pr)))
        tab*     (mf/use-state (or tab "overview"))
        tab      (deref tab*)
        profiles (mf/deref refs/profiles)
        author   (get profiles (:created-by pr))

        reviewer?   (some #(= (:profile-id %) (:id profile)) (:reviews pr))
        can-close?  (and (or (= (:created-by pr) (:id profile))
                             (boolean (get-in team [:permissions :is-admin])))
                         (= "open" (:status pr)))
        entries     (:entries activity)

        tabs (mf/with-memo [entries tasks]
               [{:id "overview" :label (tr "workspace.review.details.overview")}
                {:id "activity" :label (tr "workspace.review.details.activity" (count entries))}
                {:id "tasks" :label (tr "workspace.review.tab.tasks" (count tasks))}])

        ;; kept in the props too: a confirmation takes the place of the dialog
        ;; and brings it back with its props
        on-tab     (mf/use-fn
                    (fn [id]
                      (reset! tab* id)
                      (st/emit! (modal/update-props :review-details {:tab id}))))
        on-close   (mf/use-fn #(st/emit! (modal/hide)))
        on-open-change (mf/use-fn (fn [open?] (when-not open? (st/emit! (modal/hide)))))
        on-compare (mf/use-fn (mf/deps pr) #(prui/open-compare! pr))
        on-review  (mf/use-fn (mf/deps pr) #(modal/show! :pull-request-review {:pr pr}))
        on-close-review (mf/use-fn (mf/deps pr) #(prui/confirm-close! pr))]

    (mf/with-effect [pr]
      (st/emit! (dwrv/fetch-activity (:id pr))
                (dwtk/fetch-tasks)))

    [:> modal* {:is-open true
                :on-open-change on-open-change
                :size "large"}
     [:> dialog-header*
      {:icon i/git-pull-request-arrow
       :title (:title pr)
       :subtitle
       (mf/html
        [:div {:class (stl/css :subtitle)}
         [:> pr-state-badge* {:pr pr}]
         [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
          (tr "workspace.review.text-and-detail"
              (tr "workspace.review.route" (:branch-name pr) (tr "workspace.branches.main"))
              (tr "workspace.review.details.opened-by"
                  (ct/format-inst (:created-at pr) :localized-date)
                  (or (:fullname author) (tr "workspace.review.unknown-name"))))]])}]

     [:> modal-content* {:class (stl/css :content)}
      [:> tab-switcher* {:tabs tabs
                         :selected tab
                         :on-change on-tab
                         :scrollable-panel true
                         :class (stl/css :tabs)}
       (case tab
         "activity" [:> activity-tab* {:pr pr :activity activity :tasks tasks}]
         "tasks"    [:> tasks-tab* {:tasks tasks}]
         [:> overview-tab* {:pr pr :tasks tasks :activity activity}])]]

     [:> modal-footer* {:class (stl/css :footer)}
      (when can-close?
        [:> button* {:variant "ghost"
                     :class (stl/css :push-start)
                     :on-click on-close-review}
         (tr "workspace.review.details.close-review")])
      [:> button* {:variant "secondary" :icon i/git-compare-arrows :on-click on-compare}
       (tr "workspace.branches.compare")]
      (if reviewer?
        [:> button* {:variant "primary" :on-click on-review}
         (tr "workspace.review.submit")]
        [:> button* {:variant "primary" :on-click on-close}
         (tr "labels.close")])]]))
