;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.pull-requests
  "Pull request UI. A pull request is presented as a STATE of its branch
  (one branch → at most one open pull request), so there is no separate
  pull request list: the branch entries in the sidebar surface the review
  state (see `app.main.ui.workspace.sidebar.branches`), and this namespace
  provides the shared pieces — state badge, action helpers, the creation /
  review / details modals and the review-sandbox banner."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.team :as dtm]
   [app.main.data.workspace.pull-requests :as dwpr]
   [app.main.data.workspace.review :as dwrv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item*]]
   [app.main.ui.ds.layout.modal :refer [modal* modal-header* modal-content* modal-footer*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.progress-bar :refer [progress-bar*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Derived display state

(defn- display-state
  "The single most relevant label for a pull request: its stored status
  for finished ones, the aggregate review verdict for open ones."
  [{:keys [status review-state]}]
  (case status
    "merged" :merged
    "closed" :closed
    (case review-state
      "changes-requested" :changes-requested
      "approved"          :approved
      "in-review"         :in-review
      :open)))

(defn- state-label
  [state]
  (case state
    :open              (tr "workspace.pull-requests.state.open")
    :in-review         (tr "workspace.pull-requests.state.in-review")
    :approved          (tr "workspace.pull-requests.state.approved")
    :changes-requested (tr "workspace.pull-requests.state.changes-requested")
    :merged            (tr "workspace.pull-requests.state.merged")
    :closed            (tr "workspace.pull-requests.state.closed")))

(mf/defc pr-state-badge*
  [{:keys [pr]}]
  (let [state (display-state pr)]
    [:span {:class (stl/css-case :pr-badge true
                                 :badge-open (= state :open)
                                 :badge-in-review (= state :in-review)
                                 :badge-approved (= state :approved)
                                 :badge-changes (= state :changes-requested)
                                 :badge-merged (= state :merged)
                                 :badge-closed (= state :closed))}
     (state-label state)]))

(defn current-approvals
  "[current-approvals total-reviewers] of a pull request; stale verdicts
  do not count."
  [{:keys [reviews]}]
  [(count (filter #(and (= "approved" (:state %)) (not (:stale %))) reviews))
   (count reviews)])

(defn- pr->branch
  "A branch-row-shaped map for the pull request's branch, enough for the
  branching compare / merge dialogs (they key on :id and :name; the
  counts feed their action buttons)."
  [pr]
  {:id (:file-branch-id pr)
   :branch-file-id (:source-file-id pr)
   :name (:branch-name pr)
   :status "open"
   :ahead (:ahead pr)
   :behind (:behind pr)
   :conflicts (:conflicts pr)})

(defn open-compare!
  "Open the branching compare dialog on the pull request's branch, so the
  proposed changes can be inspected. Like merging, it diffs the LIVE
  branch against main."
  [pr]
  (modal/show! :branch-compare {:branch (pr->branch pr) :is-review true}))

(defn confirm-merge!
  "Open the branching merge dialog for the pull request's branch:
  accepting a pull request goes through the exact same integration flow
  (confirmation, conflict resolution, safety snapshot) as merging the
  branch directly. Merging always integrates the LIVE branch state, so
  an outdated pull request merges the newest changes, not the snapshot."
  [pr]
  (modal/show! :merge-branch {:branch (pr->branch pr)}))

(defn confirm-close!
  "Ask for confirmation before cancelling a pull request: the review is
  discarded and the branch simply goes back to being a normal branch
  (it is NOT deleted)."
  [pr]
  (st/emit! (modal/show {:type :confirm
                         :title (tr "workspace.pull-requests.close.title")
                         :message (tr "workspace.pull-requests.close.message" (:title pr))
                         :accept-label (tr "workspace.pull-requests.actions.close")
                         :accept-style :danger
                         :on-accept (fn [_] (st/emit! (dwpr/close-pull-request (:id pr))))})))

;; --- The action of a banner

(mf/defc banner-button*
  "A quiet action inside a banner on the canvas, with what it does as
  `children`."
  [{:keys [class children] :rest props}]
  (let [props (mf/spread-props props {:class [class (stl/css :banner-button)]
                                      :type "button"})]
    [:> :button props children]))

;; --- Dialog header

(mf/defc dialog-header*
  "The header the branch and pull request dialogs share: an icon tile,
  the title and a subtitle (a plain string, or any element), then
  whatever the dialog adds after them, such as a status chip. The close
  button belongs to `modal*`."
  [{:keys [icon tone title subtitle children]}]
  [:> modal-header* {:class (stl/css :dialog-header)}
   (when icon
     [:div {:class (stl/css-case :dialog-header-icon true
                                 :is-warning (= tone :warning))}
      [:> i/icon* {:icon-id icon}]])
   [:div {:class (stl/css :dialog-header-text)}
    [:> heading* {:level 2
                  :typography t/headline-medium
                  :class (stl/css :dialog-title)}
     title]
    (if (string? subtitle)
      [:> text* {:as "span"
                 :typography t/body-small
                 :class (stl/css :dialog-subtitle)}
       subtitle]
      subtitle)]
   children])

(defn on-dismiss
  "The `on-open-change` of a dialog that `modal*` closes (Escape, the
  backdrop or its close button): run `on-close` when it closes."
  [on-close]
  (fn [open?]
    (when-not open?
      (on-close))))

;; --- Create pull request dialog (modal)

(mf/defc create-pull-request-dialog*
  {::mf/register modal/components
   ::mf/register-as :create-pull-request}
  [{:keys [branch]}]
  (let [profile     (mf/deref refs/profile)
        team        (mf/deref refs/team)
        members     (->> (get team :members)
                         (filter :is-active)
                         (remove #(= (:id %) (:id profile))))

        title*      (mf/use-state (or (:name branch) ""))
        title       (deref title*)
        description* (mf/use-state "")
        description (deref description*)
        selected*   (mf/use-state #{})
        selected    (deref selected*)
        valid?      (not (str/blank? title))

        on-title-change
        (mf/use-fn #(reset! title* (dom/get-target-val %)))

        on-description-change
        (mf/use-fn #(reset! description* (dom/get-target-val %)))

        on-toggle-reviewer
        (mf/use-fn
         (fn [event]
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "reviewer-id")
                        (uuid/parse*))]
             (swap! selected* #(if (contains? % id) (disj % id) (conj % id))))))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-submit
        (mf/use-fn
         (mf/deps branch title description selected valid?)
         (fn [_]
           (when valid?
             (st/emit! (dwpr/create-pull-request
                        (:id branch)
                        {:title (str/trim title)
                         :description (str/trim description)
                         :reviewers (vec selected)})
                       (modal/hide)))))]

    ;; the reviewer picker needs the team members loaded
    (mf/with-effect []
      (st/emit! (dtm/fetch-members)))

    [:> modal* {:is-open true
                :on-open-change (on-dismiss on-close)
                :size "large"}
     [:> dialog-header* {:icon i/git-pull-request-create-arrow
                         :title (tr "workspace.pull-requests.create.title")
                         :subtitle (tr "workspace.pull-requests.create.subtitle" (or (:name branch) ""))}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:> input* {:label (tr "workspace.pull-requests.create.name-label")
                  :icon i/git-pull-request-arrow
                  :placeholder (tr "workspace.pull-requests.create.name-placeholder")
                  :value title
                  :on-change on-title-change}]

      [:div {:class (stl/css :field)}
       [:label {:class (stl/css :field-label)}
        (tr "workspace.pull-requests.create.description-label")]
       [:textarea {:class (stl/css :textarea)
                   :value description
                   :placeholder (tr "workspace.pull-requests.create.description-placeholder")
                   :on-change on-description-change
                   :rows 3}]]

      [:div {:class (stl/css :field)}
       [:label {:class (stl/css :field-label)}
        (tr "workspace.pull-requests.create.reviewers-label")]
       (if (empty? members)
         [:span {:class (stl/css :reviewers-empty)}
          (tr "workspace.pull-requests.create.no-members")]
         [:ul {:class (stl/css :reviewers-list)}
          (for [member members]
            [:li {:key (dm/str (:id member))}
             [:button {:class (stl/css-case :reviewer-option true
                                            :is-selected (contains? selected (:id member)))
                       :data-reviewer-id (dm/str (:id member))
                       :on-click on-toggle-reviewer}
              [:> avatar* {:profile member :variant "S"}]
              [:span {:class (stl/css :reviewer-name)} (:fullname member)]
              (when (contains? selected (:id member))
                [:> i/icon* {:icon-id i/tick :size "s" :class (stl/css :reviewer-tick)}])]])])]

      [:> context-notification* {:level :info :type :context}
       (tr "workspace.pull-requests.create.info")]]

     [:> modal-footer* {:class (stl/css :dialog-footer)}
      [:> button* {:class (stl/css :dialog-action)
                   :variant "secondary" :on-click on-close}
       (tr "labels.cancel")]
      [:> button* {:class (stl/css :dialog-action)
                   :variant "primary"
                   :icon i/git-pull-request-create-arrow
                   :disabled (not valid?)
                   :on-click on-submit}
       (tr "workspace.pull-requests.create.submit")]]]))

;; --- Review dialog (approve / request changes)

(mf/defc pull-request-review-dialog*
  {::mf/register modal/components
   ::mf/register-as :pull-request-review}
  [{:keys [pr]}]
  (let [verdict*  (mf/use-state "approved")
        verdict   (deref verdict*)
        comment*  (mf/use-state "")
        comment   (deref comment*)

        on-comment-change
        (mf/use-fn #(reset! comment* (dom/get-target-val %)))

        on-select-verdict
        (mf/use-fn
         (fn [event]
           (reset! verdict* (-> (dom/get-current-target event)
                                (dom/get-data "verdict")))))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-submit
        (mf/use-fn
         (mf/deps pr verdict comment)
         (fn [_]
           (st/emit! (dwpr/submit-review (:id pr) verdict
                                         (let [c (str/trim comment)]
                                           (when-not (str/blank? c) c)))
                     (modal/hide))))]

    [:> modal* {:is-open true
                :on-open-change (on-dismiss on-close)
                :size "large"}
     [:> dialog-header* {:icon i/git-pull-request-arrow
                         :title (tr "workspace.pull-requests.review.title")
                         :subtitle (:title pr)}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:div {:class (stl/css :verdict-options)}
       [:button {:class (stl/css-case :verdict-option true
                                      :is-selected (= verdict "approved"))
                 :data-verdict "approved"
                 :on-click on-select-verdict}
        [:> i/icon* {:icon-id i/tick}]
        [:div {:class (stl/css :verdict-option-text)}
         [:span {:class (stl/css :verdict-option-title)}
          (tr "workspace.pull-requests.review.approve")]
         [:span {:class (stl/css :verdict-option-hint)}
          (tr "workspace.pull-requests.review.approve-hint")]]]

       [:button {:class (stl/css-case :verdict-option true
                                      :is-selected (= verdict "changes-requested"))
                 :data-verdict "changes-requested"
                 :on-click on-select-verdict}
        [:> i/icon* {:icon-id i/triangle-alert}]
        [:div {:class (stl/css :verdict-option-text)}
         [:span {:class (stl/css :verdict-option-title)}
          (tr "workspace.pull-requests.review.request-changes")]
         [:span {:class (stl/css :verdict-option-hint)}
          (tr "workspace.pull-requests.review.request-changes-hint")]]]]

      [:div {:class (stl/css :field)}
       [:label {:class (stl/css :field-label)}
        (tr "workspace.pull-requests.review.comment-label")]
       [:textarea {:class (stl/css :textarea)
                   :value comment
                   :placeholder (tr "workspace.pull-requests.review.comment-placeholder")
                   :on-change on-comment-change
                   :rows 3}]]]

     [:> modal-footer* {:class (stl/css :dialog-footer)}
      [:> button* {:class (stl/css :dialog-action)
                   :variant "secondary" :on-click on-close}
       (tr "labels.cancel")]
      [:> button* {:class (stl/css :dialog-action)
                   :variant "primary"
                   :on-click on-submit}
       (tr "workspace.pull-requests.review.submit")]]]))

;; --- Details dialog

(mf/defc pull-request-info-dialog*
  {::mf/register modal/components
   ::mf/register-as :pull-request-info}
  [{:keys [pr]}]
  (let [profiles  (mf/deref refs/profiles)
        profile   (mf/deref refs/profile)
        team      (mf/deref refs/team)
        author    (get profiles (:created-by pr))
        author?   (= (:created-by pr) (:id profile))
        admin?    (boolean (get-in team [:permissions :is-admin]))
        can-merge? (boolean (get-in team [:permissions :can-edit]))
        open?     (= "open" (:status pr))
        closed?   (= "closed" (:status pr))
        ;; already inside this pull request's review sandbox: offering to
        ;; open it again would be a no-op
        reviewing? (= (:id pr)
                      (:pr-id (mf/deref refs/pull-request-preview)))
        [approvals total] (current-approvals pr)

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-merge
        (mf/use-fn
         (mf/deps pr)
         (fn [_]
           (st/emit! (modal/hide))
           (confirm-merge! pr)))

        on-open-review
        (mf/use-fn
         (mf/deps pr)
         (fn [_]
           (st/emit! (modal/hide)
                     (dwpr/open-pull-request pr))))

        on-close-pr
        (mf/use-fn
         (mf/deps pr)
         (fn [_]
           ;; the confirmation dialog replaces this modal
           (confirm-close! pr)))

        on-reopen-pr
        (mf/use-fn
         (mf/deps pr)
         (fn [_]
           (st/emit! (modal/hide)
                     (dwpr/reopen-pull-request (:id pr)))))]

    [:> modal* {:is-open true
                :on-open-change (on-dismiss on-close)
                :size "large"}
     [:> dialog-header*
      {:icon i/git-pull-request-arrow
       :title (:title pr)
       :subtitle
       (mf/html
        [:div {:class (stl/css :dialog-subtitle-row)}
         [:> pr-state-badge* {:pr pr}]
         [:> text* {:as "span"
                    :typography t/body-small
                    :class (stl/css :dialog-subtitle)}
          ;; the route reads branch → target BRANCH (reviews always merge
          ;; into main); the target file name would be redundant here,
          ;; inside the file itself
          (tr "workspace.review.route" (:branch-name pr) (tr "workspace.branches.main"))]
         (when (and open? (:outdated pr))
           [:span {:class (stl/css :pr-badge :badge-outdated)}
            (tr "workspace.pull-requests.badge.outdated")])
         (when (and open? (pos? (or (:conflicts pr) 0)))
           [:span {:class (stl/css :pr-badge :badge-conflicts)}
            (tr "workspace.pull-requests.badge.conflicts" (dm/str (:conflicts pr)))])])}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:div {:class (stl/css :pr-info-card)}
       [:div {:class (stl/css :pr-info-grid)}
        [:div {:class (stl/css :pr-info-item)}
         [:span {:class (stl/css :field-label)} (tr "workspace.pull-requests.info.author")]
         [:div {:class (stl/css :pr-info-value)}
          (when author [:> avatar* {:profile author :variant "S"}])
          [:span (get author :fullname "—")]]]

        [:div {:class (stl/css :pr-info-item)}
         [:span {:class (stl/css :field-label)} (tr "workspace.pull-requests.info.created-at")]
         [:div {:class (stl/css :pr-info-value)}
          [:span (ct/format-inst (:created-at pr) :localized-date-time)]]]

        [:div {:class (stl/css :pr-info-item)}
         [:span {:class (stl/css :field-label)} (tr "workspace.pull-requests.info.updated-at")]
         [:div {:class (stl/css :pr-info-value)}
          [:span (ct/format-inst (:review-updated-at pr) :localized-date-time)]]]

        [:div {:class (stl/css :pr-info-item)}
         [:span {:class (stl/css :field-label)} (tr "workspace.pull-requests.info.approvals")]
         [:div {:class (stl/css :pr-info-value)}
          [:span (dm/str approvals "/" total)]]]]

       (when (pos? total)
         [:div {:class (stl/css :field)}
          [:label {:class (stl/css :field-label)}
           (tr "workspace.pull-requests.info.reviewers")]
          [:ul {:class (stl/css :pr-info-reviewers)}
           (for [review (:reviews pr)]
             (let [reviewer (get profiles (:profile-id review))]
               [:li {:key (dm/str (:profile-id review))
                     :class (stl/css :pr-info-reviewer)}
                (when reviewer [:> avatar* {:profile reviewer :variant "S"}])
                [:span {:class (stl/css :reviewer-name)}
                 (get reviewer :fullname "—")]
                [:span {:class (stl/css-case :reviewer-state true
                                             :reviewer-approved (and (= "approved" (:state review))
                                                                     (not (:stale review)))
                                             :reviewer-changes (and (= "changes-requested" (:state review))
                                                                    (not (:stale review)))
                                             :reviewer-stale (:stale review))}
                 (cond
                   (:stale review)
                   (tr "workspace.pull-requests.review-state.stale")

                   (= "approved" (:state review))
                   (tr "workspace.pull-requests.review-state.approved")

                   (= "changes-requested" (:state review))
                   (tr "workspace.pull-requests.review-state.changes-requested")

                   :else
                   (tr "workspace.pull-requests.review-state.pending"))]
                (when-not (str/blank? (:comment review))
                  [:> text* {:as "p" :typography t/body-small :class (stl/css :reviewer-comment)}
                   (:comment review)])]))]])]

      (when-not (str/blank? (:description pr))
        [:div {:class (stl/css :pr-info-description-block)}
         [:label {:class (stl/css :field-label)}
          (tr "workspace.pull-requests.info.description")]
         [:> text* {:as "p" :typography t/body-medium :class (stl/css :pr-info-description)}
          (:description pr)]])]

     [:> modal-footer* {:class (stl/css :dialog-footer)}
      (when (and (or author? admin?) open?)
        [:> button* {:class (stl/css :dialog-action)
                     :variant "secondary"
                     :on-click on-close-pr}
         (tr "workspace.pull-requests.actions.close")])
      (when (and (or author? admin?) closed?)
        [:> button* {:class (stl/css :dialog-action)
                     :variant "secondary"
                     :on-click on-reopen-pr}
         (tr "workspace.pull-requests.actions.reopen")])
      (when (and open? (not reviewing?))
        [:> button* {:class (stl/css :dialog-action)
                     :variant "primary"
                     :icon i/arrow-up-right
                     :on-click on-open-review}
         (tr "workspace.pull-requests.actions.open-review")])
      (when (and open? can-merge?)
        [:> button* {:class (stl/css :dialog-action)
                     :variant "primary"
                     :icon i/git-merge
                     :on-click on-merge}
         (tr "workspace.pull-requests.actions.merge")])]]))

;; --- Review bar (replaces the branch banner inside a design review)

(mf/defc pr-review-banner*
  "The bar at the bottom of the canvas inside a design review: the review
  being reviewed, the progress of its tasks, its details, the verdict of
  the reviewer and the way out. The review is read-only; merging and
  cancelling the review sit behind the menu.

  When the branch moved past what the review shows, its author gets the
  button that publishes the latest changes; everybody else gets a badge
  that says the branch has changes they cannot see yet."
  []
  (let [preview    (mf/deref refs/pull-request-preview)
        pr         (:info preview)
        profile    (mf/deref refs/profile)
        team       (mf/deref refs/team)
        tasks*     (mf/deref refs/tasks)
        author?    (= (:created-by pr) (:id profile))
        reviewer?  (some #(= (:profile-id %) (:id profile)) (:reviews pr))
        can-merge? (boolean (get-in team [:permissions :can-edit]))
        can-cancel? (and (or author? (boolean (get-in team [:permissions :is-admin])))
                         (= "open" (:status pr)))
        outdated?  (boolean (:outdated pr))

        tasks      (mf/with-memo [tasks* pr]
                     (filterv #(= (:id pr) (:pull-request-id %)) (vals tasks*)))
        closed     (count (filter #(contains? #{"verified" "wont-do"} (:status %)) tasks))
        total      (count tasks)

        menu-open*     (mf/use-state false)
        menu-open?     (deref menu-open*)
        on-toggle-menu (mf/use-fn #(swap! menu-open* not))
        on-menu-change (mf/use-fn #(reset! menu-open* %))

        on-details
        (mf/use-fn #(st/emit! (dwrv/open-details "overview")))

        on-tasks
        (mf/use-fn #(st/emit! (dwrv/set-panel-tab :tasks)))

        on-review
        (mf/use-fn (mf/deps pr) #(modal/show! :pull-request-review {:pr pr}))

        on-exit
        (mf/use-fn #(st/emit! (dwpr/exit-pull-request-preview)))

        on-publish
        (mf/use-fn
         (mf/deps pr)
         #(st/emit! (dwpr/update-pull-request-snapshot (:id pr))))

        on-menu-action
        (mf/use-fn
         (mf/deps pr)
         (fn [action]
           (reset! menu-open* false)
           (case action
             "compare" (open-compare! pr)
             "merge"   (confirm-merge! pr)
             "cancel"  (confirm-close! pr)
             nil)))]

    (when (some? pr)
      [:div {:class (stl/css :pr-banner)
             :role "region"
             :aria-label (tr "workspace.review.bar.label")}
       [:div {:class (stl/css :pr-banner-content)}
        [:div {:class (stl/css :pr-banner-icon)}
         [:> i/icon* {:icon-id i/git-pull-request-arrow}]]
        [:div {:class (stl/css :pr-banner-text)}
         [:span {:class (stl/css :pr-banner-title)}
          (tr "workspace.pull-requests.banner.reviewing")]
         [:span {:class (stl/css :pr-banner-name)} (:title pr)]
         ;; the author publishes them with the button instead
         (when (and outdated? (not author?))
           [:> tooltip* {:content (tr "workspace.pull-requests.badge.outdated-hint")
                         :placement "top"}
            [:span {:class (stl/css :pr-badge :badge-outdated)}
             (tr "workspace.pull-requests.badge.outdated")]])]

        [:div {:class (stl/css :pr-banner-separator)}]

        ;; the progress of the tasks shows once the review has any
        (when (pos? total)
          [:*
           [:> banner-button* {:on-click on-tasks}
            [:> i/icon* {:icon-id i/view-as-list :size "s"}]
            [:span (tr "workspace.review.bar.tasks" closed total)]
            [:> progress-bar* {:value closed
                               :total total
                               :class (stl/css :pr-banner-progress)
                               :aria-hidden true}]]

           [:div {:class (stl/css :pr-banner-separator)}]])

        [:div {:class (stl/css :pr-banner-actions)}
         [:> button* {:variant "secondary" :on-click on-details}
          (tr "workspace.review.bar.details")]
         (when (and outdated? author?)
           [:> tooltip* {:content (tr "workspace.pull-requests.banner.publish-hint")
                         :placement "top"}
            [:> button* {:variant "primary" :on-click on-publish}
             (tr "workspace.pull-requests.banner.publish")]])
         (when reviewer?
           [:> button* {:variant "primary" :on-click on-review}
            (tr "workspace.review.submit")])
         [:> button* {:variant "ghost" :on-click on-exit}
          (tr "workspace.pull-requests.banner.exit")]

         ;; the bar sits at the bottom of the canvas: its menu opens upwards
         [:div {:class (stl/css :pr-banner-menu)}
          [:> menu* {:is-open menu-open?
                     :on-open-change on-menu-change
                     :on-action on-menu-action
                     :placement "top end"
                     :trigger
                     (mf/html
                      [:> icon-button* {:variant "ghost"
                                        :icon i/menu
                                        :aria-label (tr "labels.options")
                                        :aria-pressed menu-open?
                                        :on-click on-toggle-menu}])}
           [:> menu-item* {:id "compare"}
            (tr "workspace.branches.compare")]
           (when can-merge?
             [:> menu-item* {:id "merge"}
              (tr "workspace.pull-requests.actions.merge")])
           (when can-cancel?
             [:> menu-item* {:id "cancel"}
              (tr "workspace.pull-requests.banner.cancel")])]]]]])))
