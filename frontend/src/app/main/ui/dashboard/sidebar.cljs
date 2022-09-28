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
   [app.main.ui.components.dropdown :refer [dropdown]]
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
     [:li {:class (if selected? "current"
                    (when (:dragging? @local) "dragging"))
           :on-click on-click
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

        on-search-focus
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (reset! focused? true)
           (let [value (dom/get-target-val event)]
             (dom/select-text! (dom/get-target event))
             (emit! (dd/go-to-search value)))))

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
         (fn [_]
           (let [search-input (dom/get-element "search-input")]
             (dom/clean-value! search-input)
             (dom/focus! search-input)
             (emit! (dd/go-to-search)))))

        on-key-press
        (mf/use-callback
         (fn [e]
           (when (kbd/enter? e)
             (dom/prevent-default e)
             (dom/stop-propagation e))))]

    [:form.sidebar-search
     [:input.input-text
      {:key "images-search-box"
       :id "search-input"
       :type "text"
       :placeholder (tr "dashboard.search-placeholder")
       :default-value search-term
       :auto-complete "off"
       :on-focus on-search-focus
       :on-blur on-search-blur
       :on-change on-search-change
       :on-key-press on-key-press
       :ref #(when % (set! (.-value %) search-term))}]

     (if (or @focused? (seq search-term))
       [:div.clear-search
        {:on-click on-clear-click}
        i/close]

       [:div.search
        {:on-click on-clear-click}
        i/search])]))

(mf/defc teams-selector-dropdown
  [{:keys [profile] :as props}]
  (let [teams (mf/deref refs/teams)

        on-create-clicked
        (mf/use-callback
         #(st/emit! (modal/show :team-form {})))

        team-selected
        (mf/use-callback
         (fn [team-id]
           (st/emit! (dd/go-to-projects team-id))))]

    [:ul.dropdown.teams-dropdown
     [:li.title (tr "dashboard.switch-team")]
     [:hr]
     [:li.team-name {:on-click (partial team-selected (:default-team-id profile))}
      [:span.team-icon i/logo-icon]
      [:span.team-text (tr "dashboard.your-penpot")]]

     (for [team (remove :is-default (vals teams))]
       [:li.team-name {:on-click (partial team-selected (:id team))
                       :key (dm/str (:id team))}
        [:span.team-icon
         [:img {:src (cf/resolve-team-photo-url team)}]]
        [:span.team-text {:title (:name team)} (:name team)]])

     [:hr]
     [:li.action {:on-click on-create-clicked :data-test "create-new-team"}
      (tr "dashboard.create-new-team")]]))

(s/def ::member-id ::us/uuid)
(s/def ::leave-modal-form
  (s/keys :req-un [::member-id]))

(mf/defc team-options-dropdown
  [{:keys [team profile] :as props}]
  (let [go-members     #(st/emit! (dd/go-to-team-members))
        go-invitations #(st/emit! (dd/go-to-team-invitations))
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

    [:ul.dropdown.options-dropdown
     [:li {:on-click go-members :data-test "team-members"} (tr "labels.members")]
     [:li {:on-click go-invitations :data-test "team-invitations"} (tr "labels.invitations")]
     [:li {:on-click go-settings :data-test "team-settings"} (tr "labels.settings")]
     [:hr]
     (when can-rename?
       [:li {:on-click on-rename-clicked :data-test "rename-team"} (tr "labels.rename")])

     (cond
       (= (count members) 1)
       [:li {:on-click leave-and-close}  (tr "dashboard.leave-team")]

       (get-in team [:permissions :is-owner])
       [:li {:on-click on-leave-as-owner-clicked :data-test "leave-team"} (tr "dashboard.leave-team")]

       (> (count members) 1)
       [:li {:on-click on-leave-clicked}  (tr "dashboard.leave-team")])


     (when (get-in team [:permissions :is-owner])
       [:li.warning {:on-click on-delete-clicked :data-test "delete-team"} (tr "dashboard.delete-team")])]))


(mf/defc sidebar-team-switch
  [{:keys [team profile] :as props}]
  (let [show-team-opts-ddwn? (mf/use-state false)
        show-teams-ddwn?     (mf/use-state false)]

    [:div.sidebar-team-switch
     [:div.switch-content
      [:div.current-team {:on-click #(reset! show-teams-ddwn? true)}
       (if (:is-default team)
         [:div.team-name
          [:span.team-icon i/logo-icon]
          [:span.team-text (tr "dashboard.default-team-name")]]
         [:div.team-name
          [:span.team-icon
           [:img {:src (cf/resolve-team-photo-url team)}]]
          [:span.team-text {:title (:name team)} (:name team)]])

       [:span.switch-icon
        i/arrow-down]]

      (when-not (:is-default team)
        [:div.switch-options {:on-click #(reset! show-team-opts-ddwn? true)}
         i/actions])]

     ;; Teams Dropdown
     [:& dropdown {:show @show-teams-ddwn?
                   :on-close #(reset! show-teams-ddwn? false)}
      [:& teams-selector-dropdown {:team team
                                   :profile profile}]]

     [:& dropdown {:show @show-team-opts-ddwn?
                   :on-close #(reset! show-team-opts-ddwn? false)}
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

        go-fonts
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-fonts {:team-id (:id team)})))

        go-drafts
        (mf/use-callback
         (mf/deps team default-project-id)
         (fn []
           (st/emit! (rt/nav :dashboard-files
                             {:team-id (:id team)
                              :project-id default-project-id}))))
        go-libs
        (mf/use-callback
         (mf/deps team)
         #(st/emit! (rt/nav :dashboard-libraries {:team-id (:id team)})))

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
        {:on-click go-projects
         :class-name (when projects? "current")}
        [:span.element-title (tr "labels.projects")]]

       [:li {:on-click go-drafts
             :class-name (when drafts? "current")}
        [:span.element-title (tr "labels.drafts")]]


       [:li {:on-click go-libs
             :class-name (when libs? "current")}
        [:span.element-title (tr "labels.shared-libraries")]]]]

     [:hr]

     [:div.sidebar-content-section
      [:ul.sidebar-nav.no-overflow
       [:li
        {:on-click go-fonts
         :data-test "fonts"
         :class-name (when fonts? "current")}
        [:span.element-title (tr "labels.fonts")]]]]

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
     [:div.profile {:on-click #(reset! show true)
                    :data-test "profile-btn"}
      [:img {:src photo}]
      [:span (:fullname profile)]]

     [:& dropdown {:on-close #(reset! show false)
                   :show @show}
      [:ul.dropdown
       [:li {:on-click (partial on-click :settings-profile)
             :data-test "profile-profile-opt"}
        [:span.text (tr "labels.your-account")]]
       [:li.separator {:on-click #(dom/open-new-window "https://help.penpot.app")
                       :data-test "help-center-profile-opt"}
        [:span.text (tr "labels.help-center")]]
       [:li {:on-click #(dom/open-new-window "https://community.penpot.app")}
        [:span.text (tr "labels.community")]]
       [:li {:on-click #(dom/open-new-window "https://www.youtube.com/c/Penpot")}
        [:span.text (tr "labels.tutorials")]]
       [:li {:on-click show-release-notes}
        [:span (tr "labels.release-notes")]]

       [:li.separator {:on-click #(dom/open-new-window "https://penpot.app/libraries-templates.html")
                       :data-test "libraries-templates-profile-opt"}
        [:span.text (tr "labels.libraries-and-templates")]]
       [:li {:on-click #(dom/open-new-window "https://github.com/penpot/penpot")}
        [:span (tr "labels.github-repo")]]
       [:li  {:on-click #(dom/open-new-window "https://penpot.app/terms.html")}
        [:span (tr "auth.terms-of-service")]]

       (when (contains? @cf/flags :user-feedback)
         [:li.separator {:on-click (partial on-click :settings-feedback)
                         :data-test "feedback-profile-opt"}
          [:span.text (tr "labels.give-feedback")]])

       [:li.separator {:on-click #(on-click (du/logout) %)
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
    [:div.dashboard-sidebar
     [:div.sidebar-inside
      [:> sidebar-content props]
      [:& profile-section
       {:profile profile
        :team team}]]]))
