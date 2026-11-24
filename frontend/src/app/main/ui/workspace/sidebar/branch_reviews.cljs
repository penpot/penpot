;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.branch-reviews
  "Branch review UI. A branch review is presented as a STATE of its
  branch (one branch → at most one open review), so there is no separate
  review list: the branch entries in the sidebar surface the review
  state (see `app.main.ui.workspace.sidebar.branches`), and this namespace
  provides the shared pieces — state badge, action helpers, the creation /
  verdict / details modals and the review bar."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.team :as dtm]
   [app.main.data.workspace.branch-reviews :as dwbr]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.data.workspace.review :as dwrv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.textarea :refer [textarea*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item*]]
   [app.main.ui.ds.layout.modal :refer [modal* modal-header* modal-content* modal-footer*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.workspace.compare.common :as cc]
   [app.main.ui.workspace.review.common :as rvc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Derived display state

(defn verdict-stale?
  "True when the verdict of `review` was given on an earlier revision of
  the branch of `pr`. While that branch is the open file, `file` decides
  at once: it is the `:id` and the saved `:revn` of the open file
  (`app.main.refs/open-file-revision`), and
  `app.main.data.persistence/update-file-revn` raises that `:revn` after
  each save of the file, its own or a peer's. The verdict is then stale
  once the revision passes its `:verdict-revn`. Otherwise the `:stale`
  the server computed stands."
  [pr file review]
  (let [revn         (:revn file)
        verdict-revn (:verdict-revn review)]
    (if (and (some? (:id file))
             (= (:id file) (:source-file-id pr))
             (int? revn)
             (int? verdict-revn))
      (> revn verdict-revn)
      (boolean (:stale review)))))

(defn- overall-verdict
  "The aggregate verdict of `reviews`, as the server computes it:
  changes requested beats approved, a stale verdict only counts as in
  review, and no verdict at all is pending."
  [reviews]
  (let [submitted (filter #(contains? #{"approved" "changes-requested"} (:verdict %)) reviews)
        current   (remove :stale submitted)]
    (cond
      (some #(= "changes-requested" (:verdict %)) current) "changes-requested"
      (some #(= "approved" (:verdict %)) current)          "approved"
      (seq submitted)                                      "in-review"
      :else                                                "pending")))

(defn live-verdicts
  "`pr` with the `:stale` of each of its reviews (`verdict-stale?`) and its
  `:overall-verdict` brought up to the open file `file`. A review of
  another branch than the open file stays as the server sent it."
  [pr file]
  (if (and (some? pr)
           (some? (:id file))
           (= (:id file) (:source-file-id pr)))
    (let [reviews (mapv #(assoc % :stale (verdict-stale? pr file %)) (:reviews pr))]
      (assoc pr
             :reviews reviews
             :overall-verdict (overall-verdict reviews)))
    pr))

(defn use-live-verdicts
  "`live-verdicts` of `pr` against the open file, followed save by save."
  [pr]
  (let [file (mf/deref refs/open-file-revision)]
    (mf/with-memo [pr file]
      (live-verdicts pr file))))

(defn- display-state
  "The single most relevant label for a branch review: its stored status
  for finished ones, the aggregate review verdict for open ones."
  [{:keys [status overall-verdict]}]
  (case status
    "merged" :merged
    "closed" :closed
    (case overall-verdict
      "changes-requested" :changes-requested
      "approved"          :approved
      "in-review"         :in-review
      :open)))

(defn- state-label
  [state]
  (case state
    :open              (tr "workspace.branch-review.state.open")
    :in-review         (tr "workspace.branch-review.state.in-review")
    :approved          (tr "workspace.branch-review.state.approved")
    :changes-requested (tr "workspace.branch-review.state.changes-requested")
    :merged            (tr "workspace.branch-review.state.merged")
    :closed            (tr "workspace.branch-review.state.closed")))

(mf/defc review-state-badge*
  [{:keys [pr]}]
  (let [state (display-state (use-live-verdicts pr))]
    [:span {:class (stl/css-case :pr-badge true
                                 :badge-open (= state :open)
                                 :badge-in-review (= state :in-review)
                                 :badge-approved (= state :approved)
                                 :badge-changes (= state :changes-requested)
                                 :badge-merged (= state :merged)
                                 :badge-closed (= state :closed))}
     (state-label state)]))

(defn current-approvals
  "[current-approvals total-reviewers] of a branch review; stale verdicts
  do not count."
  [{:keys [reviews]}]
  [(count (filter #(and (= "approved" (:verdict %)) (not (:stale %))) reviews))
   (count reviews)])

(defn verdict-label
  "What the row of a reviewer says: the verdict, or that it was given on
  an earlier version when the branch changed after it."
  [{:keys [verdict stale]}]
  (case verdict
    "approved"
    (if stale
      (tr "workspace.branch-review.review-state.stale-approved")
      (tr "workspace.branch-review.review-state.approved"))

    "changes-requested"
    (if stale
      (tr "workspace.branch-review.review-state.stale-changes-requested")
      (tr "workspace.branch-review.state.changes-requested"))

    (tr "workspace.branch-review.review-state.pending")))

(defn- pr->branch
  "A branch-row-shaped map for the branch review's branch, enough for the
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
  "Compare the branch review's branch with main on the canvas, so the
  proposed changes can be inspected. Like merging, it diffs the LIVE
  branch against main. The branch is the open file inside its review."
  [pr]
  (st/emit! (dwcp/enter (pr->branch pr))))

(defn confirm-merge!
  "Open the branching merge dialog for the branch review's branch:
  accepting a branch review goes through the exact same integration flow
  (confirmation, conflict resolution, safety snapshot) as merging the
  branch directly. Merging integrates the live branch, the same state
  the reviewers see."
  [pr]
  (modal/show! :merge-branch {:branch (pr->branch pr)}))

(defn confirm-close!
  "Ask for confirmation before closing a branch review: the review ends
  and the branch simply goes back to being a normal branch (it is NOT
  deleted)."
  [pr]
  (st/emit! (modal/show {:type :confirm
                         :title (tr "workspace.branch-review.close.title")
                         :message (tr "workspace.branch-review.close.message" (:title pr))
                         :accept-label (tr "workspace.branch-review.actions.close")
                         :accept-style :danger
                         :on-accept (fn [_] (st/emit! (dwbr/close-branch-review (:id pr))))})))

;; --- The action of a banner

(mf/defc banner-button*
  "A quiet action inside a banner on the canvas, with what it does as
  `children`. Not the DS button: its ghost variant is uppercase and
  turns the accent color on hover, too loud for a bar over the canvas,
  and the DS has no quieter one yet."
  [{:keys [class children] :rest props}]
  (let [props (mf/spread-props props {:class [class (stl/css :banner-button)]
                                      :type "button"})]
    [:> :button props children]))

;; --- Dialog header

(mf/defc dialog-header*
  "The header the branch and branch review dialogs share: an icon tile,
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

;; --- Create branch review dialog (modal)

(mf/defc create-branch-review-dialog*
  {::mf/register modal/components
   ::mf/register-as :create-branch-review}
  [{:keys [branch]}]
  (let [profile     (mf/deref refs/profile)
        team        (mf/deref refs/team)
        desc-id     (mf/use-id)
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
             (st/emit! (dwbr/create-branch-review
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
                         :title (tr "workspace.branch-review.create.title")
                         :subtitle (tr "workspace.branch-review.create.subtitle" (or (:name branch) ""))}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:> input* {:label (tr "workspace.branch-review.create.name-label")
                  :icon i/git-pull-request-arrow
                  :placeholder (tr "workspace.branch-review.create.name-placeholder")
                  :value title
                  :on-change on-title-change}]

      [:div {:class (stl/css :field)}
       [:label {:class (stl/css :field-label) :for desc-id}
        (tr "workspace.branch-review.create.description-label")]
       [:> textarea* {:id desc-id
                      :class (stl/css :field-textarea)
                      :value description
                      :placeholder (tr "workspace.branch-review.create.description-placeholder")
                      :on-change on-description-change
                      :rows 3}]]

      [:div {:class (stl/css :field)}
       [:label {:class (stl/css :field-label)}
        (tr "workspace.branch-review.create.reviewers-label")]
       (if (empty? members)
         [:span {:class (stl/css :reviewers-empty)}
          (tr "workspace.branch-review.create.no-members")]
         [:ul {:class (stl/css :reviewers-list)}
          (for [member members]
            [:li {:key (dm/str (:id member))}
             [:button {:class (stl/css-case :reviewer-option true
                                            :is-selected (contains? selected (:id member)))
                       ;; a box to tick: the row keeps the avatar the DS
                       ;; checkbox has no room for
                       :role "checkbox"
                       :aria-checked (contains? selected (:id member))
                       :data-reviewer-id (dm/str (:id member))
                       :on-click on-toggle-reviewer}
              [:> avatar* {:profile member :variant "S"}]
              [:span {:class (stl/css :reviewer-name)} (:fullname member)]
              (when (contains? selected (:id member))
                [:> i/icon* {:icon-id i/tick :size "s" :class (stl/css :reviewer-tick)}])]])])]

      [:> context-notification* {:level :info :type :context}
       (tr "workspace.branch-review.create.info")]]

     [:> modal-footer* {:class (stl/css :dialog-footer)}
      [:> button* {:class (stl/css :dialog-action)
                   :variant "secondary" :on-click on-close}
       (tr "labels.cancel")]
      [:> button* {:class (stl/css :dialog-action)
                   :variant "primary"
                   :icon i/git-pull-request-create-arrow
                   :disabled (not valid?)
                   :on-click on-submit}
       (tr "workspace.branch-review.create.submit")]]]))

;; --- Review dialog (approve / request changes)

(mf/defc branch-review-verdict-dialog*
  "The verdict of a reviewer: Approve or Request changes. Without a
  comment, each button submits its verdict at once. Once the reviewer
  adds a comment, the buttons pick the verdict and Send submits both."
  {::mf/register modal/components
   ::mf/register-as :branch-review-verdict}
  [{:keys [pr]}]
  (let [comment-id  (mf/use-id)
        commenting* (mf/use-state false)
        commenting? (deref commenting*)
        verdict*    (mf/use-state nil)
        verdict     (deref verdict*)
        comment*    (mf/use-state "")
        comment     (deref comment*)

        submit
        (mf/use-fn
         (mf/deps pr comment)
         (fn [verdict]
           (st/emit! (dwbr/submit-review (:id pr) verdict
                                         (let [c (str/trim comment)]
                                           (when-not (str/blank? c) c)))
                     (modal/hide))))

        on-comment-change
        (mf/use-fn #(reset! comment* (dom/get-target-val %)))

        on-add-comment
        (mf/use-fn #(reset! commenting* true))

        on-verdict
        (mf/use-fn
         (mf/deps commenting? submit)
         (fn [event]
           (let [verdict (-> (dom/get-current-target event)
                             (dom/get-data "verdict"))]
             (if commenting?
               (reset! verdict* verdict)
               (submit verdict)))))

        on-send
        (mf/use-fn
         (mf/deps verdict submit)
         (fn [_]
           (when (some? verdict)
             (submit verdict))))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))]

    [:> modal* {:is-open true
                :on-open-change (on-dismiss on-close)
                :size "large"}
     [:> dialog-header* {:icon i/git-pull-request-arrow
                         :title (:title pr)
                         :subtitle (tr "workspace.review.route" (:branch-name pr)
                                       (rvc/route-target pr (tr "workspace.branches.main")))}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      ;; with a comment the buttons only pick the verdict, so they say
      ;; which one is picked
      [:div {:class (stl/css :verdict-buttons)}
       [:> button* {:variant (if (= verdict "approved") "primary" "secondary")
                    :icon i/tick
                    :aria-pressed (when commenting? (= verdict "approved"))
                    :data-verdict "approved"
                    :on-click on-verdict}
        (tr "workspace.branch-review.review.approve")]
       [:> button* {:variant (if (= verdict "changes-requested") "primary" "secondary")
                    :icon i/triangle-alert
                    :aria-pressed (when commenting? (= verdict "changes-requested"))
                    :data-verdict "changes-requested"
                    :on-click on-verdict}
        (tr "workspace.branch-review.review.request-changes")]]

      (if commenting?
        [:div {:class (stl/css :field)}
         [:label {:class (stl/css :field-label) :for comment-id}
          (tr "workspace.branch-review.review.comment-label")]
         [:> textarea* {:id comment-id
                        :class (stl/css :field-textarea)
                        :value comment
                        :placeholder (tr "workspace.branch-review.review.comment-placeholder")
                        :on-change on-comment-change
                        :rows 3}]]
        [:> button* {:class (stl/css :add-comment)
                     :variant "ghost"
                     :icon i/add
                     :on-click on-add-comment}
         (tr "workspace.branch-review.review.add-comment")])]

     [:> modal-footer* {:class (stl/css :dialog-footer)}
      [:> button* {:class (stl/css :dialog-action)
                   :variant "secondary" :on-click on-close}
       (tr "labels.cancel")]
      (when commenting?
        [:> button* {:class (stl/css :dialog-action)
                     :variant "primary"
                     :disabled (nil? verdict)
                     :on-click on-send}
         (tr "workspace.branch-review.review.send")])]]))

;; --- Details dialog

(mf/defc branch-review-info-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-review-info}
  [{:keys [pr]}]
  (let [pr        (use-live-verdicts pr)
        profiles  (mf/deref refs/profiles)
        profile   (mf/deref refs/profile)
        team      (mf/deref refs/team)
        author    (get profiles (:created-by pr))
        author?   (= (:created-by pr) (:id profile))
        admin?    (boolean (get-in team [:permissions :is-admin]))
        can-merge? (boolean (get-in team [:permissions :can-edit]))
        open?     (= "open" (:status pr))
        closed?   (= "closed" (:status pr))
        ;; already inside this branch review's review sandbox: offering to
        ;; open it again would be a no-op
        reviewing? (= (:id pr)
                      (:pr-id (mf/deref refs/branch-review-preview)))
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
                     (dwbr/open-branch-review pr))))

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
                     (dwbr/reopen-branch-review (:id pr)))))]

    [:> modal* {:is-open true
                :on-open-change (on-dismiss on-close)
                :size "large"}
     [:> dialog-header*
      {:icon i/git-pull-request-arrow
       :title (:title pr)
       :subtitle
       (mf/html
        [:div {:class (stl/css :dialog-subtitle-row)}
         [:> review-state-badge* {:pr pr}]
         [:> text* {:as "span"
                    :typography t/body-small
                    :class (stl/css :dialog-subtitle)}
          ;; the route reads branch → target: "Main" for the root of the
          ;; family, whose file name would be redundant inside the file
          ;; itself, and the target's name for a branch of a branch
          (tr "workspace.review.route" (:branch-name pr)
              (rvc/route-target pr (tr "workspace.branches.main")))]
         (when (and open? (pos? (or (:conflicts pr) 0)))
           [:span {:class (stl/css :pr-badge :badge-conflicts)}
            (tr "workspace.branch-review.badge.conflicts" (dm/str (:conflicts pr)))])])}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:div {:class (stl/css :pr-info-card)}
       [:div {:class (stl/css :pr-info-grid)}
        [:div {:class (stl/css :pr-info-item)}
         [:span {:class (stl/css :field-label)} (tr "workspace.branch-review.info.author")]
         [:div {:class (stl/css :pr-info-value)}
          (when author [:> avatar* {:profile author :variant "S"}])
          [:span (get author :fullname "—")]]]

        [:div {:class (stl/css :pr-info-item)}
         [:span {:class (stl/css :field-label)} (tr "workspace.branch-review.info.created-at")]
         [:div {:class (stl/css :pr-info-value)}
          [:span (ct/format-inst (:created-at pr) :localized-date-time)]]]

        [:div {:class (stl/css :pr-info-item)}
         [:span {:class (stl/css :field-label)} (tr "workspace.branch-review.info.approvals")]
         [:div {:class (stl/css :pr-info-value)}
          [:span (dm/str approvals "/" total)]]]]

       (when (pos? total)
         [:div {:class (stl/css :field)}
          [:label {:class (stl/css :field-label)}
           (tr "workspace.branch-review.info.reviewers")]
          [:ul {:class (stl/css :pr-info-reviewers)}
           (for [review (:reviews pr)]
             (let [reviewer (get profiles (:profile-id review))]
               [:li {:key (dm/str (:profile-id review))
                     :class (stl/css :pr-info-reviewer)}
                (when reviewer [:> avatar* {:profile reviewer :variant "S"}])
                [:span {:class (stl/css :reviewer-name)}
                 (get reviewer :fullname "—")]
                [:span {:class (stl/css-case :reviewer-state true
                                             :reviewer-approved (and (= "approved" (:verdict review))
                                                                     (not (:stale review)))
                                             :reviewer-changes (and (= "changes-requested" (:verdict review))
                                                                    (not (:stale review)))
                                             :reviewer-stale (:stale review))}
                 (verdict-label review)]
                (when-not (str/blank? (:comment review))
                  [:> text* {:as "p" :typography t/body-small :class (stl/css :reviewer-comment)}
                   (:comment review)])]))]])]

      (when-not (str/blank? (:description pr))
        [:div {:class (stl/css :pr-info-description-block)}
         [:label {:class (stl/css :field-label)}
          (tr "workspace.branch-review.info.description")]
         [:> text* {:as "p" :typography t/body-medium :class (stl/css :pr-info-description)}
          (:description pr)]])]

     [:> modal-footer* {:class (stl/css :dialog-footer)}
      (when (and (or author? admin?) open?)
        [:> button* {:class (stl/css :dialog-action)
                     :variant "secondary"
                     :on-click on-close-pr}
         (tr "workspace.branch-review.actions.close")])
      (when (and (or author? admin?) closed?)
        [:> button* {:class (stl/css :dialog-action)
                     :variant "secondary"
                     :on-click on-reopen-pr}
         (tr "workspace.branch-review.actions.reopen")])
      (when (and open? (not reviewing?))
        [:> button* {:class (stl/css :dialog-action)
                     :variant "primary"
                     :icon i/arrow-up-right
                     :on-click on-open-review}
         (tr "workspace.branch-review.actions.open-review")])
      (when (and open? can-merge?)
        [:> button* {:class (stl/css :dialog-action)
                     :variant "primary"
                     :icon i/git-merge
                     :on-click on-merge}
         (tr "workspace.branch-review.actions.merge")])]]))

;; --- Review bar (replaces the branch banner inside a branch review)

(mf/defc compare-banner-content*
  "What the review bar says and offers while the branch of the review is
  compared with main, or its conflicts are resolved: the comparison, how
  far along it is, its main action and the way back to the review. Only
  who may merge gets the action that merges."
  {::mf/private true}
  [{:keys [pr can-merge]}]
  (let [comparison (cc/use-comparison)
        resolving? (= :resolve (:phase comparison))
        {:keys [icon title progress exit action]} (cc/bar comparison)]
    [:div {:class (stl/css :pr-banner-content)}
     [:div {:class (stl/css :pr-banner-icon)}
      [:> i/icon* {:icon-id icon}]]
     [:div {:class (stl/css :pr-banner-text)}
      (if resolving?
        [:span {:class (stl/css :pr-banner-name)} title]
        [:*
         [:span {:class (stl/css :pr-banner-title)}
          (tr "workspace.compare.bar.comparing")]
         [:span {:class (stl/css :pr-banner-name)}
          (if (:incoming comparison)
            (tr "workspace.review.route"
                (rvc/route-target pr (tr "workspace.branches.main"))
                (:branch-name pr))
            (tr "workspace.review.route"
                (:branch-name pr)
                (rvc/route-target pr (tr "workspace.branches.main"))))]])]

     (when (some? progress)
       [:*
        [:div {:class (stl/css :pr-banner-separator)}]
        [:span {:class (stl/css :pr-banner-title :pr-banner-progress)} progress]])

     [:div {:class (stl/css :pr-banner-separator)}]

     [:div {:class (stl/css :pr-banner-actions)}
      (when (and (some? action) (or can-merge (not (:merge action))))
        [:> button* {:variant "primary"
                     :icon (:icon action)
                     :disabled (boolean (:disabled action))
                     :on-click (:on-click action)}
         (:label action)])
      [:> icon-button* {:variant "ghost"
                        :icon (:icon exit)
                        :aria-label (:label exit)
                        :tooltip-placement "top"
                        :on-click (:on-click exit)}]]]))

(defn canvas-center
  "The middle of the canvas, in pixels from the left of the window. The bar of
  the branch and the one of the review center there and not on the window, or
  at a usual width they would cover the folded palettes, on the left of the
  canvas. The rulers take their side of it."
  [vport rulers?]
  (let [left-sidebar (dom/get-element "left-sidebar-aside")
        left         (-> (dom/get-data left-sidebar "width")
                         (d/parse-integer)
                         (d/nilv 0))
        rulers       (if rulers? 22 0)]
    (+ left rulers (/ (- (:width vport) rulers) 2))))

(mf/defc review-banner*
  "The bar at the bottom of the canvas inside a branch review: the review
  being reviewed, the progress of its tasks, its details, the verdict of
  the reviewer and the way out. Merging and closing the review sit behind
  the menu."
  []
  (let [preview    (mf/deref refs/branch-review-preview)
        vport      (mf/deref refs/workspace-vport)
        rulers?    (mf/deref refs/rulers?)
        pr         (:info preview)
        comparing? (some? (mf/deref refs/compare-mode))
        profile    (mf/deref refs/profile)
        team       (mf/deref refs/team)
        tasks*     (mf/deref refs/tasks)
        author?    (= (:created-by pr) (:id profile))
        reviewer?  (some #(= (:profile-id %) (:id profile)) (:reviews pr))
        can-merge? (boolean (get-in team [:permissions :can-edit]))
        can-close? (and (or author? (boolean (get-in team [:permissions :is-admin])))
                        (= "open" (:status pr)))

        tasks      (mf/with-memo [tasks* pr]
                     (filterv #(= (:id pr) (:review-id %)) (vals tasks*)))
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
        (mf/use-fn (mf/deps pr) #(modal/show! :branch-review-verdict {:pr pr}))

        on-exit
        (mf/use-fn #(st/emit! (dwbr/exit-branch-review-preview)))

        on-menu-action
        (mf/use-fn
         (mf/deps pr)
         (fn [action]
           (reset! menu-open* false)
           (case action
             "compare" (open-compare! pr)
             "merge"   (confirm-merge! pr)
             "close"   (confirm-close! pr)
             nil)))]

    (when (some? pr)
      [:div {:class (stl/css :pr-banner)
             :style (when (some? vport)
                      #js {"insetInlineStart" (dm/str (canvas-center vport rulers?) "px")})
             :role "region"
             :aria-label (tr "workspace.review.bar.label")}
       ;; while its branch is compared with main, the bar belongs to the
       ;; comparison
       (if comparing?
         [:> compare-banner-content* {:pr pr :can-merge can-merge?}]
         [:div {:class (stl/css :pr-banner-content)}
          [:div {:class (stl/css :pr-banner-icon)}
           [:> i/icon* {:icon-id i/git-pull-request-arrow}]]
          [:div {:class (stl/css :pr-banner-text)}
           [:span {:class (stl/css :pr-banner-title)}
            (tr "workspace.branch-review.banner.reviewing")]
           ;; cut short when it is long: the whole of it on hover
           [:span {:class (stl/css :pr-banner-name) :title (:title pr)} (:title pr)]]

          [:div {:class (stl/css :pr-banner-separator)}]

          ;; the progress of the tasks shows once the review has any
          (when (pos? total)
            [:*
             [:> banner-button* {:on-click on-tasks}
              [:> i/icon* {:icon-id i/view-as-list :size "s"}]
              [:span (tr "workspace.review.bar.tasks" closed total)]]

             [:div {:class (stl/css :pr-banner-separator)}]])

          [:div {:class (stl/css :pr-banner-actions)}
           [:> button* {:variant "secondary" :on-click on-details}
            (tr "workspace.review.bar.details")]
           (when reviewer?
             [:> button* {:variant "primary" :on-click on-review}
              (tr "workspace.review.submit")])
           [:> icon-button* {:variant "ghost"
                             :icon i/exit
                             :aria-label (tr "workspace.branch-review.banner.exit")
                             :tooltip-placement "top"
                             :on-click on-exit}]

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
                (tr "workspace.branch-review.actions.merge")])
             (when can-close?
               [:> menu-item* {:id "close"}
                (tr "workspace.branch-review.actions.close")])]]]])])))
