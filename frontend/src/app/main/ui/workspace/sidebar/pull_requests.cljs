;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
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

(def ^:private state->label
  {:open              "workspace.pull-requests.state.open"
   :in-review         "workspace.pull-requests.state.in-review"
   :approved          "workspace.pull-requests.state.approved"
   :changes-requested "workspace.pull-requests.state.changes-requested"
   :merged            "workspace.pull-requests.state.merged"
   :closed            "workspace.pull-requests.state.closed"})

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
     (tr (get state->label state))]))

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
  (modal/show! :branch-compare {:branch (pr->branch pr)}))

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

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-header-icon)}
        [:> i/icon* {:icon-id i/git-pull-request-arrow}]]
       [:div {:class (stl/css :modal-header-text)}
        [:h2 {:class (stl/css :modal-title)} (tr "workspace.pull-requests.create.title")]
        [:span {:class (stl/css :modal-subtitle)}
         (tr "workspace.pull-requests.create.subtitle" (or (:name branch) ""))]]
       [:> button* {:variant "ghost"
                    :icon i/close
                    :aria-label (tr "labels.close")
                    :on-click on-close}]]

      [:div {:class (stl/css :modal-content)}
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
                 [:> i/icon* {:icon-id i/tick :size "s"}])]])])]

       [:> context-notification* {:level :info :type :context}
        (tr "workspace.pull-requests.create.info")]]

      [:div {:class (stl/css :modal-footer)}
       [:> button* {:variant "secondary" :on-click on-close}
        (tr "labels.cancel")]
       [:> button* {:variant "primary"
                    :icon i/git-pull-request-arrow
                    :disabled (not valid?)
                    :on-click on-submit}
        (tr "workspace.pull-requests.create.submit")]]]]))

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

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-header-icon)}
        [:> i/icon* {:icon-id i/git-pull-request-arrow}]]
       [:div {:class (stl/css :modal-header-text)}
        [:h2 {:class (stl/css :modal-title)} (tr "workspace.pull-requests.review.title")]
        [:span {:class (stl/css :modal-subtitle)} (:title pr)]]
       [:> button* {:variant "ghost"
                    :icon i/close
                    :aria-label (tr "labels.close")
                    :on-click on-close}]]

      [:div {:class (stl/css :modal-content)}
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

      [:div {:class (stl/css :modal-footer)}
       [:> button* {:variant "secondary" :on-click on-close}
        (tr "labels.cancel")]
       [:> button* {:variant "primary"
                    :on-click on-submit}
        (tr "workspace.pull-requests.review.submit")]]]]))

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

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-header-icon)}
        [:> i/icon* {:icon-id i/git-pull-request-arrow}]]
       [:div {:class (stl/css :modal-header-text)}
        [:h2 {:class (stl/css :modal-title)} (:title pr)]
        [:div {:class (stl/css :modal-subtitle-row)}
         [:> pr-state-badge* {:pr pr}]
         [:span {:class (stl/css :modal-subtitle)}
          ;; the route reads branch → target BRANCH (reviews always merge
          ;; into main); the target file name would be redundant here,
          ;; inside the file itself
          (dm/str (:branch-name pr) " → " (tr "workspace.branches.main"))]
         (when (and open? (:outdated pr))
           [:span {:class (stl/css :pr-badge :badge-outdated)}
            (tr "workspace.pull-requests.badge.outdated")])
         (when (and open? (pos? (or (:conflicts pr) 0)))
           [:span {:class (stl/css :pr-badge :badge-conflicts)}
            (tr "workspace.pull-requests.badge.conflicts" (dm/str (:conflicts pr)))])]]
       [:> button* {:variant "ghost"
                    :icon i/close
                    :aria-label (tr "labels.close")
                    :on-click on-close}]]

      [:div {:class (stl/css :modal-content)}
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
                   [:p {:class (stl/css :reviewer-comment)} (:comment review)])]))]])]

       (when-not (str/blank? (:description pr))
         [:div {:class (stl/css :pr-info-description-block)}
          [:label {:class (stl/css :field-label)}
           (tr "workspace.pull-requests.info.description")]
          [:p {:class (stl/css :pr-info-description)} (:description pr)]])]

      [:div {:class (stl/css :modal-footer)}
       (when (and (or author? admin?) open?)
         [:> button* {:variant "secondary"
                      :on-click on-close-pr}
          (tr "workspace.pull-requests.actions.close")])
       (when (and (or author? admin?) closed?)
         [:> button* {:variant "secondary"
                      :on-click on-reopen-pr}
          (tr "workspace.pull-requests.actions.reopen")])
       (when (and open? (not reviewing?))
         [:> button* {:variant "primary"
                      :icon i/arrow-up-right
                      :on-click on-open-review}
          (tr "workspace.pull-requests.actions.open-review")])
       (when (and open? can-merge?)
         [:> button* {:variant "primary"
                      :icon i/git-merge
                      :on-click on-merge}
          (tr "workspace.pull-requests.actions.merge")])]]]))

;; --- Review sandbox banner (replaces the branch banner inside a sandbox)

(mf/defc pr-review-banner*
  []
  (let [preview  (mf/deref refs/pull-request-preview)
        pr       (:info preview)
        profile  (mf/deref refs/profile)
        team     (mf/deref refs/team)
        author?  (= (:created-by pr) (:id profile))
        reviewer? (some #(= (:profile-id %) (:id profile)) (:reviews pr))
        ;; merging keeps requiring edition permissions on main: the
        ;; sandbox file permissions are stripped, but the team role tells
        ;; whether this user could integrate the branch
        can-merge? (boolean (get-in team [:permissions :can-edit]))
        [approvals total] (current-approvals pr)

        collapsed*  (mf/use-state false)
        collapsed?  (deref collapsed*)
        on-toggle   (mf/use-fn #(swap! collapsed* not))

        ;; secondary actions live behind the kebab menu so the banner
        ;; only exposes the essential ones (details / compare / review)
        menu-open*    (mf/use-state false)
        menu-open?    (deref menu-open*)
        on-open-menu  (mf/use-fn
                       (fn [event]
                         ;; the dropdown closes itself on any outside
                         ;; click: the opening one must not reach it
                         (dom/stop-propagation event)
                         (reset! menu-open* true)))
        on-close-menu (mf/use-fn #(reset! menu-open* false))

        on-info
        (mf/use-fn (mf/deps pr) #(modal/show! :pull-request-info {:pr pr}))

        on-compare
        (mf/use-fn (mf/deps pr) #(open-compare! pr))

        on-review
        (mf/use-fn (mf/deps pr) #(modal/show! :pull-request-review {:pr pr}))

        on-exit
        (mf/use-fn
         (fn [_]
           (reset! menu-open* false)
           (st/emit! (dwpr/exit-pull-request-preview))))

        on-merge
        (mf/use-fn
         (mf/deps pr)
         (fn [_]
           (reset! menu-open* false)
           (confirm-merge! pr)))

        on-play
        (mf/use-fn
         (mf/deps pr)
         (fn [_]
           (reset! menu-open* false)
           (st/emit! (dwpr/open-pull-request-viewer pr))))

        on-publish
        (mf/use-fn
         (mf/deps pr)
         (fn [_]
           (reset! menu-open* false)
           (st/emit! (dwpr/update-pull-request-snapshot (:id pr))
                     (dwpr/refresh-pull-request-preview-info (:id pr)))))]

    (when (some? pr)
      [:div {:class (stl/css-case :pr-banner true
                                  :is-collapsed collapsed?)}
       [:button {:class (stl/css :pr-banner-handle)
                 :title (tr (if collapsed?
                              "workspace.branches.banner.expand"
                              "workspace.branches.banner.collapse"))
                 :aria-label (tr (if collapsed?
                                   "workspace.branches.banner.expand"
                                   "workspace.branches.banner.collapse"))
                 :on-click on-toggle}
        [:div {:class (stl/css :pr-banner-handle-btn)}]]
       [:div {:class (stl/css :pr-banner-content)}
        [:div {:class (stl/css :pr-banner-icon)}
         [:> i/icon* {:icon-id i/git-pull-request-arrow}]]
        [:div {:class (stl/css :pr-banner-text)}
         [:span {:class (stl/css :pr-banner-title)}
          (tr "workspace.pull-requests.banner.reviewing")]
         [:span {:class (stl/css :pr-banner-name)} (:title pr)]
         [:span {:class (stl/css :pr-banner-route)}
          (dm/str (:branch-name pr) " → " (tr "workspace.branches.main"))]]

        [:div {:class (stl/css :pr-banner-badges)}
         [:> pr-state-badge* {:pr pr}]
         (when (:outdated pr)
           [:span {:class (stl/css :pr-badge :badge-outdated)}
            (tr "workspace.pull-requests.badge.outdated")])
         (when (pos? (or (:conflicts pr) 0))
           [:span {:class (stl/css :pr-badge :badge-conflicts)}
            (tr "workspace.pull-requests.badge.conflicts" (dm/str (:conflicts pr)))])
         (when (pos? total)
           [:span {:class (stl/css :pr-banner-approvals)}
            [:> i/icon* {:icon-id i/tick :size "s"}]
            (dm/str approvals "/" total)])]

        [:div {:class (stl/css :pr-banner-actions)}
         [:> button* {:variant "secondary"
                      :on-click on-info}
          (tr "workspace.pull-requests.menu.info")]

         [:> button* {:variant "secondary"
                      :icon i/switch
                      :on-click on-compare}
          (tr "workspace.branches.compare")]

         (when reviewer?
           [:> button* {:variant "primary"
                        :icon i/tick
                        :on-click on-review}
            (tr "workspace.pull-requests.banner.review")])

         [:div {:class (stl/css :pr-banner-menu)}
          [:> icon-button* {:variant "ghost"
                            :icon i/menu
                            :aria-label (tr "labels.options")
                            :on-click on-open-menu}]
          [:> dropdown-menu* {:show menu-open?
                              :on-close on-close-menu
                              :class (stl/css :pr-banner-options-dropdown)}
           [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-play}
            (tr "workspace.pull-requests.banner.play")]
           (when (and author? (:outdated pr))
             [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-publish}
              (tr "workspace.pull-requests.banner.publish")])
           (when can-merge?
             [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-merge}
              (tr "workspace.pull-requests.actions.merge")])
           [:> dropdown-menu-item* {:class (stl/css :menu-option) :on-click on-exit}
            (tr "workspace.pull-requests.banner.exit")]]]]]])))
