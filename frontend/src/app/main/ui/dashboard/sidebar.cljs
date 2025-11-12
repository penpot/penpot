;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.components.link :refer [link]]
   [app.main.ui.dashboard.comments :refer [comments-icon* comments-section]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu*]]
   [app.main.ui.dashboard.subscription :refer [subscription-sidebar*
                                               menu-team-icon*
                                               dashboard-cta*
                                               show-subscription-dashboard-banner?
                                               get-subscription-type]]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.functions :as f]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private clear-search-icon
  (deprecated-icon/icon-xref :delete-text (stl/css :clear-search-icon)))

(def ^:private search-icon
  (deprecated-icon/icon-xref :search (stl/css :search-icon)))

(def ^:private tick-icon
  (deprecated-icon/icon-xref :tick (stl/css :tick-icon)))

(def ^:private logo-icon
  (deprecated-icon/icon-xref :logo (stl/css :logo-icon)))

(def ^:private add-icon
  (deprecated-icon/icon-xref :add (stl/css :add-icon)))

(def ^:private arrow-icon
  (deprecated-icon/icon-xref :arrow (stl/css :arrow-icon)))

(def ^:private menu-icon
  (deprecated-icon/icon-xref :menu (stl/css :menu-icon)))

(def ^:private pin-icon
  (deprecated-icon/icon-xref :pin (stl/css :pin-icon)))

(def ^:private exit-icon
  (deprecated-icon/icon-xref :exit (stl/css :exit-icon)))

(mf/defc sidebar-project*
  {::mf/private true}
  [{:keys [item is-selected]}]
  (let [dstate           (mf/deref refs/dashboard-local)
        selected-files   (:selected-files dstate)
        selected-project (:selected-project dstate)
        edit-id          (:project-for-edit dstate)

        local*           (mf/use-state
                          #(do {:menu-open false
                                :menu-pos nil
                                :edition? (= (:id item) edit-id)
                                :dragging? false}))

        local            (deref local*)

        project-id       (get item :id)

        on-click
        (mf/use-fn
         (mf/deps project-id)
         (fn []
           (st/emit! (dcm/go-to-dashboard-files :project-id project-id))))

        on-key-down
        (mf/use-fn
         (mf/deps project-id)
         (fn [event]
           (when (kbd/enter? event)
             (st/emit!
              (dcm/go-to-dashboard-files :project-id project-id)
              (ts/schedule-on-idle
               (fn []
                 (when-let [title (dom/get-element (str project-id))]
                   (dom/set-attribute! title "tabindex" "0")
                   (dom/focus! title)
                   (dom/set-attribute! title "tabindex" "-1"))))))))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (let [position (dom/get-client-position event)]
             (dom/prevent-default event)
             (swap! local* assoc
                    :menu-open true
                    :menu-pos position))))

        on-menu-close
        (mf/use-fn #(swap! local* assoc :menu-open false))

        on-edit-open
        (mf/use-fn #(swap! local* assoc :edition? true))

        on-edit
        (mf/use-fn
         (mf/deps item)
         (fn [name]
           (when-not (str/blank? name)
             (st/emit! (-> (dd/rename-project (assoc item :name name))
                           (with-meta {::ev/origin "dashboard:sidebar"}))))
           (swap! local* assoc :edition? false)))

        on-drag-enter
        (mf/use-fn
         (mf/deps selected-project)
         (fn [e]
           (when (dnd/has-type? e "penpot/files")
             (dom/prevent-default e)
             (when-not (dnd/from-child? e)
               (when (not= selected-project (:id item))
                 (swap! local* assoc :dragging? true))))))

        on-drag-over
        (mf/use-fn
         (fn [e]
           (when (dnd/has-type? e "penpot/files")
             (dom/prevent-default e))))

        on-drag-leave
        (mf/use-fn
         (fn [e]
           (when-not (dnd/from-child? e)
             (swap! local* assoc :dragging? false))))

        on-drop-success
        (mf/use-fn
         (mf/deps project-id)
         (fn [_]
           (st/emit! (dcm/go-to-dashboard-files :project-id project-id)
                     (ntf/success (tr "dashboard.success-move-file")))))

        on-drop
        (mf/use-fn
         (mf/deps item selected-files)
         (fn [_]
           (swap! local* assoc :dragging? false)
           (when (not= selected-project (:id item))
             (let [data  {:ids selected-files
                          :project-id (:id item)}
                   mdata {:on-success on-drop-success}]
               (st/emit! (dd/move-files (with-meta data mdata)))))))]

    [:*
     [:li {:tab-index "0"
           :class (stl/css-case :project-element true
                                :sidebar-nav-item true
                                :current is-selected
                                :dragging (:dragging? local))
           :on-click on-click
           :on-key-down on-key-down
           :on-double-click on-edit-open
           :on-context-menu on-menu-click
           :on-drag-enter on-drag-enter
           :on-drag-over on-drag-over
           :on-drag-leave on-drag-leave
           :on-drop on-drop}
      (if (:edition? local)
        [:& inline-edition {:content (:name item)
                            :on-end on-edit}]
        [:span {:class (stl/css :element-title)} (:name item)])]
     [:> project-menu* {:project item
                        :show (:menu-open local)
                        :left (:x (:menu-pos local))
                        :top (:y (:menu-pos local))
                        :on-edit on-edit-open
                        :on-close on-menu-close}]]))

(mf/defc sidebar-search*
  {::mf/private true}
  [{:keys [search-term team-id]}]
  (let [search-term (d/nilv search-term "")
        focused?    (mf/use-state false)
        emit!       (mf/use-memo #(f/debounce st/emit! 500))

        on-search-blur
        (mf/use-fn
         (fn [_]
           (reset! focused? false)))

        on-search-change
        (mf/use-fn
         (fn [event]
           (let [value (dom/get-target-val event)]
             (emit! (dcm/go-to-dashboard-search :term value)))))

        on-clear-click
        (mf/use-fn
         (mf/deps team-id)
         (fn [e]
           (emit! (dcm/go-to-dashboard-search))
           (let [search-input (dom/get-element "search-input")]
             (dom/clean-value! search-input)
             (dom/focus! search-input)
             (dom/prevent-default e)
             (dom/stop-propagation e))))

        on-key-press
        (mf/use-fn
         (fn [e]
           (when (kbd/enter? e)
             (ts/schedule-on-idle
              (fn []
                (let [search-title (dom/get-element (str "dashboard-search-title"))]
                  (when search-title
                    (dom/set-attribute! search-title "tabindex" "0")
                    (dom/focus! search-title)
                    (dom/set-attribute! search-title "tabindex" "-1")))))
             (dom/prevent-default e)
             (dom/stop-propagation e))))

        handle-clear-search
        (mf/use-fn
         (mf/deps on-clear-click)
         (fn [event]
           (when (kbd/enter? event)
             (on-clear-click event))))]

    [:form {:class (stl/css :sidebar-search)}
     [:input {:class (stl/css :input-text)
              :key "images-search-box"
              :id "search-input"
              :type "text"
              :aria-label (tr "dashboard.search-placeholder")
              :placeholder (tr "dashboard.search-placeholder")
              :default-value search-term
              :auto-complete "off"
              ;;  :on-focus on-search-focus
              :on-blur on-search-blur
              :on-change on-search-change
              :on-key-press on-key-press
              :ref #(when % (set! (.-value %) search-term))}]

     (if (or @focused? (seq search-term))
       [:button {:class (stl/css :search-btn :clear-search-btn)
                 :tab-index "0"
                 :aria-label "dashboard-clear-search"
                 :on-click on-clear-click
                 :on-key-down handle-clear-search}
        clear-search-icon]

       [:button {:class (stl/css :search-btn)
                 :aria-label "dashboard-search"
                 :on-click on-clear-click}
        search-icon])]))

(mf/defc teams-selector-dropdown*
  {::mf/private true}
  [{:keys [team profile teams] :rest props}]
  (let [on-create-click
        (mf/use-fn #(st/emit! (modal/show :team-form {})))

        on-team-click
        (mf/use-fn
         (fn [event]
           (let [team-id (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (uuid/parse))]
             (st/emit! (dcm/go-to-dashboard-recent :team-id team-id)))))]

    [:> dropdown-menu* props

     [:> dropdown-menu-item* {:on-click    on-team-click
                              :data-value  (:default-team-id profile)
                              :class       (stl/css :team-dropdown-item)}
      [:span {:class (stl/css :penpot-icon)} deprecated-icon/logo-icon]

      [:span {:class (stl/css :team-text)} (tr "dashboard.your-penpot")]
      (when (= (:default-team-id profile) (:id team))
        tick-icon)]

     (for [team-item (remove :is-default (vals teams))]
       [:> dropdown-menu-item* {:on-click    on-team-click
                                :data-value  (:id team-item)
                                :class       (stl/css :team-dropdown-item)
                                :key         (str (:id team-item))}
        [:img {:src (cf/resolve-team-photo-url team-item)
               :class (stl/css :team-picture)
               :alt (:name team-item)}]

        (if (and (contains? cf/flags :subscriptions)
                 (#{"unlimited" "enterprise"} (get-subscription-type (:subscription team-item))))
          [:div  {:class (stl/css :team-text-with-icon)}
           [:span {:class (stl/css :team-text) :title (:name team-item)} (:name team-item)]
           [:> menu-team-icon* {:subscription-type (get-subscription-type (:subscription team-item))}]]
          [:span {:class (stl/css :team-text)
                  :title (:name team-item)} (:name team-item)])
        (when (= (:id team-item) (:id team))
          tick-icon)])

     [:hr {:role "separator" :class (stl/css :team-separator)}]
     [:> dropdown-menu-item* {:on-click    on-create-click
                              :class       (stl/css :team-dropdown-item :action)}
      [:span {:class (stl/css :icon-wrapper)} add-icon]
      [:span {:class (stl/css :team-text)} (tr "dashboard.create-new-team")]]]))

(mf/defc team-options-dropdown*
  {::mf/private true}
  [{:keys [team profile] :rest props}]
  (let [go-members     #(st/emit! (dcm/go-to-dashboard-members))
        go-invitations #(st/emit! (dcm/go-to-dashboard-invitations))
        go-webhooks    #(st/emit! (dcm/go-to-dashboard-webhooks))
        go-settings    #(st/emit! (dcm/go-to-dashboard-settings))

        members        (get team :members)
        permissions    (get team :permissions)
        can-rename?    (or (:is-owner permissions)
                           (:is-admin permissions))

        on-success
        (fn []
          ;; FIXME: this should be handled in the event, not here
          (let [team-id (:default-team-id profile)]
            (rx/of (dcm/go-to-dashboard-recent :team-id team-id)
                   (modal/hide))))

        on-error
        (fn [{:keys [code] :as error}]
          (condp = code
            :no-enough-members-for-leave
            (rx/of (ntf/error (tr "errors.team-leave.insufficient-members")))

            :member-does-not-exist
            (rx/of (ntf/error (tr "errors.team-leave.member-does-not-exists")))

            :owner-cant-leave-team
            (rx/of (ntf/error (tr "errors.team-leave.owner-cant-leave")))

            (rx/throw error)))

        leave-fn
        (mf/use-fn
         (mf/deps on-success on-error)
         (fn [member-id]
           (let [params (cond-> {} (uuid? member-id) (assoc :reassign-to member-id))]
             (st/emit! (dtm/leave-current-team (with-meta params
                                                 {:on-success on-success
                                                  :on-error on-error}))))))
        delete-fn
        (mf/use-fn
         (mf/deps team on-success on-error)
         (fn []
           (st/emit! (dtm/delete-team (with-meta team {:on-success on-success
                                                       :on-error on-error})))))
        on-rename-clicked
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit! (modal/show :team-form {:team team}))))

        on-leave-clicked
        (mf/use-fn
         (mf/deps leave-fn)
         #(st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.leave-confirm.title")
                      :message (tr "modals.leave-confirm.message")
                      :accept-label (tr "modals.leave-confirm.accept")
                      :on-accept leave-fn})))

        on-leave-as-owner-clicked
        (mf/use-fn
         (mf/deps team profile leave-fn)
         (fn []
           (st/emit! (dtm/fetch-members)
                     (modal/show
                      {:type :leave-and-reassign
                       :profile profile
                       :team team
                       :accept leave-fn}))))

        leave-and-close
        (mf/use-fn
         (mf/deps team delete-fn)
         #(st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.leave-confirm.title")
                      :message  (tr "modals.leave-and-close-confirm.message" (:name team))
                      :scd-message (tr "modals.leave-and-close-confirm.hint")
                      :accept-label (tr "modals.leave-confirm.accept")
                      :on-accept delete-fn})))

        on-delete-clicked
        (mf/use-fn
         (mf/deps delete-fn)
         #(st/emit!
           (modal/show
            {:type :confirm
             :title (tr "modals.delete-team-confirm.title")
             :message (tr "modals.delete-team-confirm.message")
             :accept-label (tr "modals.delete-team-confirm.accept")
             :on-accept delete-fn})))]
    [:> dropdown-menu* props

     [:> dropdown-menu-item* {:on-click    go-members
                              :class       (stl/css :team-options-item)
                              :data-testid "team-members"}
      (tr "labels.members")]
     [:> dropdown-menu-item* {:on-click    go-invitations
                              :class       (stl/css :team-options-item)
                              :data-testid "team-invitations"}
      (tr "labels.invitations")]

     (when (contains? cf/flags :webhooks)
       [:> dropdown-menu-item* {:on-click go-webhooks
                                :class    (stl/css :team-options-item)}
        (tr "labels.webhooks")])

     [:> dropdown-menu-item* {:on-click    go-settings
                              :class       (stl/css :team-options-item)
                              :data-testid "team-settings"}
      (tr "labels.settings")]

     [:hr {:class (stl/css :team-option-separator)}]
     (when can-rename?
       [:> dropdown-menu-item* {:on-click    on-rename-clicked
                                :class       (stl/css :team-options-item)
                                :data-testid "rename-team"}
        (tr "labels.rename")])

     (cond
       (= (count members) 1)
       [:> dropdown-menu-item* {:on-click leave-and-close
                                :class    (stl/css :team-options-item)}
        (tr "dashboard.leave-team")]


       (get-in team [:permissions :is-owner])
       [:> dropdown-menu-item* {:on-click    on-leave-as-owner-clicked
                                :class       (stl/css :team-options-item)
                                :data-testid  "leave-team"}
        (tr "dashboard.leave-team")]

       (> (count members) 1)
       [:> dropdown-menu-item* {:on-click on-leave-clicked
                                :class    (stl/css :team-options-item)}
        (tr "dashboard.leave-team")])

     (when (get-in team [:permissions :is-owner])
       [:> dropdown-menu-item* {:on-click    on-delete-clicked
                                :class       (stl/css :team-options-item :warning)
                                :data-testid "delete-team"}
        (tr "dashboard.delete-team")])]))

(mf/defc sidebar-team-switch*
  [{:keys [team profile]}]
  (let [teams (mf/deref refs/teams)

        subscription
        (get team :subscription)

        subscription-type
        (get-subscription-type subscription)

        show-team-options-menu*
        (mf/use-state false)

        show-team-options-menu?
        (deref show-team-options-menu*)

        show-teams-menu*
        (mf/use-state false)

        show-teams-menu?
        (deref show-teams-menu*)

        on-show-teams-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-teams-menu* not)))

        on-show-teams-keydown
        (mf/use-fn
         (fn [event]
           (when (or (kbd/space? event)
                     (kbd/enter? event))
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (some-> (dom/get-current-target event)
                     (dom/click!)))))

        close-team-options-menu
        (mf/use-fn #(reset! show-team-options-menu* false))

        on-show-options-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-team-options-menu* not)))

        on-show-options-keydown
        (mf/use-fn
         (fn [event]
           (when (or (kbd/space? event)
                     (kbd/enter? event))
             (dom/prevent-default event)
             (dom/stop-propagation event)

             (some-> (dom/get-current-target event)
                     (dom/click!)))))

        close-teams-menu
        (mf/use-fn #(reset! show-teams-menu* false))]

    [:div {:class (stl/css :sidebar-team-switch)}
     [:div {:class (stl/css :switch-content)}
      [:button {:class (stl/css :current-team)
                :on-click on-show-teams-click
                :on-key-down on-show-teams-keydown}
       (cond
         (:is-default team)
         [:div {:class (stl/css :team-name)}
          [:span {:class (stl/css :penpot-icon)} deprecated-icon/logo-icon]
          [:span {:class (stl/css :team-text)} (tr "dashboard.default-team-name")]]

         (and (contains? cf/flags :subscriptions)
              (not (:is-default team))
              (or (= "unlimited" subscription-type) (= "enterprise" subscription-type)))
         [:div {:class (stl/css :team-name)}
          [:img {:src (cf/resolve-team-photo-url team)
                 :class (stl/css :team-picture)
                 :alt (:name team)}]
          [:div  {:class (stl/css :team-text-with-icon)}
           [:span {:class (stl/css :team-text) :title (:name team)} (:name team)]
           [:> menu-team-icon* {:subscription-type subscription-type}]]]


         (and (not (:is-default team))
              (or (not= "unlimited" subscription-type) (not= "enterprise" subscription-type)))
         [:div {:class (stl/css :team-name)}
          [:img {:src (cf/resolve-team-photo-url team)
                 :class (stl/css :team-picture)
                 :alt (:name team)}]
          [:span {:class (stl/css :team-text) :title (:name team)} (:name team)]])

       arrow-icon]

      (when-not (:is-default team)
        [:button {:class (stl/css :switch-options)
                  :on-click on-show-options-click
                  :aria-label "team-management"
                  :tab-index "0"
                  :on-key-down on-show-options-keydown}
         menu-icon])]

     ;; Teams Dropdown

     [:> teams-selector-dropdown* {:show show-teams-menu?
                                   :on-close close-teams-menu
                                   :id "team-list"
                                   :class (stl/css :dropdown :teams-dropdown)
                                   :team team
                                   :profile profile
                                   :teams teams}]

     [:> team-options-dropdown* {:show show-team-options-menu?
                                 :on-close close-team-options-menu
                                 :id "team-options"
                                 :class (stl/css :dropdown :options-dropdown)
                                 :team team
                                 :profile profile}]]))

(mf/defc sidebar-content*
  {::mf/private true}
  [{:keys [projects profile section team project search-term default-project] :as props}]
  (let [default-project-id
        (get default-project :id)

        team-id     (get team :id)

        projects?   (= section :dashboard-recent)
        fonts?      (= section :dashboard-fonts)
        libs?       (= section :dashboard-libraries)
        drafts?     (and (= section :dashboard-files)
                         (= (:id project) default-project-id))
        container   (mf/use-ref nil)

        overflow*   (mf/use-state false)
        overflow?   (deref overflow*)

        go-projects
        (mf/use-fn #(st/emit! (dcm/go-to-dashboard-recent)))

        go-projects-with-key
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit!
            (dcm/go-to-dashboard-recent :team-id team-id)
            (ts/schedule-on-idle
             (fn []
               (when-let [projects-title (dom/get-element "dashboard-projects-title")]
                 (dom/set-attribute! projects-title "tabindex" "0")
                 (dom/focus! projects-title)
                 (dom/set-attribute! projects-title "tabindex" "-1")))))))

        go-fonts
        (mf/use-fn
         (mf/deps team-id)
         #(st/emit! (dcm/go-to-dashboard-fonts :team-id team-id)))

        go-fonts-with-key
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit!
            (dcm/go-to-dashboard-fonts :team-id team-id)
            (ts/schedule-on-idle
             (fn []
               (let [font-title (dom/get-element "dashboard-fonts-title")]
                 (when font-title
                   (dom/set-attribute! font-title "tabindex" "0")
                   (dom/focus! font-title)
                   (dom/set-attribute! font-title "tabindex" "-1"))))))))

        go-drafts
        (mf/use-fn
         (mf/deps team-id default-project-id)
         (fn []
           (st/emit! (dcm/go-to-dashboard-files :team-id team-id :project-id default-project-id))))

        go-drafts-with-key
        (mf/use-fn
         (mf/deps team-id default-project-id)
         (fn []
           (st/emit! (dcm/go-to-dashboard-files :team-id team-id :project-id default-project-id))
           (ts/schedule-on-idle
            (fn []
              (when-let [title (dom/get-element "dashboard-drafts-title")]
                (dom/set-attribute! title "tabindex" "0")
                (dom/focus! title)
                (dom/set-attribute! title "tabindex" "-1"))))))

        go-libs
        (mf/use-fn
         (mf/deps team-id)
         (fn [] (st/emit! (dcm/go-to-dashboard-libraries :team-id team-id))))

        go-libs-with-key
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit!
            (dcm/go-to-dashboard-libraries :team-id team-id)
            (ts/schedule-on-idle
             (fn []
               (let [libs-title (dom/get-element "dashboard-libraries-title")]
                 (when libs-title
                   (dom/set-attribute! libs-title "tabindex" "0")
                   (dom/focus! libs-title)
                   (dom/set-attribute! libs-title "tabindex" "-1"))))))))

        pinned-projects
        (mf/with-memo [projects]
          (->> projects
               (remove :deleted-at)
               (remove :is-default)
               (filter :is-pinned)
               (sort-by :name)
               (not-empty)))]

    (mf/with-layout-effect [pinned-projects]
      (let [node          (mf/ref-val container)
            client-height (.-clientHeight ^js node)
            scroll-height (.-scrollHeight ^js node)]
        (reset! overflow* (> scroll-height client-height))))

    [:*
     [:div {:class (stl/css-case :sidebar-content true)
            :ref container}
      [:> sidebar-team-switch* {:team team :profile profile}]

      [:> sidebar-search* {:search-term search-term
                           :team-id (:id team)}]

      [:div {:class (stl/css :sidebar-content-section)}
       [:ul {:class (stl/css :sidebar-nav)}
        [:li {:class (stl/css-case :recent-projects true
                                   :sidebar-nav-item true
                                   :current projects?)}
         [:& link {:action go-projects
                   :class (stl/css :sidebar-link)
                   :keyboard-action go-projects-with-key}
          [:span {:class (stl/css :element-title)} (tr "labels.projects")]]]

        [:li {:class (stl/css-case :current drafts?
                                   :sidebar-nav-item true)}
         [:& link {:action go-drafts
                   :class (stl/css :sidebar-link)
                   :keyboard-action go-drafts-with-key}
          [:span {:class (stl/css :element-title)} (tr "labels.drafts")]]]]]


      [:div {:class (stl/css :sidebar-content-section)}
       [:div {:class (stl/css :sidebar-section-title)}
        (tr "labels.sources")]
       [:ul {:class (stl/css :sidebar-nav)}
        [:li {:class (stl/css-case :sidebar-nav-item true
                                   :current fonts?)}
         [:& link {:action go-fonts
                   :class (stl/css :sidebar-link)
                   :keyboard-action go-fonts-with-key
                   :data-testid "fonts"}
          [:span {:class (stl/css :element-title)} (tr "labels.fonts")]]]
        [:li {:class (stl/css-case :current libs?
                                   :sidebar-nav-item true)}
         [:& link {:action go-libs
                   :data-testid "libs-link-sidebar"
                   :class (stl/css :sidebar-link)
                   :keyboard-action go-libs-with-key}
          [:span {:class (stl/css :element-title)} (tr "labels.shared-libraries")]]]]]


      [:div {:class (stl/css :sidebar-content-section)
             :data-testid "pinned-projects"}
       [:div {:class (stl/css :sidebar-section-title)}
        (tr "labels.pinned-projects")]
       (if (some? pinned-projects)
         [:ul {:class (stl/css :sidebar-nav :pinned-projects)}
          (for [item pinned-projects]
            [:> sidebar-project*
             {:item item
              :key (dm/str (:id item))
              :id (:id item)
              :team-id (:id team)
              :is-selected (= (:id item) (:id project))}])]
         [:div {:class (stl/css :sidebar-empty-placeholder)}
          pin-icon
          [:span {:class (stl/css :empty-text)} (tr "dashboard.no-projects-placeholder")]])]]
     [:div {:class (stl/css-case :separator true :overflow-separator overflow?)}]]))

(mf/defc help-learning-menu*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-close on-click]}]
  (let [handle-click-url
        (mf/use-fn
         (fn [event]
           (let [url       (-> (dom/get-current-target event)
                               (dom/get-data "url"))
                 eventname (-> (dom/get-current-target event)
                               (dom/get-data "eventname"))]
             (st/emit! (ptk/event ::ev/event {::ev/name eventname
                                              ::ev/origin "menu:in-app"}))
             (dom/open-new-window url))))

        handle-feedback-click
        (mf/use-fn #(on-click :settings-feedback %))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :sub-menu :help-learning)
                        :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :data-url "https://help.penpot.app"
                              :on-click handle-click-url
                              :data-eventname "explore-help-center-click"}
      (tr "labels.help-center")]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :data-url "https://penpot.app/learning-center"
                              :on-click handle-click-url
                              :data-eventname "explore-learning-center-click"}
      (tr "labels.learning-center")]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :data-url "https://penpot.app/penpothub"
                              :on-click handle-click-url
                              :data-eventname "explore-penpot-hub-click"}
      (tr "labels.penpot-hub")]

     (when (contains? cf/flags :user-feedback)
       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click handle-feedback-click}
        (tr "labels.give-feedback")])]))

(mf/defc community-contributions-menu*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-close]}]
  (let [handle-click-url
        (mf/use-fn
         (fn [event]
           (let [url       (-> (dom/get-current-target event)
                               (dom/get-data "url"))
                 eventname (-> (dom/get-current-target event)
                               (dom/get-data "eventname"))]
             (st/emit! (ptk/event ::ev/event {::ev/name eventname
                                              ::ev/origin "menu:in-app"}))
             (dom/open-new-window url))))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :sub-menu :community)
                        :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :data-url "https://github.com/penpot/penpot"
                              :on-click handle-click-url
                              :data-eventname "explore-github-repository-click"}
      (tr "labels.github-repo")]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :data-url "https://community.penpot.app"
                              :on-click handle-click-url
                              :data-eventname "explore-community-click"}
      (tr "labels.community")]]))

(mf/defc about-penpot-menu*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-close]}]
  (let [version cf/version
        show-release-notes
        (mf/use-fn
         (fn [event]
           (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version (:main version)}))
           (if (and (kbd/alt? event) (kbd/mod? event))
             (st/emit! (modal/show {:type :onboarding}))
             (st/emit! (modal/show {:type :release-notes :version (:main version)})))))

        handle-click-url
        (mf/use-fn
         (fn [event]
           (let [url       (-> (dom/get-current-target event)
                               (dom/get-data "url"))
                 eventname (-> (dom/get-current-target event)
                               (dom/get-data "eventname"))]
             (st/emit! (ptk/event ::ev/event {::ev/name eventname
                                              ::ev/origin "menu:in-app"}))
             (dom/open-new-window url))))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :sub-menu :about)
                        :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click show-release-notes}
      (tr "labels.version-notes" (:base version))]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :data-url "https://github.com/penpot/penpot/blob/develop/CHANGES.md"
                              :on-click handle-click-url
                              :data-eventname "explore-changelog-click"}
      (tr "labels.penpot-changelog")]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :data-url "https://penpot.app/terms"
                              :on-click handle-click-url
                              :data-eventname "explore-terms-service-click"}
      (tr "auth.terms-of-service")]]))

(mf/defc profile-section*
  [{:keys [profile team]}]
  (let [show-profile-menu* (mf/use-state false)
        show-profile-menu? (deref show-profile-menu*)
        sub-menu*      (mf/use-state false)
        sub-menu       (deref sub-menu*)
        version        (:base cf/version)

        close-sub-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! sub-menu* nil)))

        photo
        (cf/resolve-profile-photo-url profile)

        on-click
        (mf/use-fn
         (fn [section event]
           (dom/stop-propagation event)
           (reset! show-profile-menu* false)
           (if (keyword? section)
             (st/emit! (rt/nav section))
             (st/emit! section))))

        show-comments* (mf/use-state false)
        show-comments? @show-comments*

        handle-hide-comments
        (mf/use-fn
         (fn []
           (reset! show-comments* false)))

        handle-show-comments
        (mf/use-fn
         (fn []
           (reset! show-comments* true)))

        handle-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-profile-menu* not)))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (when (kbd/enter? event)
             (reset! show-profile-menu* true))))

        on-close
        (mf/use-fn #(reset! show-profile-menu* false))

        handle-logout-click
        (mf/use-fn
         #(on-click (da/logout) %))

        handle-set-profile
        (mf/use-fn
         #(on-click :settings-profile %))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [menu (-> (dom/get-current-target event)
                          (dom/get-data "testid")
                          (keyword))]
             (reset! sub-menu* menu))))

        on-power-up-click
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-pricing-click" ::ev/origin "dashboard" :section "sidebar"}))
           (dom/open-new-window "https://penpot.app/pricing")))]

    [:*
     (when (contains? cf/flags :subscriptions)
       (if (show-subscription-dashboard-banner? profile)
         [:> dashboard-cta* {:profile profile}]
         [:> subscription-sidebar* {:profile profile}]))

     ;; TODO remove this block when subscriptions is full implemented
     (when (contains? cf/flags :subscriptions-old)
       [:button {:class (stl/css :upgrade-plan-section)
                 :on-click on-power-up-click}
        [:div {:class (stl/css :penpot-free)}
         [:span (tr "dashboard.upgrade-plan.penpot-free")]
         [:span {:class (stl/css :no-limits)}
          (tr "dashboard.upgrade-plan.no-limits")]]
        [:div {:class (stl/css :power-up)}
         (tr "subscription.dashboard.upgrade-plan.power-up")]])

     (when (and team profile)
       [:& comments-section
        {:profile profile
         :team team
         :show? show-comments?
         :on-show-comments handle-show-comments
         :on-hide-comments handle-hide-comments}])

     [:div {:class (stl/css :profile-section)}
      [:button {:class (stl/css :profile)
                :tab-index "0"
                :on-click handle-click
                :on-key-down handle-key-down
                :data-testid "profile-btn"}
       [:img {:src photo
              :class (stl/css :profile-img)
              :alt (:fullname profile)}]
       [:span {:class (stl/css :profile-fullname)} (:fullname profile)]]

      [:> dropdown-menu* {:on-close on-close
                          :show show-profile-menu?
                          :id "profile-menu"
                          :class (stl/css :profile-dropdown)}
       [:> dropdown-menu-item* {:class (stl/css :profile-dropdown-item)
                                :on-click handle-set-profile
                                :data-testid "profile-profile-opt"}
        (tr "labels.your-account")]

       [:li {:class (stl/css :profile-separator)}]


       [:> dropdown-menu-item* {:class (stl/css-case :profile-dropdown-item true)
                                :on-click    on-menu-click
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (on-menu-click event)))
                                :on-pointer-enter on-menu-click
                                :data-testid "help-learning"
                                :id          "help-learning"}
        [:span {:class (stl/css :item-name)} (tr "labels.help-learning")]
        [:> icon* {:icon-id i/arrow :class (stl/css :open-arrow)}]]

       [:> dropdown-menu-item* {:class (stl/css-case :profile-dropdown-item true)
                                :on-click    on-menu-click
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (on-menu-click event)))
                                :on-pointer-enter on-menu-click
                                :data-testid "community-contributions"
                                :id          "community-contributions"}
        [:span {:class (stl/css :item-name)} (tr "labels.community-contributions")]
        [:> icon* {:icon-id i/arrow :class (stl/css :open-arrow)}]]

       [:> dropdown-menu-item* {:class (stl/css-case :profile-dropdown-item true)
                                :on-click    on-menu-click
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (on-menu-click event)))
                                :on-pointer-enter on-menu-click
                                :data-testid "about-penpot"
                                :id          "about-penpot"}

        [:div {:class (stl/css :about-penpot)}
         [:span {:class (stl/css :item-name)} (tr "labels.about-penpot")]
         [:span {:class (stl/css :menu-version) :title version} version]]
        [:> icon* {:icon-id i/arrow :class (stl/css :open-arrow)}]]

       [:li {:class (stl/css :profile-separator)}]

       [:> dropdown-menu-item* {:class (stl/css :profile-dropdown-item :item-with-icon)
                                :on-click handle-logout-click
                                :data-testid "logout-profile-opt"}
        exit-icon
        (tr "labels.logout")]]

      (when (and team profile)
        [:> comments-icon*
         {:profile profile
          :on-show-comments handle-show-comments}])]

     (when show-profile-menu?
       (case sub-menu
         :help-learning
         [:> help-learning-menu* {:on-close close-sub-menu :on-click on-click}]

         :community-contributions
         [:> community-contributions-menu* {:on-close close-sub-menu}]

         :about-penpot
         [:> about-penpot-menu* {:on-close close-sub-menu}]
         nil))]))

(mf/defc sidebar*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [team profile] :as props}]
  [:nav {:class (stl/css :dashboard-sidebar) :data-testid "dashboard-sidebar"}
   [:> sidebar-content* props]
   [:> profile-section*
    {:profile profile
     :team team}]])

