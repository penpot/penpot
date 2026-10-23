;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.reviews
  "The reviews dialog: the merges into main, each with who made it, when
  and the comment left with it, and the design reviews (pull requests) of
  the file. It follows the all-branches dialog: tabs with their counts,
  a search box and one list per tab."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.pull-requests :as dwpr]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item*]]
   [app.main.ui.ds.layout.modal :refer [modal* modal-header* modal-content*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.product.branch-pill :refer [branch-pill*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.workspace.sidebar.pull-requests :as prui :refer [pr-state-badge*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private merges
  (l/derived :workspace-branch-merges st/state))

(defn- matches?
  "Whether any of `values` contains the search text, ignoring case. An
  empty search matches everything."
  [filter-v values]
  (or (str/blank? filter-v)
      (let [needle (str/lower filter-v)]
        (some #(str/includes? (str/lower (or % "")) needle) values))))

;; --- Shared pieces

(mf/defc clamped-text*
  "A text of any length shown on two lines at most. When it does not fit,
  a \"See more\" link shows the rest and \"See less\" folds it back. The
  link is a button of its own, so it never acts on the entry around it."
  {::mf/private true}
  [{:keys [text]}]
  (let [text-ref   (mf/use-ref nil)
        expanded*  (mf/use-state false)
        expanded?  (deref expanded*)
        overflow*  (mf/use-state false)
        overflow?  (deref overflow*)

        on-toggle
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! expanded* not)))]

    ;; measured only while folded: unfolded, the text always fits
    (mf/with-effect [text expanded?]
      (when-not expanded?
        (when-let [node (mf/ref-val text-ref)]
          (let [measure  #(reset! overflow* (> (.-scrollHeight node) (.-clientHeight node)))
                observer (js/ResizeObserver. measure)]
            (measure)
            (.observe observer node)
            #(.disconnect observer)))))

    [:div {:class (stl/css :clamped)}
     [:p {:ref text-ref
          :class (stl/css-case :clamped-text true
                               :is-folded (not expanded?))}
      text]
     (when (or overflow? expanded?)
       [:button {:type "button"
                 :class (stl/css :see-more)
                 :aria-expanded expanded?
                 :on-click on-toggle}
        (if expanded?
          (tr "workspace.branches.reviews.see-less")
          (tr "workspace.branches.reviews.see-more"))])]))

(mf/defc entry-date*
  "When something happened, as time ago, with the full date on hover."
  {::mf/private true}
  [{:keys [date]}]
  (when date
    (let [date (ct/inst date)]
      [:time {:class (stl/css :entry-date)
              :date-time (ct/format-inst date :iso)
              :title (ct/format-inst date :localized-date-time)}
       (ct/timeago date)])))

(mf/defc route*
  "The branch the changes come from and main, where they land."
  {::mf/private true}
  [{:keys [branch-name is-review]}]
  [:div {:class (stl/css :entry-route)}
   [:> branch-pill* {:label (or branch-name "—")
                     :is-review is-review}]
   [:> i/icon* {:icon-id i/arrow-right
                :size "s"
                :class (stl/css :entry-route-arrow)}]
   [:> branch-pill* {:label (tr "workspace.branches.main")
                     :is-main true}]])

(mf/defc author*
  "The profile behind an entry: avatar and name. A profile that left the
  team is no longer among the team's profiles and shows as unknown."
  {::mf/private true}
  [{:keys [profile]}]
  (if profile
    [:> avatar* {:profile profile
                 :variant "M"
                 :class (stl/css :entry-avatar)}]
    [:div {:class (stl/css :entry-avatar :entry-avatar-unknown)}
     [:> i/icon* {:icon-id i/user :size "s"}]]))

;; --- Merge reviews

(mf/defc merge-entry*
  {::mf/private true}
  [{:keys [merge profiles]}]
  (let [author (get profiles (:merged-by merge))]
    [:li {:class (stl/css :entry)}
     [:> author* {:profile author}]
     [:div {:class (stl/css :entry-body)}
      [:div {:class (stl/css :entry-head)}
       [:span {:class (stl/css :entry-title)}
        (or (:fullname author) (tr "workspace.branches.reviews.unknown-author"))]
       [:> entry-date* {:date (:created-at merge)}]]
      [:div {:class (stl/css :entry-meta)}
       [:span {:class (stl/css :entry-meta-text)}
        (tr "workspace.branches.reviews.merged")]
       [:> route* {:branch-name (:branch-name merge)}]]
      ;; the merge accepted a design review: name it, it may be gone by now
      (when-let [title (:pull-request-title merge)]
        [:div {:class (stl/css :entry-review)}
         [:> i/icon* {:icon-id i/git-pull-request-arrow :size "s"}]
         [:span {:class (stl/css :entry-review-title)}
          (tr "workspace.branches.reviews.from-review" title)]])
      (if (str/blank? (:comment merge))
        [:p {:class (stl/css :entry-no-comment)}
         (tr "workspace.branches.reviews.no-comment")]
        [:> clamped-text* {:text (:comment merge)}])]]))

;; --- Design reviews

(mf/defc design-review-entry*
  "A design review: the entry opens it while it is open; a finished one
  (merged or closed) can no longer be opened, so the entry shows its
  details instead. Its options menu shows the details of every review and
  reopens a closed one for its author or a team admin, as the details
  dialog does."
  {::mf/private true}
  [{:keys [pr profiles]}]
  (let [author  (get profiles (:created-by pr))
        profile (mf/deref refs/profile)
        team    (mf/deref refs/team)
        open?   (= "open" (:status pr))
        closed? (= "closed" (:status pr))
        can-reopen? (and closed?
                         (or (= (:created-by pr) (:id profile))
                             (boolean (get-in team [:permissions :is-admin]))))
        [approvals total] (prui/current-approvals pr)

        ;; each entry keeps its own menu state: the DS menu closes every
        ;; other open menu by itself when one opens
        menu-open*     (mf/use-state false)
        menu-open?     (deref menu-open*)
        on-toggle-menu (mf/use-fn #(swap! menu-open* not))
        on-menu-change (mf/use-fn #(reset! menu-open* %))

        on-menu-action
        (mf/use-fn
         (mf/deps pr)
         (fn [action]
           (case action
             "details" (modal/show! :pull-request-info {:pr pr})
             "reopen"  (st/emit! (dwpr/reopen-pull-request (:id pr)))
             nil)))

        on-open
        (mf/use-fn
         (mf/deps pr open?)
         (fn [_]
           (if open?
             (st/emit! (modal/hide)
                       (dwpr/open-pull-request pr))
             (modal/show! :pull-request-info {:pr pr}))))

        on-key-down
        (mf/use-fn
         (mf/deps on-open)
         (fn [event]
           (when (or (kbd/enter? event) (kbd/space? event))
             (dom/prevent-default event)
             (on-open event))))]

    [:li {:class (stl/css :entry :is-clickable)
          :role "button"
          :tab-index 0
          :title (if open?
                   (tr "workspace.pull-requests.actions.open-review")
                   (tr "workspace.pull-requests.menu.review-details"))
          :on-click on-open
          :on-key-down on-key-down}
     [:> author* {:profile author}]
     [:div {:class (stl/css :entry-body)}
      [:div {:class (stl/css :entry-head)}
       [:span {:class (stl/css :entry-title)} (:title pr)]
       [:> pr-state-badge* {:pr pr}]
       [:> entry-date* {:date (:created-at pr)}]]
      [:div {:class (stl/css :entry-meta)}
       [:span {:class (stl/css :entry-meta-text)}
        (or (:fullname author) (tr "workspace.branches.reviews.unknown-author"))]
       [:> route* {:branch-name (:branch-name pr) :is-review open?}]
       (when (pos? total)
         [:span {:class (stl/css :entry-approvals)
                 :title (tr "workspace.pull-requests.info.approvals")}
          [:> i/icon* {:icon-id i/tick :size "s"}]
          (dm/str approvals "/" total)])]
      (when-not (str/blank? (:description pr))
        [:> clamped-text* {:text (:description pr)}])]
     ;; the entry opens the review on click and on Enter: neither the
     ;; menu, its trigger nor its items (portaled, but still in this
     ;; React tree) may reach it
     [:div {:class (stl/css :entry-aside)
            :on-click dom/stop-propagation
            :on-key-down dom/stop-propagation}
      [:> menu* {:is-open menu-open?
                 :on-open-change on-menu-change
                 :on-action on-menu-action
                 :placement "bottom end"
                 :trigger
                 (mf/html
                  [:> icon-button* {:variant "ghost"
                                    :icon i/menu
                                    :aria-label (tr "labels.options")
                                    :aria-pressed menu-open?
                                    :on-click on-toggle-menu}])}
       [:> menu-item* {:id "details"}
        (tr "workspace.pull-requests.menu.review-details")]
       (when can-reopen?
         [:> menu-item* {:id "reopen"}
          (tr "workspace.pull-requests.actions.reopen")])]]]))

;; --- Dialog

(mf/defc list-state*
  "What a tab shows while its list is not there: loading, a failure with
  a retry, or an empty list."
  {::mf/private true}
  [{:keys [status icon empty-text on-retry]}]
  (case status
    :loading
    [:div {:class (stl/css :reviews-empty) :role "status"}
     [:> empty-state* {:icon i/git-pull-request-arrow
                       :text (tr "workspace.branches.reviews.loading")}]]

    :error
    [:div {:class (stl/css :reviews-empty) :role "alert"}
     [:> empty-state* {:icon i/triangle-alert
                       :text (tr "workspace.branches.reviews.error")}]
     [:> button* {:variant "secondary"
                  :icon i/reload
                  :on-click on-retry}
      (tr "labels.retry")]]

    [:div {:class (stl/css :reviews-empty)}
     [:> empty-state* {:icon icon :text empty-text}]]))

(mf/defc reviews-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-reviews}
  []
  (let [merges-state (mf/deref merges)
        prs-state    (mf/deref refs/pull-requests)
        profiles     (mf/deref refs/profiles)
        prs-enabled? (dwpr/enabled?)

        tab*      (mf/use-state "merges")
        tab       (deref tab*)
        filter*   (mf/use-state "")
        filter-v  (deref filter*)

        all-merges (:data merges-state)
        all-prs    (when prs-enabled? (:data prs-state))

        ;; the search reads what each entry shows: the branch, the
        ;; people and the texts
        shown-merges
        (mf/with-memo [all-merges filter-v profiles]
          (filterv #(matches? filter-v [(:branch-name %)
                                        (:comment %)
                                        (:pull-request-title %)
                                        (get-in profiles [(:merged-by %) :fullname])])
                   all-merges))

        shown-prs
        (mf/with-memo [all-prs filter-v profiles]
          (filterv #(matches? filter-v [(:title %)
                                        (:description %)
                                        (:branch-name %)
                                        (get-in profiles [(:created-by %) :fullname])])
                   all-prs))

        tabs
        (mf/with-memo [all-merges all-prs prs-enabled?]
          (cond-> [{:id "merges"
                    :label (dm/str (tr "workspace.branches.reviews.tab.merges")
                                   " (" (count all-merges) ")")}]
            prs-enabled?
            (conj {:id "design"
                   :label (dm/str (tr "workspace.branches.reviews.tab.design")
                                  " (" (count all-prs) ")")})))

        on-tab-change    (mf/use-fn #(reset! tab* %))
        on-filter-change (mf/use-fn #(reset! filter* (dom/get-target-val %)))

        ;; the input's wrapper takes the full width it is given: this
        ;; box sets that width, so the tabs keep room for their labels
        search
        (mf/html
         [:div {:class (stl/css :reviews-search)}
          [:> input* {:variant "dense"
                      :icon i/search
                      :placeholder (tr "workspace.branches.reviews.search")
                      :value filter-v
                      :on-change on-filter-change}]])

        on-open-change
        (mf/use-fn
         (fn [open?]
           (when-not open?
             (st/emit! (modal/hide)))))

        on-retry-merges
        (mf/use-fn #(st/emit! (dwb/fetch-branch-merges)))]

    ;; before paint, so a list from an earlier opening never shows in
    ;; place of the loading state
    (mf/with-layout-effect []
      (st/emit! (dwb/fetch-branch-merges))
      (when (dwpr/enabled?)
        (st/emit! (dwpr/fetch-pull-requests))))

    [:> modal* {:is-open true
                :on-open-change on-open-change
                :size "large"}
     [:> modal-header* {:title (tr "workspace.branches.reviews.title")}]
     [:> modal-content* {:class (stl/css :reviews-content)}
      [:> tab-switcher* {:tabs tabs
                         :selected tab
                         :on-change on-tab-change
                         :scrollable-panel true
                         :action-button-position "end"
                         :action-button search
                         :class (stl/css :reviews-tabs)}
       (if (= tab "design")
         (cond
           (= :loading (:status prs-state))
           [:> list-state* {:status :loading}]

           (empty? shown-prs)
           [:> list-state* {:icon i/git-pull-request-arrow
                            :empty-text (if (seq all-prs)
                                          (tr "workspace.branches.reviews.no-results")
                                          (tr "workspace.branches.reviews.design.empty"))}]

           :else
           [:ul {:class (stl/css :entries)}
            (for [pr shown-prs]
              [:> design-review-entry* {:key (dm/str (:id pr))
                                        :pr pr
                                        :profiles profiles}])])

         (cond
           (contains? #{:loading :error} (:status merges-state))
           [:> list-state* {:status (:status merges-state)
                            :on-retry on-retry-merges}]

           (empty? shown-merges)
           [:> list-state* {:icon i/git-merge
                            :empty-text (if (seq all-merges)
                                          (tr "workspace.branches.reviews.no-results")
                                          (tr "workspace.branches.reviews.merges.empty"))}]

           :else
           [:ul {:class (stl/css :entries)}
            (for [merge shown-merges]
              [:> merge-entry* {:key (dm/str (:id merge))
                                :merge merge
                                :profiles profiles}])]))]]]))
