;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branches
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.pull-requests :as dwpr]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item*]]
   [app.main.ui.ds.layout.modal :refer [modal* modal-header* modal-content* modal-footer*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.compare]
   [app.main.ui.workspace.sidebar.branch-review.conflicts]
   [app.main.ui.workspace.sidebar.pull-requests :as prui :refer [pr-state-badge*]]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private branches
  (l/derived :workspace-branches st/state))

(def ^:private branch-context
  ;; see refs/branch-context: the state entry wraps the row under :info
  (l/derived #(get-in % [:workspace-branch-context :info]) st/state))

;; --- Create branch dialog (modal)

(mf/defc create-branch-dialog*
  {::mf/register modal/components
   ::mf/register-as :create-branch}
  [{:keys [file-name]}]
  (let [;; a branch only grows from main, so the open file is main
        library?     (boolean (:is-shared (mf/deref refs/file)))
        branch-name* (mf/use-state "")
        description*  (mf/use-state "")
        branch-name   (deref branch-name*)
        description   (deref description*)
        valid?        (not (str/blank? branch-name))

        on-name-change
        (mf/use-fn #(reset! branch-name* (dom/get-target-val %)))

        on-description-change
        (mf/use-fn #(reset! description* (dom/get-target-val %)))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-submit
        (mf/use-fn
         (mf/deps branch-name description valid?)
         (fn [_]
           (when valid?
             (st/emit! (dwb/create-branch (str/trim branch-name) (str/trim description))
                       (modal/hide)))))]

    [:> modal* {:is-open true
                :on-open-change (prui/on-dismiss on-close)}
     [:> prui/dialog-header* {:icon i/git-branch
                              :title (tr "workspace.branches.create.title")
                              :subtitle (tr "workspace.branches.create.subtitle" (or file-name ""))}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:> input* {:label (tr "workspace.branches.create.name-label")
                  :icon i/git-branch
                  :placeholder (tr "workspace.branches.create.name-placeholder")
                  :value branch-name
                  :on-change on-name-change}]

      [:div {:class (stl/css :field)}
       [:label {:class (stl/css :field-label)}
        (tr "workspace.branches.create.description-label")]
       [:textarea {:class (stl/css :textarea)
                   :value description
                   :on-change on-description-change
                   :rows 2}]]

      [:div {:class (stl/css :field)}
       [:label {:class (stl/css :field-label)}
        (tr "workspace.branches.create.derive-from")]
       [:div {:class (stl/css :derive-from)}
        [:span {:class (stl/css :derive-from-dot)}]
        [:span {:class (stl/css :derive-from-name)}
         (tr "workspace.branches.create.derive-from-main")]
        [:span {:class (stl/css :derive-from-hint)}
         (tr "workspace.branches.create.derive-from-latest")]]]

      [:> context-notification* {:level :info :type :context}
       (if library?
         (tr "workspace.branches.create.info-library")
         (tr "workspace.branches.create.info"))]]

     [:> modal-footer* {}
      [:> button* {:variant "secondary" :on-click on-close}
       (tr "labels.cancel")]
      [:> button* {:variant "primary"
                   :icon i/git-branch-plus
                   :disabled (not valid?)
                   :on-click on-submit}
       (tr "workspace.branches.create.submit")]]]))

;; --- Preview limits dialog (modal)
;;
;; Every refusal the branching commands can emit has a line here, so a user
;; who meets one reads the feature as scoped rather than as broken. The three
;; numbers are fetched from the backend (`::get-branching-limits`) and never
;; restated in the copy: the config the gates read is their one home.

(defn- grouped
  "A limit as a reader sees it: 50,000 rather than 50000. Grouped here
  rather than with `toLocaleString`, which silently returns the ungrouped
  digits on a build without full ICU data."
  [n]
  (-> (dm/str n)
      (str/replace #"\B(?=(\d{3})+(?!\d))" ",")))

(mf/defc limits-item*
  {::mf/private true}
  [{:keys [text]}]
  [:li {:class (stl/css :limits-item)} text])

(mf/defc limits-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-limits}
  []
  (let [{:keys [limits]} (mf/deref branches)

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))]

    (mf/with-effect []
      (st/emit! (dwb/fetch-branching-limits)))

    [:> modal* {:is-open true
                :on-open-change (prui/on-dismiss on-close)}
     [:> prui/dialog-header* {:icon i/git-branch
                              :title (tr "workspace.branches.limits.title")
                              :subtitle (tr "workspace.branches.limits.subtitle")}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:div {:class (stl/css :limits-section)}
       [:h3 {:class (stl/css :limits-section-title)} (tr "workspace.branches.limits.size.title")]
       [:ul {:class (stl/css :limits-list)}
        (if (some? limits)
          [:*
           [:> limits-item* {:text (tr "workspace.branches.limits.size.shapes" (grouped (:max-shapes limits)))}]
           [:> limits-item* {:text (tr "workspace.branches.limits.size.pages" (grouped (:max-pages limits)))}]
           [:> limits-item* {:text (tr "workspace.branches.limits.size.changes" (grouped (:max-oplog-changes limits)))}]]
          [:> limits-item* {:text (tr "workspace.branches.limits.size.loading")}])]]

      [:div {:class (stl/css :limits-section)}
       [:h3 {:class (stl/css :limits-section-title)} (tr "workspace.branches.limits.scope.title")]
       [:ul {:class (stl/css :limits-list)}
        [:> limits-item* {:text (tr "workspace.branches.limits.scope.nested")}]
        [:> limits-item* {:text (tr "workspace.branches.limits.scope.libraries")}]
        [:> limits-item* {:text (tr "workspace.branches.limits.scope.comments")}]
        [:> limits-item* {:text (tr "workspace.branches.limits.scope.page-attrs")}]]]

      [:div {:class (stl/css :limits-section)}
       [:h3 {:class (stl/css :limits-section-title)} (tr "workspace.branches.limits.merge.title")]
       [:ul {:class (stl/css :limits-list)}
        [:> limits-item* {:text (tr "workspace.branches.limits.merge.whole-entities")}]
        [:> limits-item* {:text (tr "workspace.branches.limits.merge.no-partial")}]
        [:> limits-item* {:text (tr "workspace.branches.limits.merge.main-moved")}]
        [:> limits-item* {:text (tr "workspace.branches.limits.merge.base-missing")}]]]

      [:> context-notification* {:level :info :type :context}
       (tr "workspace.branches.limits.exit")]]

     [:> modal-footer* {}
      [:> button* {:variant "primary" :on-click on-close}
       (tr "labels.close")]]]))

;; --- Merge confirmation dialog (modal)

(mf/defc merge-branch-dialog*
  {::mf/register modal/components
   ::mf/register-as :merge-branch}
  [{:keys [branch excluded]}]
  (let [archive?* (mf/use-state false)
        archive?  (deref archive?*)

        ;; The branch name and "main" are highlighted (foreground-primary)
        ;; within the otherwise secondary-colored prompt. We wrap each of
        ;; them with a sentinel in the `tr` args and split on it, so the
        ;; whole sentence stays in a single translation and the (user
        ;; controlled) branch name is rendered as escaped text, not HTML.
        message-parts
        (let [sep "\uE000"
              msg (tr "workspace.branches.merge.confirm-message"
                      (str sep "\"" (:name branch) "\"" sep)
                      (str sep (tr "workspace.branches.main") sep))]
          (str/split msg sep))

        on-toggle-archive
        (mf/use-fn #(swap! archive?* not))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-submit
        (mf/use-fn
         (mf/deps branch archive? excluded)
         (fn [_]
           (st/emit! (dwb/merge-branch branch {:keep-branch archive? :excluded excluded})
                     (modal/hide))))]

    [:> modal* {:is-open true
                :on-open-change (prui/on-dismiss on-close)}
     [:> modal-header* {:title (tr "workspace.branches.merge.confirm-title")}]

     [:> modal-content* {:class (stl/css :dialog-content)}
      [:p {:class (stl/css :modal-message)}
       (for [[idx part] (map-indexed vector message-parts)]
         (if (odd? idx)
           [:span {:key idx :class (stl/css :modal-message-emphasis)} part]
           [:span {:key idx} part]))]

      [:> checkbox* {:id "merge-archive-branch"
                     :label (tr "workspace.branches.merge.archive-label")
                     :checked archive?
                     :on-change on-toggle-archive}]

      (if archive?
        [:> context-notification* {:level :warning :type :context}
         (tr "workspace.branches.merge.archive-warning")]
        [:> context-notification* {:level :info :type :context}
         (tr "workspace.branches.merge.delete-info")])]

     [:> modal-footer* {}
      [:> button* {:variant "secondary" :on-click on-close}
       (tr "labels.cancel")]
      [:> button* {:variant "primary"
                   :icon i/git-merge
                   :on-click on-submit}
       (tr "workspace.branches.merge.confirm-accept")]]]))

;; --- Confirmation for relevant branch actions (merge / update / materialize)

(defn- confirm-materialize!
  "Ask for confirmation before turning `branch` into an ordinary file: the
  branch stops being one, so it can no longer be compared or merged."
  [branch]
  (st/emit! (modal/show {:type :confirm
                         :title (tr "workspace.branches.materialize.title")
                         :message (tr "workspace.branches.materialize.message" (:name branch))
                         :accept-label (tr "workspace.branches.materialize.accept")
                         :accept-style :danger
                         :on-accept (fn [_] (st/emit! (dwb/materialize-branch (:branch-file-id branch))))})))

;; --- Diff counts: known, or honestly unknown
;;
;; A row whose comparison could not be made carries no counts at all (and
;; the `:diff-error` marker), because zeros read as "in sync" and a
;; branch that cannot be compared is not in sync. Every surface showing
;; the counts shows the comparison warning in their place.

(defn- counts-error?
  [row]
  (or (:diff-error row)
      (nil? (:ahead row))))

;; --- Branch info dialog (read-only info card + editable description)

(mf/defc branch-info-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-info}
  [{:keys [branch]}]
  (let [profile   (mf/deref refs/profile)
        profiles  (mf/deref refs/profiles)
        author    (or (get profiles (:created-by branch))
                      (when (= (:created-by branch) (:id profile)) profile))
        author?   (= (:created-by branch) (:id profile))
        status    (:status branch)
        merged?   (= "merged" status)
        archived? (= "archived" status)
        ;; only an open branch carries change counts
        open?     (= "open" status)

        editing?  (mf/use-state false)
        desc*     (mf/use-state (or (:description branch) ""))
        desc      (deref desc*)

        on-close       (mf/use-fn #(st/emit! (modal/hide)))
        on-edit        (mf/use-fn #(reset! editing? true))
        on-desc-change (mf/use-fn #(reset! desc* (dom/get-target-val %)))
        on-cancel      (mf/use-fn (mf/deps branch)
                                  (fn [_]
                                    (reset! desc* (or (:description branch) ""))
                                    (reset! editing? false)))
        on-save        (mf/use-fn (mf/deps branch desc)
                                  (fn [_]
                                    (st/emit! (dwb/set-branch-description (:id branch) (str/trim desc)))
                                    (reset! editing? false)))
        on-compare     (mf/use-fn (mf/deps branch)
                                  #(modal/show! :branch-compare {:branch branch}))
        on-restore     (mf/use-fn (mf/deps branch)
                                  (fn [_]
                                    (st/emit! (modal/hide)
                                              (dwb/archive-branch (:id branch) false))))
        on-materialize (mf/use-fn (mf/deps branch)
                                  #(confirm-materialize! branch))]
    [:> modal* {:is-open true
                :on-open-change (prui/on-dismiss on-close)}
     [:> prui/dialog-header* {:icon i/git-branch
                              :title (tr "workspace.branches.info.title")
                              :subtitle (:name branch)}
      [:span {:class (stl/css-case :info-status true
                                   :status-merged merged?
                                   :status-archived (= "archived" status)
                                   :status-open (= "open" status))}
       (tr (case status
             "merged"   "workspace.branches.info.status.merged"
             "archived" "workspace.branches.info.status.archived"
             "workspace.branches.info.status.open"))]]

     [:> modal-content* {:class (stl/css :dialog-content)}
      ;; description: shown read-only, editable inline by the author
      [:div {:class (stl/css :info-section)}
       [:div {:class (stl/css :info-section-head)}
        [:span {:class (stl/css :field-label)} (tr "workspace.branches.info.description")]
        (when (and author? (not (deref editing?)))
          [:> button* {:variant "ghost" :on-click on-edit} (tr "labels.edit")])]
       (if (deref editing?)
         [:div {:class (stl/css :info-desc-edit)}
          [:textarea {:class (stl/css :textarea)
                      :value desc
                      :on-change on-desc-change
                      :rows 3
                      :auto-focus true
                      :placeholder (tr "workspace.branches.info.description-placeholder")}]
          [:div {:class (stl/css :info-desc-actions)}
           [:> button* {:variant "secondary" :on-click on-cancel} (tr "labels.cancel")]
           [:> button* {:variant "primary" :on-click on-save} (tr "labels.save")]]]
         (if (str/blank? (:description branch))
           [:p {:class (stl/css :info-desc-empty)} (tr "workspace.branches.info.description-empty")]
           [:p {:class (stl/css :info-desc)} (:description branch)]))]

      ;; metadata
      [:dl {:class (stl/css :info-grid)}
       [:div {:class (stl/css :info-row)}
        [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.author")]
        [:dd {:class (stl/css :info-val)}
         (when author [:> avatar* {:profile author :variant "S"}])
         [:span (or (:fullname author) "—")]]]

       [:div {:class (stl/css :info-row)}
        [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.derived-from")]
        [:dd {:class (stl/css :info-val)}
         [:> i/icon* {:icon-id i/git-commit-vertical :size "s"}]
         [:span (or (:source-name branch) (tr "workspace.branches.main"))]]]

       (when (:created-at branch)
         [:div {:class (stl/css :info-row)}
          [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.created")]
          [:dd {:class (stl/css :info-val)}
           (-> (:created-at branch) ct/inst (ct/format-inst :localized-date-time))]])

       (when (:updated-at branch)
         [:div {:class (stl/css :info-row)}
          [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.updated")]
          [:dd {:class (stl/css :info-val)}
           (some-> (:updated-at branch) ct/inst ct/timeago)]])

       (when open?
         [:div {:class (stl/css :info-row)}
          [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.changes")]
          [:dd {:class (stl/css :info-val :info-counts)}
           (if (counts-error? branch)
             [:span {:class (stl/css :count-error)
                     :title (tr "workspace.branches.counts.error")}
              [:> i/icon* {:icon-id i/triangle-alert :size "s"}]]
             [:*
              [:span {:class (stl/css-case :count true :has-changes (pos? (:ahead branch 0)))
                      :title (tr "workspace.branches.counts.ahead")}
               [:> i/icon* {:icon-id i/arrow-up :size "s"}] (dm/str (:ahead branch 0))]
              [:span {:class (stl/css-case :count true :is-behind true :has-changes (pos? (:behind branch 0)))
                      :title (tr "workspace.branches.counts.behind")}
               [:> i/icon* {:icon-id i/arrow-down :size "s"}] (dm/str (:behind branch 0))]
              (when (pos? (:conflicts branch 0))
                [:span {:class (stl/css :item-badge :badge-conflict)}
                 (tr "workspace.branches.banner.conflicts" (dm/str (:conflicts branch)))])])]])

       (when (:merged-at branch)
         [:div {:class (stl/css :info-row)}
          [:dt {:class (stl/css :info-key)} (tr "workspace.branches.info.merged")]
          [:dd {:class (stl/css :info-val)}
           (-> (:merged-at branch) ct/inst (ct/format-inst :localized-date-time))]])]]

     [:> modal-footer* {}
      ;; an archived branch is read-only: it comes back to work restored,
      ;; or leaves the branch engine as an ordinary file
      (when archived?
        [:> button* {:variant "secondary" :icon i/history :on-click on-restore}
         (tr "workspace.branches.menu.restore")])
      (when archived?
        [:> button* {:variant "secondary" :icon i/document :on-click on-materialize}
         (tr "workspace.branches.materialize.action")])
      ;; the work of a merged branch is in main: there is nothing to compare
      (when-not merged?
        [:> button* {:variant "secondary" :icon i/git-compare-arrows :on-click on-compare}
         (tr "workspace.branches.compare")])
      [:> button* {:variant "primary" :on-click on-close} (tr "labels.close")]]]))

;; --- Branch list entry

(mf/defc branch-entry*
  {::mf/private true}
  [{:keys [entry profiles current pr menu-open? on-set-menu]}]
  (let [author    (get profiles (:created-by entry))
        ahead     (:ahead entry)
        behind    (:behind entry)
        main?     (:is-main entry)
        merged?   (= "merged" (:status entry))
        archived? (= "archived" (:status entry))
        ;; merged and archived branches are out of the flow: no counts, no
        ;; branch work, and dimmed in the list
        closed?   (or merged? archived?)

        ;; `pr` is the branch's open pull request (nil when none): the
        ;; review is a STATE of the branch, so the entry surfaces it as a
        ;; badge and swaps "Request review" for the review actions
        profile    (mf/deref refs/profile)
        team       (mf/deref refs/team)
        author?    (and pr (= (:created-by pr) (:id profile)))
        admin?     (boolean (get-in team [:permissions :is-admin]))
        can-merge? (boolean (get-in team [:permissions :can-edit]))

        [approvals total] (if pr (prui/current-approvals pr) [0 0])

        editing?   (mf/use-state false)

        on-open
        (mf/use-fn
         (mf/deps entry editing?)
         (fn [_]
           (when-not (deref editing?)
             (st/emit! (dwb/open-branch (:branch-file-id entry))))))

        on-compare
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :branch-compare {:branch entry})))

        on-update
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (brc/confirm-update! entry)))

        on-toggle-menu
        (mf/use-fn (mf/deps on-set-menu menu-open?)
                   (fn [event]
                     (dom/stop-propagation event)
                     (on-set-menu (not menu-open?))))

        on-info
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :branch-info {:branch entry})))

        on-start-rename
        (mf/use-fn (fn [event]
                     (dom/stop-propagation event)
                     (on-set-menu false)
                     (reset! editing? true)))

        on-request-review
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :create-pull-request {:branch entry})))

        on-open-review
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (st/emit! (dwpr/open-pull-request pr))))

        on-review-details
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (modal/show! :pull-request-info {:pr pr})))

        on-publish-review
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (st/emit! (dwpr/update-pull-request-snapshot (:id pr)))))

        on-merge-review
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (brc/confirm-merge! entry)))

        on-cancel-review
        (mf/use-fn
         (mf/deps pr)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (prui/confirm-close! pr)))

        on-rename-commit
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (let [value (str/trim (dom/get-target-val event))]
             (when (and (seq value) (not= value (:name entry)))
               (st/emit! (dwb/rename-branch (:id entry) value)))
             (reset! editing? false))))

        on-rename-key-down
        (mf/use-fn
         (mf/deps on-rename-commit)
         (fn [event]
           (cond
             (kbd/enter? event) (on-rename-commit event)
             (kbd/esc? event)   (reset! editing? false))))

        on-archive
        (mf/use-fn (mf/deps entry archived?)
                   (fn [event]
                     (dom/stop-propagation event)
                     (on-set-menu false)
                     (st/emit! (dwb/archive-branch (:id entry) (not archived?)))))

        on-delete
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (st/emit! (modal/show {:type :confirm
                                  :title (tr "workspace.branches.delete.title")
                                  :message (tr "workspace.branches.delete.message" (:name entry))
                                  :accept-label (tr "labels.delete")
                                  :accept-style :danger
                                  :on-accept (fn [_] (st/emit! (dwb/delete-branch (:id entry))))}))))

        ;; the exit door: the branch stops being a branch but keeps its
        ;; content, and the confirmation says so, because the branch row and
        ;; the link to main do not come back
        on-materialize
        (mf/use-fn
         (mf/deps entry)
         (fn [event]
           (dom/stop-propagation event)
           (on-set-menu false)
           (confirm-materialize! entry)))]

    [:li {:class (stl/css-case :branch-entry true
                               :is-archived closed?
                               :is-current current)
          :role "button"
          :on-click (when-not current on-open)}
     [:div {:class (stl/css :branch-entry-icon)}
      [:> i/icon* {:icon-id (if main? i/git-commit-vertical i/git-branch)}]]

     [:div {:class (stl/css :branch-entry-body)}
      (if (deref editing?)
        [:input {:class (stl/css :branch-rename-input)
                 :default-value (:name entry)
                 :auto-focus true
                 :on-click (fn [e] (dom/stop-propagation e))
                 :on-blur on-rename-commit
                 :on-key-down on-rename-key-down}]
        [:span {:class (stl/css :branch-entry-name)} (:name entry)])
      [:div {:class (stl/css :branch-entry-meta)}
       ;; Main is the default branch: always show it the same way (no avatar,
       ;; just the "Default branch" subtitle), even when it's the current branch
       (when (and author (not main?))
         [:> avatar* {:profile author :variant "S"}])
       [:span {:class (stl/css :branch-entry-author)}
        (if main?
          (tr "workspace.branches.main-subtitle")
          (:fullname author))]]
      (when (some? pr)
        [:div {:class (stl/css :branch-entry-badges)}
         [:> i/icon* {:icon-id i/git-pull-request-arrow :size "s"}]
         [:> pr-state-badge* {:pr pr}]
         (when (pos? total)
           [:span {:class (stl/css :branch-pr-approvals)
                   :title (tr "workspace.pull-requests.info.approvals")}
            [:> i/icon* {:icon-id i/tick :size "s"}]
            (dm/str approvals "/" total)])])]

     [:div {:class (stl/css :branch-entry-aside)}
      (when (and (not closed?) (not main?))
        [:div {:class (stl/css :branch-entry-counts)}
         (if (counts-error? entry)
           [:span {:class (stl/css :count-error)
                   :title (tr "workspace.branches.counts.error")}
            [:> i/icon* {:icon-id i/triangle-alert :size "s"}]]
           [:*
            [:span {:class (stl/css-case :count true :has-changes (pos? (or ahead 0)))
                    :title (tr "workspace.branches.counts.ahead")}
             [:> i/icon* {:icon-id i/arrow-up :size "s"}]
             (dm/str ahead)]
            [:span {:class (stl/css-case :count true :is-behind true :has-changes (pos? (or behind 0)))
                    :title (tr "workspace.branches.counts.behind")}
             [:> i/icon* {:icon-id i/arrow-down :size "s"}]
             (dm/str behind)]])])

      ;; the row opens the branch on click: clicks on the menu, its
      ;; trigger and its items (portaled, but still in this React tree)
      ;; must not reach it
      (when-not main?
        [:div {:class (stl/css :branch-entry-menu)
               :on-click dom/stop-propagation}
         [:> menu* {:is-open menu-open?
                    :on-open-change on-set-menu
                    :placement "bottom end"
                    :trigger
                    (mf/html
                     [:> icon-button* {:variant "ghost"
                                       :icon i/menu
                                       :aria-label (tr "labels.options")
                                       :aria-pressed menu-open?
                                       :on-click on-toggle-menu}])}
          [:> menu-item* {:id "info" :on-action on-info}
           (tr "workspace.branches.menu.info")]
          (when-not merged?
            [:> menu-item* {:id "compare" :on-action on-compare}
             (tr "workspace.branches.compare")])
          (when (and (not closed?) (pos? (or behind 0)))
            [:> menu-item* {:id "update" :on-action on-update}
             (tr "workspace.branches.update")])
          ;; review lifecycle: a branch without an open pull request offers to
          ;; request one; with it, the review actions take its place (the
          ;; review either ends merging the branch or is cancelled, leaving
          ;; the branch as a normal branch again)
          (when (and (not closed?) (dwpr/enabled?) (nil? pr))
            [:> menu-item* {:id "request-review" :on-action on-request-review}
             (tr "workspace.pull-requests.menu.request-review")])
          (when (some? pr)
            [:> menu-item* {:id "open-review" :on-action on-open-review}
             (tr "workspace.pull-requests.actions.open-review")])
          (when (some? pr)
            [:> menu-item* {:id "review-details" :on-action on-review-details}
             (tr "workspace.pull-requests.menu.review-details")])
          (when (and (some? pr) author?)
            [:> menu-item* {:id "publish-review" :on-action on-publish-review}
             (tr "workspace.pull-requests.menu.publish")])
          (when (and (some? pr) can-merge?)
            [:> menu-item* {:id "merge-review" :on-action on-merge-review}
             (tr "workspace.pull-requests.actions.merge")])
          (when (and (some? pr) (or author? admin?))
            [:> menu-item* {:id "cancel-review" :on-action on-cancel-review}
             (tr "workspace.pull-requests.actions.close")])
          (when-not closed?
            [:> menu-item* {:id "rename" :on-action on-start-rename}
             (tr "labels.rename")])
          ;; a merged branch cannot go back to work: its changes are in main
          (when-not merged?
            [:> menu-item* {:id "archive" :on-action on-archive}
             (tr (if archived?
                   "workspace.branches.menu.restore"
                   "workspace.branches.menu.archive"))])
          ;; a merged branch has its work in main already: only an open or an
          ;; archived branch can leave the branch engine as an ordinary file
          (when-not merged?
            [:> menu-item* {:id "materialize" :on-action on-materialize}
             (tr "workspace.branches.materialize.action")])
          [:> menu-item* {:id "delete" :on-action on-delete}
           (tr "labels.delete")]]])]]))

;; --- All branches dialog (the full list, with each branch's options)

(defn- branches-for-tab
  "The branch rows a tab of the all-branches dialog lists: the open ones,
  the open ones the profile created, the merged ones, or the archived
  ones."
  [tab rows profile-id]
  (case tab
    "mine"     (filter #(and (= "open" (:status %)) (= profile-id (:created-by %))) rows)
    "merged"   (filter #(= "merged" (:status %)) rows)
    "archived" (filter #(= "archived" (:status %)) rows)
    (filter #(= "open" (:status %)) rows)))

(mf/defc branches-list-dialog*
  {::mf/register modal/components
   ::mf/register-as :branches-list}
  []
  (let [{:keys [data status]} (mf/deref branches)
        profiles       (mf/deref refs/profiles)
        profile        (mf/deref refs/profile)
        file           (mf/deref refs/file)
        branch-ctx     (mf/deref branch-context)
        prs            (mf/deref refs/pull-requests)
        profile-id     (:id profile)

        profiles       (cond-> profiles
                         profile-id (assoc profile-id profile))

        prs-by-branch
        (mf/with-memo [prs]
          (when (dwpr/enabled?)
            (->> (:data prs)
                 (filter #(= "open" (:status %)))
                 (map (juxt :file-branch-id identity))
                 (into {}))))

        tab*      (mf/use-state "active")
        tab       (deref tab*)
        filter*   (mf/use-state "")
        filter-v  (deref filter*)

        ;; only one entry's options dropdown may be open at a time
        open-menu* (mf/use-state nil)
        open-menu  (deref open-menu*)
        set-menu   (mf/use-fn (fn [k open?] (reset! open-menu* (when open? k))))

        matching
        (mf/with-memo [data filter-v]
          (filter #(or (str/blank? filter-v)
                       (str/includes? (str/lower (or (:name %) "")) (str/lower filter-v)))
                  data))

        tabs
        (mf/with-memo [data profile-id]
          (let [label (fn [text t] (dm/str text " (" (count (branches-for-tab t data profile-id)) ")"))]
            [{:id "mine"     :label (label (tr "workspace.branches.list.tab.mine") "mine")}
             {:id "active"   :label (label (tr "workspace.branches.list.tab.active") "active")}
             {:id "merged"   :label (label (tr "workspace.branches.merged") "merged")}
             {:id "archived" :label (label (tr "workspace.branches.archived") "archived")}]))

        rows      (branches-for-tab tab matching profile-id)

        on-tab-change    (mf/use-fn #(reset! tab* %))
        on-filter-change (mf/use-fn #(reset! filter* (dom/get-target-val %)))

        search
        (mf/html
         [:> input* {:variant "dense"
                     :icon i/search
                     :class (stl/css :branches-list-search)
                     :placeholder (tr "workspace.branches.search.placeholder")
                     :value filter-v
                     :on-change on-filter-change}])

        on-open-change
        (mf/use-fn
         (fn [open?]
           (when-not open?
             (st/emit! (modal/hide)))))

        on-show-limits
        (mf/use-fn #(modal/show! :branch-limits {}))

        on-create
        (mf/use-fn
         (mf/deps file)
         (fn [_]
           (modal/show! :create-branch {:file-name (:name file)})))

        on-retry
        (mf/use-fn #(st/emit! (dwb/init-branches-state)))]

    ;; before paint, so a list from an earlier opening never shows in
    ;; place of the loading state
    (mf/with-layout-effect []
      (st/emit! (dwb/init-branches-state))
      (when (dwpr/enabled?)
        (st/emit! (dwpr/fetch-pull-requests))))

    [:> modal* {:is-open true
                :on-open-change on-open-change
                :size "large"}
     [:> modal-header* {:title (tr "workspace.branches.list.title" (or (:source-name branch-ctx) (:name file)))}]
     [:> modal-content* {:class (stl/css :branches-list-content)}
      [:> tab-switcher* {:tabs tabs
                         :selected tab
                         :on-change on-tab-change
                         :scrollable-panel true
                         :action-button-position "end"
                         :action-button search
                         :class (stl/css :branches-list-tabs)}
       (cond
         (= status :loading)
         [:div {:class (stl/css :branches-empty) :role "status"}
          [:> empty-state* {:icon i/git-branch
                            :text (tr "workspace.branches.loading")}]]

         (= status :error)
         [:div {:class (stl/css :branches-empty) :role "alert"}
          [:> empty-state* {:icon i/triangle-alert
                            :text (tr "workspace.branches.list.error")}]
          [:> button* {:variant "secondary"
                       :icon i/reload
                       :on-click on-retry}
           (tr "labels.retry")]]

         (empty? rows)
         [:div {:class (stl/css :branches-empty)}
          [:> empty-state* {:icon i/git-branch
                            :text (tr "workspace.branches.list.empty")}]]

         :else
         [:ul {:class (stl/css :branches-entries :branches-list-entries)}
          (for [entry rows]
            (let [k (dm/str (:id entry))]
              [:> branch-entry* {:key k
                                 :entry entry
                                 :profiles profiles
                                 :current (= (:id entry) (:id branch-ctx))
                                 :pr (get prs-by-branch (:id entry))
                                 :menu-open? (= open-menu k)
                                 :on-set-menu (partial set-menu k)}]))])]]

     ;; the preview's limits belong where the feature is used: a user who
     ;; meets a refusal finds the reason here
     [:> modal-footer* {:variant "split"
                        :start (mf/html
                                [:> button* {:variant "ghost"
                                             :icon i/help
                                             :on-click on-show-limits}
                                 (tr "workspace.branches.limits.link")])
                        :end (let [;; branches only grow from main: on a
                                   ;; branch the button stays in place,
                                   ;; disabled, and says where branches
                                   ;; come from
                                   on-branch? (and (some? branch-ctx)
                                                   (= (:branch-file-id branch-ctx) (:id file)))
                                   button     (mf/html
                                               [:> button* {:variant "primary"
                                                            :icon i/git-branch-plus
                                                            :disabled on-branch?
                                                            :on-click on-create}
                                                (tr "workspace.branches.switcher.new")])]
                               (if on-branch?
                                 (mf/html
                                  [:> tooltip* {:content (tr "workspace.branches.switcher.new-from-main")
                                                :id "new-branch-disabled-tooltip"
                                                :tab-index 0}
                                   button])
                                 button))}]]))

;; --- Branch context banner (shown while editing a branch)

(defn- publish-banner-clearance!
  "Publish the room the branch banner takes from the bottom edge of the
  canvas as `--branch-banner-clearance` on the app root, which the floating
  colour/typography palette bar reads to lift itself clear (`palette.scss`):
  the banner and the bar hang from the same edge and the bar draws over the
  banner's controls. The room is measured from the banner's live box rather
  than written down, so it follows the banner in both the collapsed and the
  expanded state, where one fixed offset would be wrong in one of the two."
  []
  (when-let [root (dom/get-root)]
    (when-let [node (dom/get-element "branch-banner")]
      (let [room (- (:bottom (dom/get-bounding-rect root))
                    (:top (dom/get-bounding-rect node)))]
        (dom/set-css-property! root
                               "--branch-banner-clearance"
                               (dm/str (max 0 room) "px"))))))

(defn- banner-status
  "What the bottom banner says about the open branch, most pressing
  first and in the order of its primary action: merged, archived,
  conflicts, main moved, an open review, branch work."
  [ctx pr]
  (let [ahead     (or (:ahead ctx) 0)
        behind    (or (:behind ctx) 0)
        conflicts (or (:conflicts ctx) 0)]
    (cond
      (= "merged" (:status ctx))
      {:id :merged
       :icon i/git-merge
       :label (tr "workspace.branches.banner.merged")}

      (= "archived" (:status ctx))
      {:id :archived
       :icon i/git-branch
       :label (tr "workspace.branches.banner.archived")}

      (counts-error? ctx)
      {:id :counts-error
       :icon i/triangle-alert
       :label (tr "workspace.branches.counts.error")}

      (pos? conflicts)
      {:id :conflicts
       :icon i/triangle-alert
       :label (tr "workspace.branches.banner.status.conflicts" (i18n/c conflicts))}

      (pos? behind)
      {:id :behind
       :icon i/arrow-down
       :label (tr "workspace.branches.banner.status.behind" (i18n/c behind))}

      (some? pr)
      (let [[approvals total] (prui/current-approvals pr)]
        {:id :review
         :icon i/git-pull-request-arrow
         :label (tr "workspace.branches.banner.status.in-review"
                    (dm/str approvals) (dm/str total))})

      (pos? ahead)
      {:id :ahead
       :icon i/arrow-up
       :label (tr "workspace.branches.banner.status.ahead" (i18n/c ahead))}

      :else
      {:id :up-to-date
       :icon i/git-branch
       :label (tr "workspace.branches.banner.status.up-to-date")})))

(mf/defc branch-context-banner*
  [{:keys [file-id]}]
  (let [ctx         (mf/deref branch-context)
        merged?     (= "merged" (:status ctx))
        archived?   (= "archived" (:status ctx))

        collapsed*  (mf/use-state false)
        collapsed?  (deref collapsed*)
        on-toggle   (mf/use-fn #(swap! collapsed* not))

        ;; persistence status drives a refetch of the change counts on save
        persistence (mf/deref refs/persistence)
        pstatus     (:status persistence)

        ;; with pull requests enabled the merge action moves behind the
        ;; kebab menu and requesting a review becomes the primary action
        ;; (or opening the branch's already-open pull request)
        prs-enabled? (dwpr/enabled?)
        open-pr      (when prs-enabled?
                       (->> (:data (mf/deref refs/pull-requests))
                            (filter #(and (= "open" (:status %))
                                          (= (:id ctx) (:file-branch-id %))))
                            (first)))

        menu-open*     (mf/use-state false)
        menu-open?     (deref menu-open*)
        on-toggle-menu (mf/use-fn #(swap! menu-open* not))
        on-menu-change (mf/use-fn #(reset! menu-open* %))

        on-compare   (mf/use-fn (mf/deps ctx) #(modal/show! :branch-compare {:branch ctx}))
        on-update    (mf/use-fn (mf/deps ctx) #(brc/confirm-update! ctx))
        on-open-main (mf/use-fn (mf/deps ctx) #(st/emit! (dwb/open-branch (:source-file-id ctx))))
        on-restore   (mf/use-fn (mf/deps ctx) #(st/emit! (dwb/archive-branch (:id ctx) false)))
        on-materialize (mf/use-fn (mf/deps ctx) #(confirm-materialize! ctx))
        on-resolve   (mf/use-fn (mf/deps ctx) #(modal/show! :branch-conflicts {:branch ctx :mode :merge}))

        on-merge
        (mf/use-fn
         (mf/deps ctx)
         (fn [_]
           (reset! menu-open* false)
           (brc/confirm-merge! ctx)))

        on-request-review
        (mf/use-fn
         (mf/deps ctx)
         #(modal/show! :create-pull-request {:branch ctx}))

        on-open-review
        (mf/use-fn
         (mf/deps open-pr)
         #(st/emit! (dwpr/open-pull-request open-pr)))

        ;; publishes the room the banner takes from the bottom of the canvas
        ;; whenever its box changes size, which is what keeps the floating
        ;; colour/typography palette bar clear of it in both the collapsed and
        ;; the expanded state (see `publish-banner-clearance!`)
        banner-ref (r/use-resize-observer
                    (mf/use-fn (fn [_ _] (publish-banner-clearance!))))

        status     (banner-status ctx open-pr)]

    (mf/with-effect [file-id]
      (when (contains? cf/flags :branching)
        (st/emit! (dwb/fetch-branch-context)))
      ;; the primary action depends on whether the branch already has an
      ;; open pull request
      (when (dwpr/enabled?)
        (st/emit! (dwpr/fetch-pull-requests))))

    ;; refresh the change counts every time a save settles (and when
    ;; returning to the tab, so "main advanced" is surfaced too)
    (mf/with-effect [pstatus]
      (when (and (= :saved pstatus) (contains? cf/flags :branching))
        (st/emit! (dwb/fetch-branch-context))))

    (mf/with-effect []
      (let [key (events/listen globals/window "focus"
                               (fn [_]
                                 (when (contains? cf/flags :branching)
                                   (st/emit! (dwb/fetch-branch-context)))))]
        (fn [] (events/unlistenByKey key))))

    ;; it is cleared when there is no banner to clear, so a file that is not a
    ;; branch leaves the palette bar on its plain bottom anchor
    (mf/with-effect [ctx]
      (if (some? ctx)
        (publish-banner-clearance!)
        (dom/unset-css-property! (dom/get-root) "--branch-banner-clearance")))

    ;; the same goes when the banner goes away with the component, so nothing
    ;; is left behind for the palette bar to clear later
    (mf/with-effect []
      (fn [] (dom/unset-css-property! (dom/get-root) "--branch-banner-clearance")))

    (when ctx
      [:div {:id "branch-banner"
             :ref banner-ref
             :class (stl/css-case :branch-banner true
                                  :branch-banner-merged (or merged? archived?)
                                  :is-collapsed collapsed?)}
       [:button {:class (stl/css :branch-banner-handle)
                 :title (tr (if collapsed?
                              "workspace.branches.banner.expand"
                              "workspace.branches.banner.collapse"))
                 :aria-label (tr (if collapsed?
                                   "workspace.branches.banner.expand"
                                   "workspace.branches.banner.collapse"))
                 :on-click on-toggle}
        [:div {:class (stl/css :branch-banner-handle-btn)}]]
       ;; the header row names the branch; the banner says where it
       ;; stands and offers what to do next
       [:div {:class (stl/css :branch-banner-content)}
        [:div {:class (stl/css-case :branch-banner-icon true
                                    :branch-banner-icon-warning (contains? #{:conflicts :counts-error} (:id status)))}
         [:> i/icon* {:icon-id (:icon status)}]]
        [:div {:class (stl/css :branch-banner-text)}
         [:span {:class (stl/css :branch-banner-title)} (:label status)]]
        [:div {:class (stl/css :branch-banner-separator)}]

        (cond
          merged?
          [:> button* {:variant "secondary"
                       :icon i/arrow-up-right
                       :on-click on-open-main}
           (tr "workspace.branches.banner.view-main")]

          ;; an archived branch was set aside unmerged and is read-only: it
          ;; can be compared with main, brought back to work, or taken out
          ;; as an ordinary file
          archived?
          [:div {:class (stl/css :branch-banner-actions)}
           [:> button* {:variant "secondary"
                        :icon i/git-compare-arrows
                        :on-click on-compare}
            (tr "workspace.branches.compare")]
           [:> button* {:variant "secondary"
                        :icon i/document
                        :on-click on-materialize}
            (tr "workspace.branches.materialize.action")]
           [:> button* {:variant "primary"
                        :icon i/history
                        :on-click on-restore}
            (tr "workspace.branches.menu.restore")]]

          :else
          (let [conflicts (or (:conflicts ctx) 0)]
            [:div {:class (stl/css :branch-banner-actions)}
             [:> button* {:variant "secondary"
                          :icon i/git-compare-arrows
                          :on-click on-compare}
              (tr "workspace.branches.compare")]

             (cond
               (pos? conflicts)
               [:> button* {:variant "primary"
                            :icon i/triangle-alert
                            :on-click on-resolve}
                (tr "workspace.branches.conflicts.resolve")]

               (pos? (or (:behind ctx) 0))
               [:> button* {:variant "primary"
                            :icon i/status-update
                            :on-click on-update}
                (tr "workspace.branches.update")]

               ;; with pull requests enabled the primary flow is asking
               ;; for a review (merging moves behind the kebab menu)
               (and prs-enabled? (some? open-pr))
               [:> button* {:variant "primary"
                            :icon i/git-pull-request-arrow
                            :on-click on-open-review}
                (tr "workspace.pull-requests.actions.open-review")]

               (and prs-enabled? (pos? (or (:ahead ctx) 0)))
               [:> button* {:variant "primary"
                            :icon i/git-pull-request-arrow
                            :on-click on-request-review}
                (tr "workspace.pull-requests.menu.request-review")]

               (pos? (or (:ahead ctx) 0))
               [:> button* {:variant "primary"
                            :icon i/git-merge
                            :on-click on-merge}
                (tr "workspace.branches.merge.action")])

             (when prs-enabled?
               ;; the banner sits at the bottom of the canvas: its menu
               ;; opens upwards
               [:div {:class (stl/css :branch-banner-menu)}
                [:> menu* {:is-open menu-open?
                           :on-open-change on-menu-change
                           :placement "top end"
                           :trigger
                           (mf/html
                            [:> icon-button* {:variant "ghost"
                                              :icon i/menu
                                              :aria-label (tr "labels.options")
                                              :aria-pressed menu-open?
                                              :on-click on-toggle-menu}])}
                 [:> menu-item* {:id "merge" :on-action on-merge}
                  (tr "workspace.pull-requests.actions.merge")]]])]))]])))
