;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.sidebar
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu dropdown-menu-item]]
   [app.main.ui.components.link :refer [link]]
   [app.main.ui.dashboard.comments :refer [comments-section]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu]]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [goog.functions :as f]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc sidebar-project
  [{:keys [item selected?] :as props}]
  (let [dstate           (mf/deref refs/dashboard-local)
        selected-files   (:selected-files dstate)
        selected-project (:selected-project dstate)
        edit-id          (:project-for-edit dstate)

        local            (mf/use-state
                          {:menu-open false
                           :menu-pos nil
                           :edition? (= (:id item) edit-id)
                           :dragging? false})

        on-click
        (mf/use-callback
         (mf/deps item)
         (fn []
           (st/emit! (dd/go-to-files (:id item)))))

        on-key-down
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (when (kbd/enter? event)
             (st/emit! (dd/go-to-files (:id item))
                       (ts/schedule-on-idle
                        (fn []
                          (let [project-title (dom/get-element (str (:id item)))]
                            (when project-title
                              (dom/set-attribute! project-title "tabindex" "0")
                              (dom/focus! project-title)
                              (dom/set-attribute! project-title "tabindex" "-1")))))))))

        on-menu-click
        (mf/use-callback
         (fn [event]
           (let [position (dom/get-client-position event)]
             (dom/prevent-default event)
             (swap! local assoc
                    :menu-open true
                    :menu-pos position))))

        on-menu-close
        (mf/use-callback #(swap! local assoc :menu-open false))

        on-edit-open
        (mf/use-callback #(swap! local assoc :edition? true))

        on-edit
        (mf/use-callback
         (mf/deps item)
         (fn [name]
           (st/emit! (-> (dd/rename-project (assoc item :name name))
                         (with-meta {::ev/origin "dashboard:sidebar"})))
           (swap! local assoc :edition? false)))

        on-drag-enter
        (mf/use-callback
         (mf/deps selected-project)
         (fn [e]
           (when (dnd/has-type? e "penpot/files")
             (dom/prevent-default e)
             (when-not (dnd/from-child? e)
               (when (not= selected-project (:id item))
                 (swap! local assoc :dragging? true))))))

        on-drag-over
        (mf/use-callback
         (fn [e]
           (when (dnd/has-type? e "penpot/files")
             (dom/prevent-default e))))

        on-drag-leave
        (mf/use-callback
         (fn [e]
           (when-not (dnd/from-child? e)
             (swap! local assoc :dragging? false))))

        on-drop-success
        (mf/use-callback
         (mf/deps (:id item))
         #(st/emit! (msg/success (tr "dashboard.success-move-file"))
                    (dd/go-to-files (:id item))))

        on-drop
        (mf/use-callback
         (mf/deps item selected-files)
         (fn [_]
           (swap! local assoc :dragging? false)
           (when (not= selected-project (:id item))
             (let [data  {:ids selected-files
                          :project-id (:id item)}
                   mdata {:on-success on-drop-success}]
               (st/emit! (dd/move-files (with-meta data mdata)))))))]

    [:*
     [:li {:tab-index "0"
           :class (if selected? "current"
                      (when (:dragging? @local) "dragging"))
           :on-click on-click
           :on-key-down on-key-down
           :on-double-click on-edit-open
           :on-context-menu on-menu-click
           :on-drag-enter on-drag-enter
           :on-drag-over on-drag-over
           :on-drag-leave on-drag-leave
           :on-drop on-drop}
      (if (:edition? @local)
        [:& inline-edition {:content (:name item)
                            :on-end on-edit}]
        [:span.element-title (:name item)])]
     [:& project-menu {:project item
                       :show? (:menu-open @local)
                       :left (:x (:menu-pos @local))
                       :top (:y (:menu-pos @local))
                       :on-edit on-edit-open
                       :on-menu-close on-menu-close}]]))

(mf/defc sidebar-search
  [{:keys [search-term team-id] :as props}]
  (let [search-term (or search-term "")
        focused?    (mf/use-state false)
        emit!       (mf/use-memo #(f/debounce st/emit! 500))

        on-search-blur
        (mf/use-callback
         (fn [_]
           (reset! focused? false)))

        on-search-change
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (emit! (dd/go-to-search value)))))

        on-clear-click
        (mf/use-callback
         (mf/deps team-id)
         (fn [e]
           (let [search-input (dom/get-element "search-input")]
             (dom/clean-value! search-input)
             (dom/focus! search-input)
             (emit! (dd/go-to-search))
             (dom/prevent-default e)
             (dom/stop-propagation e))))

        on-key-press
        (mf/use-callback
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
             (dom/stop-propagation e))))]

    [:form.sidebar-search
     [:input.input-text
      {:key "images-search-box"
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
       [:div.clear-search
        {:tab-index "0"
         :on-click on-clear-click
         :on-key-down (fn [event]
                        (when (kbd/enter? event)
                          (on-clear-click event)))}
        i/close]

       [:div.search
        {:on-click on-clear-click}
        i/search])]))

(mf/defc teams-selector-dropdown-items
  [{:keys [team profile teams] :as props}]
  (let [on-create-clicked
        (mf/use-callback
         #(st/emit! (modal/show :team-form {})))

        team-selected
        (mf/use-callback
         (fn [team-id]
           (st/emit! (dd/go-to-projects team-id))))]

    [:*
     [:& dropdown-menu-item {:on-click    (partial team-selected (:default-team-id profile))
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (team-selected (:default-team-id profile) event)))
                             :id          "teams-selector-default-team"
                             :klass       "team-name"}
      [:span.team-icon i/logo-icon]
      [:span.team-text (tr "dashboard.your-penpot")]
      (when (= (:default-team-id profile) (:id team))
        [:span.icon i/tick])]

     (for [team-item (remove :is-default (vals teams))]
       [:& dropdown-menu-item {:on-click    (partial team-selected (:id team-item))
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (team-selected (:id team-item) event)))
                               :id          (str "teams-selector-" (:id team-item))
                               :klass       "team-name"
                               :key         (dm/str (:id team-item))}
        [:span.team-icon
         [:img {:src (cf/resolve-team-photo-url team-item)
                :alt (:name team-item)}]]
        [:span.team-text {:title (:name team-item)} (:name team-item)]
        (when (= (:id team-item) (:id team))
          [:span.icon i/tick])])
     [:hr {:role "separator"}]
     [:& dropdown-menu-item {:on-click    on-create-clicked
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (on-create-clicked event)))
                             :id          "teams-selector-create-team"
                             :klass       "team-name action"
                             :key         "teams-selector-create-team"}
      [:span.team-icon.new-team i/close]
      [:span.team-text (tr "dashboard.create-new-team")]]]))

(s/def ::member-id ::us/uuid)
(s/def ::leave-modal-form
  (s/keys :req-un [::member-id]))

(mf/defc team-options-dropdown
  [{:keys [team profile] :as props}]
  (let [go-members     #(st/emit! (dd/go-to-team-members))
        go-invitations #(st/emit! (dd/go-to-team-invitations))
        go-webhooks    #(st/emit! (dd/go-to-team-webhooks))
        go-settings    #(st/emit! (dd/go-to-team-settings))

        members-map    (mf/deref refs/dashboard-team-members)
        members        (vals members-map)
        can-rename?    (or (get-in team [:permissions :is-owner]) (get-in team [:permissions :is-admin]))

        on-success
        (fn []
          (st/emit! (dd/go-to-projects (:default-team-id profile))
                    (modal/hide)
                    (du/fetch-teams)))

        on-error
        (fn [{:keys [code] :as error}]
          (condp = code
            :no-enough-members-for-leave
            (rx/of (msg/error (tr "errors.team-leave.insufficient-members")))

            :member-does-not-exist
            (rx/of (msg/error (tr "errors.team-leave.member-does-not-exists")))

            :owner-cant-leave-team
            (rx/of (msg/error (tr "errors.team-leave.owner-cant-leave")))

            (rx/throw error)))

        leave-fn
        (fn [member-id]
          (let [params (cond-> {} (uuid? member-id) (assoc :reassign-to member-id))]
            (st/emit! (dd/leave-team (with-meta params
                                       {:on-success on-success
                                        :on-error on-error})))))
        delete-fn
        (fn []
          (st/emit! (dd/delete-team (with-meta team {:on-success on-success
                                                     :on-error on-error}))))
        on-rename-clicked
        (fn []
          (st/emit! (modal/show :team-form {:team team})))

        on-leave-clicked
        #(st/emit! (modal/show
                    {:type :confirm
                     :title (tr "modals.leave-confirm.title")
                     :message (tr "modals.leave-confirm.message")
                     :accept-label (tr "modals.leave-confirm.accept")
                     :on-accept leave-fn}))

        on-leave-as-owner-clicked
        (fn []
          (st/emit! (dd/fetch-team-members)
                    (modal/show
                     {:type :leave-and-reassign
                      :profile profile
                      :team team
                      :accept leave-fn})))

        leave-and-close
        #(st/emit! (modal/show
                    {:type :confirm
                     :title (tr "modals.leave-confirm.title")
                     :message  (tr "modals.leave-and-close-confirm.message" (:name team))
                     :scd-message (tr "modals.leave-and-close-confirm.hint")
                     :accept-label (tr "modals.leave-confirm.accept")
                     :on-accept delete-fn}))

        on-delete-clicked
        #(st/emit!
          (modal/show
           {:type :confirm
            :title (tr "modals.delete-team-confirm.title")
            :message (tr "modals.delete-team-confirm.message")
            :accept-label (tr "modals.delete-team-confirm.accept")
            :on-accept delete-fn}))]

    [:*
     [:& dropdown-menu-item {:on-click    go-members
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (go-members)))
                             :id          "teams-options-members"
                             :key         "teams-options-members"
                             :data-test "team-members"}
      (tr "labels.members")]
     [:& dropdown-menu-item {:on-click    go-invitations
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (go-invitations)))
                             :id          "teams-options-invitations"
                             :key         "teams-options-invitations"
                             :data-test "team-invitations"}
      (tr "labels.invitations")]

     (when (contains? @cf/flags :webhooks)
       [:& dropdown-menu-item {:on-click    go-webhooks
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (go-webhooks)))
                               :id          "teams-options-webhooks"
                               :key         "teams-options-webhooks"}
        (tr "labels.webhooks")])

     [:& dropdown-menu-item {:on-click    go-settings
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (go-settings)))
                             :id          "teams-options-settings"
                             :key         "teams-options-settings"
                             :data-test "team-settings"}
      (tr "labels.settings")]

     [:hr]
     (when can-rename?
       [:& dropdown-menu-item {:on-click    on-rename-clicked
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-rename-clicked)))
                               :id          "teams-options-rename"
                               :key         "teams-options-rename"
                               :data-test "rename-team"}
        (tr "labels.rename")])

     (cond
       (= (count members) 1)
       [:& dropdown-menu-item {:on-click    leave-and-close
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (leave-and-close)))
                               :id          "teams-options-leave-team"
                               :key         "teams-options-leave-team"}
        (tr "dashboard.leave-team")]


       (get-in team [:permissions :is-owner])
       [:& dropdown-menu-item {:on-click    on-leave-as-owner-clicked
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-leave-as-owner-clicked)))
                               :id          "teams-options-leave-team"
                               :key         "teams-options-leave-team"
                               :data-test "leave-team"}
        (tr "dashboard.leave-team")]

       (> (count members) 1)
       [:& dropdown-menu-item {:on-click    on-leave-clicked
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-leave-clicked)))
                               :id          "teams-options-leave-team"
                               :key         "teams-options-leave-team"}
        (tr "dashboard.leave-team")])


     (when (get-in team [:permissions :is-owner])
       [:& dropdown-menu-item {:on-click    on-delete-clicked
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-delete-clicked)))
                               :id          "teams-options-delete-team"
                               :key         "teams-options-delete-team"
                               :klass       "warning"
                               :data-test "delete-team"}
        (tr "dashboard.delete-team")])]))


(mf/defc sidebar-team-switch
  [{:keys [team profile] :as props}]
  (let [teams                 (mf/deref refs/teams)
        teams-without-default (into {} (filter (fn [[_ v]] (= false (:is-default v))) teams))
        team-ids              (map #(str "teams-selector-" %) (keys teams-without-default))
        ids                   (concat ["teams-selector-default-team"] team-ids ["teams-selector-create-team"])
        show-team-opts-ddwn?  (mf/use-state false)
        show-teams-ddwn?      (mf/use-state false)
        can-rename?           (or (get-in team [:permissions :is-owner]) (get-in team [:permissions :is-admin]))
        options-ids           ["teams-options-members"
                               "teams-options-invitations"
                               (when (contains? @cf/flags :webhooks)
                                 "teams-options-webhooks")
                               "teams-options-settings"
                               (when can-rename?
                                 "teams-options-rename")
                               "teams-options-leave-team"
                               (when (get-in team [:permissions :is-owner])
                                 "teams-options-delete-team")]]

    [:div.sidebar-team-switch
     [:div.switch-content
      [:button.current-team {:tab-index "0"
                             :on-click #(reset! show-teams-ddwn? true)
                             :on-key-down (fn [event]
                                            (when (or (kbd/space? event) (kbd/enter? event))
                                              (dom/prevent-default event)
                                              (reset! show-teams-ddwn? true)
                                              (ts/schedule-on-idle
                                               (fn []
                                                 (let [first-element (dom/get-element (first ids))]
                                                   (when first-element
                                                     (dom/focus! first-element)))))))}
       (if (:is-default team)
         [:div.team-name
          [:span.team-icon i/logo-icon]
          [:span.team-text (tr "dashboard.default-team-name")]]
         [:div.team-name
          [:span.team-icon
           [:img {:src (cf/resolve-team-photo-url team)
                  :alt (:name team)}]]
          [:span.team-text {:title (:name team)} (:name team)]])

       [:span.switch-icon
        i/arrow-down]]

      (when-not (:is-default team)
        [:button.switch-options {:on-click #(reset! show-team-opts-ddwn? true)
                                 :tab-index "0"
                                 :on-key-down (fn [event]
                                                (when (or (kbd/space? event) (kbd/enter? event))
                                                  (dom/prevent-default event)
                                                  (reset! show-team-opts-ddwn? true)
                                                  (ts/schedule-on-idle
                                                   (fn []
                                                     (let [first-element (dom/get-element (first options-ids))]
                                                       (when first-element
                                                         (dom/focus! first-element)))))))}
         i/actions])]

     ;; Teams Dropdown
     [:& dropdown-menu {:show @show-teams-ddwn?
                        :on-close #(reset! show-teams-ddwn? false)
                        :ids ids
                        :list-class "dropdown teams-dropdown"}
      [:& teams-selector-dropdown-items {:ids ids
                                         :team team
                                         :profile profile
                                         :teams teams}]]

     [:& dropdown-menu {:show @show-team-opts-ddwn?
                        :on-close #(reset! show-team-opts-ddwn? false)
                        :ids options-ids
                        :list-class "dropdown options-dropdown"}
      [:& team-options-dropdown {:team team
                                 :profile profile}]]]))

(mf/defc sidebar-content
  [{:keys [projects profile section team project search-term] :as props}]
  (let [default-project-id
        (->> (vals projects)
             (d/seek :is-default)
             (:id))

        projects?   (= section :dashboard-projects)
        fonts?      (= section :dashboard-fonts)
        libs?       (= section :dashboard-libraries)
        drafts?     (and (= section :dashboard-files)
                         (= (:id project) default-project-id))

        go-projects
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-projects {:team-id (:id team)})))

        go-projects-with-key
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-projects {:team-id (:id team)})
                    (ts/schedule-on-idle
                     (fn []
                       (let [projects-title (dom/get-element "dashboard-projects-title")]
                         (when projects-title
                           (dom/set-attribute! projects-title "tabindex" "0")
                           (dom/focus! projects-title)
                           (dom/set-attribute! projects-title "tabindex" "-1")))))))

        go-fonts
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-fonts {:team-id (:id team)})))

        go-fonts-with-key
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-fonts {:team-id (:id team)})
                    (ts/schedule-on-idle
                     (fn []
                       (let [font-title (dom/get-element "dashboard-fonts-title")]
                         (when font-title
                           (dom/set-attribute! font-title "tabindex" "0")
                           (dom/focus! font-title)
                           (dom/set-attribute! font-title "tabindex" "-1")))))))
        go-drafts
        (mf/use-callback
         (mf/deps team default-project-id)
         (fn []
           (st/emit! (rt/nav :dashboard-files
                             {:team-id (:id team)
                              :project-id default-project-id}))))

        go-drafts-with-key
        (mf/use-callback
         (mf/deps team default-project-id)
         #(st/emit! (rt/nav :dashboard-files {:team-id (:id team)
                                              :project-id default-project-id})
                    (ts/schedule-on-idle
                     (fn []
                       (let [drafts-title (dom/get-element "dashboard-drafts-title")]
                         (when drafts-title
                           (dom/set-attribute! drafts-title "tabindex" "0")
                           (dom/focus! drafts-title)
                           (dom/set-attribute! drafts-title "tabindex" "-1")))))))

        go-libs
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-libraries {:team-id (:id team)})))

        go-libs-with-key
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-libraries {:team-id (:id team)})
                    (ts/schedule-on-idle
                     (fn []
                       (let [libs-title (dom/get-element "dashboard-libraries-title")]
                         (when libs-title
                           (dom/set-attribute! libs-title "tabindex" "0")
                           (dom/focus! libs-title)
                           (dom/set-attribute! libs-title "tabindex" "-1")))))))
        pinned-projects
        (->> (vals projects)
             (remove :is-default)
             (filter :is-pinned))]

    [:div.sidebar-content
     [:& sidebar-team-switch {:team team :profile profile}]
     [:hr]
     [:& sidebar-search {:search-term search-term
                         :team-id (:id team)}]
     [:div.sidebar-content-section
      [:ul.sidebar-nav.no-overflow
       [:li.recent-projects
        {:class-name (when projects? "current")}
        [:& link {:action go-projects
                  :keyboard-action go-projects-with-key}
         [:span.element-title (tr "labels.projects")]]]

       [:li {:class-name (when drafts? "current")}
        [:& link {:action go-drafts
                  :keyboard-action go-drafts-with-key}
         [:span.element-title (tr "labels.drafts")]]]


       [:li {:class-name (when libs? "current")}
        [:& link {:action go-libs
                  :keyboard-action go-libs-with-key}
         [:span.element-title (tr "labels.shared-libraries")]]]]]

     [:hr]

     [:div.sidebar-content-section
      [:ul.sidebar-nav.no-overflow
       [:li {:class-name (when fonts? "current")}

        [:& link {:action go-fonts
                  :keyboard-action go-fonts-with-key
                  :data-test "fonts"}
         [:span.element-title (tr "labels.fonts")]]]]]

     [:hr]
     [:div.sidebar-content-section {:data-test "pinned-projects"}
      (if (seq pinned-projects)
        [:ul.sidebar-nav
         (for [item pinned-projects]
           [:& sidebar-project
            {:item item
             :key (dm/str (:id item))
             :id (:id item)
             :team-id (:id team)
             :selected? (= (:id item) (:id project))}])]
        [:div.sidebar-empty-placeholder
         [:span.icon i/pin]
         [:span.text (tr "dashboard.no-projects-placeholder")]])]]))


(mf/defc profile-section
  [{:keys [profile team] :as props}]
  (let [show  (mf/use-state false)
        photo (cf/resolve-profile-photo-url profile)

        on-click
        (mf/use-callback
         (fn [section event]
           (dom/stop-propagation event)
           (if (keyword? section)
             (st/emit! (rt/nav section))
             (st/emit! section))))

        show-release-notes
        (mf/use-callback
         (fn [event]
           (let [version (:main @cf/version)]
             (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version version}))
             (if (and (kbd/alt? event) (kbd/mod? event))
               (st/emit! (modal/show {:type :onboarding}))
               (st/emit! (modal/show {:type :release-notes :version version}))))))]

    [:div.profile-section
     [:div.profile {:tab-index "0"
                    :on-click #(reset! show true)
                    :on-key-down (fn [event]
                                   (when (kbd/enter? event)
                                     (reset! show true)))
                    :data-test "profile-btn"}
      [:img {:src photo
             :alt (:fullname profile)}]
      [:span (:fullname profile)]]

     [:& dropdown-menu {:on-close #(reset! show false)
                        :show @show}
      [:ul.dropdown
       [:li {:tab-index (if show
                          "0"
                          "-1")
             :on-click (partial on-click :settings-profile)
             :on-key-down (fn [event]
                            (when (kbd/enter? event)
                              (on-click :settings-profile event)))
             :data-test "profile-profile-opt"}
        [:span.text (tr "labels.your-account")]]
       [:li.separator {:tab-index (if show
                                    "0"
                                    "-1")
                       :on-click #(dom/open-new-window "https://help.penpot.app")
                       :on-key-down (fn [event]
                                      (when (kbd/enter? event)
                                        (dom/open-new-window "https://help.penpot.app")))
                       :data-test "help-center-profile-opt"}
        [:span.text (tr "labels.help-center")]]
       [:li {:tab-index (if show
                          "0"
                          "-1")
             :on-click #(dom/open-new-window "https://community.penpot.app")
             :on-key-down (fn [event]
                            (when (kbd/enter? event)
                              (dom/open-new-window "https://community.penpot.app")))}
        [:span.text (tr "labels.community")]]
       [:li {:tab-index (if show
                          "0"
                          "-1")
             :on-click #(dom/open-new-window "https://www.youtube.com/c/Penpot")
             :on-key-down (fn [event]
                            (when (kbd/enter? event)
                              (dom/open-new-window "https://www.youtube.com/c/Penpot")))}
        [:span.text (tr "labels.tutorials")]]
       [:li {:tab-index (if show
                          "0"
                          "-1")
             :on-click show-release-notes
             :on-key-down (fn [event]
                            (when (kbd/enter? event)
                              (show-release-notes)))}
        [:span (tr "labels.release-notes")]]

       [:li.separator {:tab-index (if show
                                    "0"
                                    "-1")
                       :on-click #(dom/open-new-window "https://penpot.app/libraries-templates")
                       :on-key-down (fn [event]
                                      (when (kbd/enter? event)
                                        (dom/open-new-window "https://penpot.app/libraries-templates")))
                       :data-test "libraries-templates-profile-opt"}
        [:span.text (tr "labels.libraries-and-templates")]]
       [:li {:tab-index (if show
                          "0"
                          "-1")
             :on-click #(dom/open-new-window "https://github.com/penpot/penpot")
             :on-key-down (fn [event]
                            (when (kbd/enter? event)
                              (dom/open-new-window "https://github.com/penpot/penpot")))}
        [:span (tr "labels.github-repo")]]
       [:li  {:tab-index (if show
                           "0"
                           "-1")
              :on-click #(dom/open-new-window "https://penpot.app/terms")
              :on-key-down (fn [event]
                             (when (kbd/enter? event)
                               (dom/open-new-window "https://penpot.app/terms")))}
        [:span (tr "auth.terms-of-service")]]

       (when (contains? @cf/flags :user-feedback)
         [:li.separator {:tab-index (if show
                                      "0"
                                      "-1")
                         :on-click (partial on-click :settings-feedback)
                         :on-key-down (fn [event]
                                        (when (kbd/enter? event)
                                          (on-click :settings-feedback event)))
                         :data-test "feedback-profile-opt"}
          [:span.text (tr "labels.give-feedback")]])

       [:li.separator {:tab-index (if show
                                    "0"
                                    "-1")
                       :on-click #(on-click (du/logout) %)
                       :on-key-down (fn [event]
                                      (when (kbd/enter? event)
                                        (on-click (du/logout) event)))
                       :data-test "logout-profile-opt"}
        [:span.icon i/exit]
        [:span.text (tr "labels.logout")]]]]

     (when (and team profile)
       [:& comments-section {:profile profile
                             :team team}])]))

(mf/defc sidebar
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [team    (obj/get props "team")
        profile (obj/get props "profile")]
    [:nav.dashboard-sidebar
     [:> sidebar-content props]
     [:& profile-section
      {:profile profile
       :team team}]]))
