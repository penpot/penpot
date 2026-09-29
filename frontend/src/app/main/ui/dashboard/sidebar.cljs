;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.dashboard.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
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
   [app.main.ui.components.link :refer [link*]]
   [app.main.ui.dashboard.check-updates :as dcu]
   [app.main.ui.dashboard.comments :refer [comments-icon* comments-section]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.organization-team-switch :refer [organization-team-switch*]]
   [app.main.ui.dashboard.project-menu :refer [project-menu-items*]]
   [app.main.ui.dashboard.subscription :refer [dashboard-cta*
                                               nitrate-current-plan*
                                               nitrate-sidebar*
                                               show-subscription-dashboard-banner?
                                               subscription-sidebar*]]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.layout.menu :refer [context-menu*]]
   [app.main.ui.hooks :refer [use-focus-timer-ref]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.nitrate.nitrate-form]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [goog.functions :as f]
   [rumext.v2 :as mf]))

(def ^:private clear-search-icon
  (deprecated-icon/icon-xref :delete-text (stl/css :clear-search-icon)))

(def ^:private search-icon
  (deprecated-icon/icon-xref :search (stl/css :search-icon)))

(def ^:private pin-icon
  (deprecated-icon/icon-xref :pin (stl/css :pin-icon)))

(def ^:private exit-icon
  (deprecated-icon/icon-xref :exit (stl/css :exit-icon)))

(defn schedule-focus-by-id!
  [ref element-id]
  (when-let [h (mf/ref-val ref)]
    (ts/dispose! h))
  (mf/set-ref-val! ref
                   (ts/schedule
                    #(dom/focus-and-untabbable! (dom/get-element element-id)))))

(mf/defc sidebar-project*
  {::mf/private true}
  [{:keys [item is-selected]}]
  (let [dstate           (mf/deref refs/dashboard-local)
        selected-files   (:selected-files dstate)
        selected-project (:selected-project dstate)
        edit-id          (:project-for-edit dstate)

        local*           (mf/use-state
                          #(do {:edition? (= (:id item) edit-id)
                                :dragging? false}))

        local            (deref local*)

        project-id       (get item :id)

        focus-timer-ref  (use-focus-timer-ref)

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
             (schedule-focus-by-id! focus-timer-ref (str project-id))
             (st/emit! (dcm/go-to-dashboard-files :project-id project-id)))))


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

    [:> context-menu* {:aria-label (tr "dashboard.options")
                       :trigger
                       (mf/html
                        [:li {:tab-index "0"
                              :class (stl/css-case :project-element true
                                                   :sidebar-nav-item true
                                                   :current is-selected
                                                   :dragging (:dragging? local))
                              :on-click on-click
                              :on-key-down on-key-down
                              :on-double-click on-edit-open
                              :on-drag-enter on-drag-enter
                              :on-drag-over on-drag-over
                              :on-drag-leave on-drag-leave
                              :on-drop on-drop}
                         (if (:edition? local)
                           [:& inline-edition {:content (:name item)
                                               :on-end on-edit}]
                           [:span {:class (stl/css :element-title)} (:name item)])])}
     [:> project-menu-items* {:project item :on-edit on-edit-open}]]))

(mf/defc sidebar-search*
  {::mf/private true}
  [{:keys [search-term team-id]}]
  (let [search-term (d/nilv search-term "")
        focused?    (mf/use-state false)
        emit!       (mf/use-memo #(f/debounce st/emit! 500))

        focus-timer-ref  (use-focus-timer-ref)

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
             (schedule-focus-by-id! focus-timer-ref "dashboard-search-title")
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

        nitrate?    (contains? cf/flags :admin-console)

        focus-timer-ref  (use-focus-timer-ref)

        go-projects
        (mf/use-fn #(st/emit! (dcm/go-to-dashboard-recent)))

        go-projects-with-key
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit!
            (dcm/go-to-dashboard-recent :team-id team-id))
           (schedule-focus-by-id! focus-timer-ref "dashboard-projects-title")))

        go-fonts
        (mf/use-fn
         (mf/deps team-id)
         #(st/emit! (dcm/go-to-dashboard-fonts :team-id team-id)))

        go-fonts-with-key
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit!
            (dcm/go-to-dashboard-fonts :team-id team-id))
           (schedule-focus-by-id! focus-timer-ref "dashboard-fonts-title")))

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
           (schedule-focus-by-id! focus-timer-ref "dashboard-drafts-title")))

        go-libs
        (mf/use-fn
         (mf/deps team-id)
         (fn [] (st/emit! (dcm/go-to-dashboard-libraries :team-id team-id))))

        go-libs-with-key
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit!
            (dcm/go-to-dashboard-libraries :team-id team-id))
           (schedule-focus-by-id! focus-timer-ref "dashboard-libraries-title")))

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
     [:> organization-team-switch* {:team team :profile profile}]
     [:div {:class (stl/css :sidebar-content-wrapper)}
      [:div {:ref container
             :class (stl/css-case :sidebar-content true :sidebar-content-nitrate nitrate?)}

       [:> sidebar-search* {:search-term search-term
                            :team-id (:id team)}]

       [:div {:class (stl/css :sidebar-content-section)}
        [:ul {:class (stl/css :sidebar-nav)}
         [:li {:class (stl/css-case :recent-projects true
                                    :sidebar-nav-item true
                                    :current projects?)}
          [:> link* {:action go-projects
                     :class (stl/css :sidebar-link)
                     :keyboard-action go-projects-with-key}
           [:span {:class (stl/css :element-title)} (tr "labels.projects")]]]

         [:li {:class (stl/css-case :current drafts?
                                    :sidebar-nav-item true)}
          [:> link* {:action go-drafts
                     :class (stl/css :sidebar-link)
                     :keyboard-action go-drafts-with-key}
           [:span {:class (stl/css :element-title)} (tr "labels.drafts")]]]]]


       [:div {:class (stl/css :sidebar-content-section)}
        [:div {:class (stl/css :sidebar-section-title)}
         (tr "labels.sources")]
        [:ul {:class (stl/css :sidebar-nav)}
         [:li {:class (stl/css-case :sidebar-nav-item true
                                    :current fonts?)}
          [:> link* {:action go-fonts
                     :class (stl/css :sidebar-link)
                     :keyboard-action go-fonts-with-key
                     :data-testid "fonts"}
           [:span {:class (stl/css :element-title)} (tr "labels.fonts")]]]
         [:li {:class (stl/css-case :current libs?
                                    :sidebar-nav-item true)}
          [:> link* {:action go-libs
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
      [:div {:class (stl/css-case :separator true :overflow-separator overflow?)}]]]))

(mf/defc help-learning-menu*
  {::mf/private true}
  [{:keys [on-close on-click on-pointer-enter on-pointer-leave]}]
  (let [handle-click-url
        (mf/use-fn
         (fn [event]
           (let [url       (-> (dom/get-current-target event)
                               (dom/get-data "url"))
                 eventname (-> (dom/get-current-target event)
                               (dom/get-data "eventname"))]
             (st/emit! (ev/event {::ev/name eventname
                                  ::ev/origin "menu:in-app"}))
             (dom/open-new-window url))))

        handle-feedback-click
        (mf/use-fn #(on-click :settings-feedback %))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :sub-menu :help-learning)
                        :on-close on-close
                        :on-pointer-enter on-pointer-enter
                        :on-pointer-leave on-pointer-leave}

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
  {::mf/private true}
  [{:keys [on-close on-pointer-enter on-pointer-leave]}]
  (let [handle-click-url
        (mf/use-fn
         (fn [event]
           (let [url       (-> (dom/get-current-target event)
                               (dom/get-data "url"))
                 eventname (-> (dom/get-current-target event)
                               (dom/get-data "eventname"))]
             (st/emit! (ev/event {::ev/name eventname
                                  ::ev/origin "menu:in-app"}))
             (dom/open-new-window url))))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :sub-menu :community)
                        :on-close on-close
                        :on-pointer-enter on-pointer-enter
                        :on-pointer-leave on-pointer-leave}

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
  {::mf/private true}
  [{:keys [on-close on-close-profile on-pointer-enter on-pointer-leave]}]
  (let [version     cf/version
        checking*   (mf/use-state false)
        checking?   (deref checking*)

        show-release-notes
        (mf/use-fn
         (fn [event]
           (st/emit! (ev/event {::ev/name "show-release-notes" :version (:main version)}))
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
             (st/emit! (ev/event {::ev/name eventname
                                  ::ev/origin "menu:in-app"}))
             (dom/open-new-window url))))

        check-for-updates
        (mf/use-fn
         (mf/deps on-close-profile version)
         (fn [event]
           (dom/stop-propagation event)
           (when-not @checking*
             (st/emit! (ev/event {::ev/name "check-for-updates"
                                  ::ev/origin "menu:in-app"
                                  :version (:base version)}))
             (dcu/check-for-updates!
              (:base version)
              {:on-start  #(reset! checking* true)
               :on-finish #(do (reset! checking* false)
                               (on-close-profile))}))))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :sub-menu :about)
                        :on-close on-close
                        :on-pointer-enter on-pointer-enter
                        :on-pointer-leave on-pointer-leave}

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
      (tr "auth.terms-of-service")]
     (when-not (contains? cf/flags :air-gapped-conf)
       [:*
        [:hr {:role "separator" :class (stl/css :submenu-separator)}]
        [:> dropdown-menu-item* {:class (stl/css-case :submenu-item true
                                                      :checking checking?)
                                 :aria-disabled checking?
                                 :can-focus (not checking?)
                                 :on-click check-for-updates}
         (if checking?
           (tr "labels.checking-for-updates")
           (tr "labels.check-for-updates"))
         (when checking?
           [:> icon* {:icon-id i/reload
                      :class (stl/css :checking-icon)
                      :size "s"}])]])]))

(mf/defc profile-section*
  [{:keys [profile team]}]
  (let [teams            (mf/deref refs/teams)
        show-profile-menu* (mf/use-state false)
        show-profile-menu? (deref show-profile-menu*)
        sub-menu*      (mf/use-state false)
        sub-menu       (deref sub-menu*)

        ;; Tracks whether the pointer is over an expandable option or
        ;; its floating submenu, so the submenu survives the gap
        ;; between them while the pointer travels across.
        hovering?*     (mf/use-ref false)
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

        on-menu-pointer-enter
        (mf/use-fn
         (fn [event]
           (mf/set-ref-val! hovering?* true)
           (on-menu-click event)))

        on-menu-pointer-leave
        (mf/use-fn
         (fn [_]
           (mf/set-ref-val! hovering?* false)
           (ts/schedule 200 #(when-not (mf/ref-val hovering?*)
                               (reset! sub-menu* nil)))))

        on-sub-menu-pointer-enter
        (mf/use-fn
         (fn [_]
           (mf/set-ref-val! hovering?* true)))]

    (mf/with-effect [teams]
      (when (and (contains? cf/flags :admin-console)
                 (empty? teams))
        (st/emit! (dtm/fetch-teams))))

    (mf/with-effect [show-profile-menu?]
      (when-not show-profile-menu?
        (reset! sub-menu* nil)))

    [:*
     (if (contains? cf/flags :admin-console)
       [:*
        [:> nitrate-sidebar* {:profile profile :teams teams}]
        [:> nitrate-current-plan* {:profile profile}]]
       (when (contains? cf/flags :subscriptions)
         (if (show-subscription-dashboard-banner? profile)
           [:> dashboard-cta* {:profile profile}]
           [:> subscription-sidebar* {:profile profile}])))


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
                                :on-pointer-enter on-menu-pointer-enter
                                :on-pointer-leave on-menu-pointer-leave
                                :data-testid "help-learning"
                                :id          "help-learning"}
        [:span {:class (stl/css :item-name)} (tr "labels.help-learning")]
        [:> icon* {:icon-id i/arrow :class (stl/css :open-arrow)}]]

       [:> dropdown-menu-item* {:class (stl/css-case :profile-dropdown-item true)
                                :on-click    on-menu-click
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (on-menu-click event)))
                                :on-pointer-enter on-menu-pointer-enter
                                :on-pointer-leave on-menu-pointer-leave
                                :data-testid "community-contributions"
                                :id          "community-contributions"}
        [:span {:class (stl/css :item-name)} (tr "labels.community-contributions")]
        [:> icon* {:icon-id i/arrow :class (stl/css :open-arrow)}]]

       [:> dropdown-menu-item* {:class (stl/css-case :profile-dropdown-item true)
                                :on-click    on-menu-click
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (on-menu-click event)))
                                :on-pointer-enter on-menu-pointer-enter
                                :on-pointer-leave on-menu-pointer-leave
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
         [:> help-learning-menu* {:on-close close-sub-menu
                                  :on-click on-click
                                  :on-pointer-enter on-sub-menu-pointer-enter
                                  :on-pointer-leave on-menu-pointer-leave}]

         :community-contributions
         [:> community-contributions-menu* {:on-close close-sub-menu
                                            :on-pointer-enter on-sub-menu-pointer-enter
                                            :on-pointer-leave on-menu-pointer-leave}]

         :about-penpot
         [:> about-penpot-menu* {:on-close close-sub-menu
                                 :on-close-profile on-close
                                 :on-pointer-enter on-sub-menu-pointer-enter
                                 :on-pointer-leave on-menu-pointer-leave}]
         nil))]))

(mf/defc sidebar*
  {::mf/wrap [mf/memo]}
  [{:keys [team profile] :as props}]
  [:nav {:class (stl/css :dashboard-sidebar) :data-testid "dashboard-sidebar"}
   [:> sidebar-content* props]
   [:> profile-section*
    {:profile profile
     :team team}]])
