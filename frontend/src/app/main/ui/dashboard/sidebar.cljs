;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.sidebar
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.comments :refer [comments-section]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu]]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.icons :as i]
   [app.util.avatars :as avatars]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [goog.functions :as f]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc sidebar-project
  [{:keys [item team-id selected?] :as props}]
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
           (st/emit! (rt/nav :dashboard-files {:team-id (:team-id item)
                                               :project-id (:id item)}))))

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
           (st/emit! (dd/rename-project (assoc item :name name)))
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

        on-drop
        (mf/use-callback
          (mf/deps item selected-files)
          (fn [e]
            (swap! local assoc :dragging? false)
            (when (not= selected-project (:id item))
              (let [data  {:ids selected-files
                           :project-id (:id item)}

                    mdata {:on-success
                           (st/emitf (dm/success (tr "dashboard.success-move-file"))
                                     (rt/nav :dashboard-files
                                             {:team-id team-id
                                              :project-id (:id item)}))}]
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
           (let [target (dom/get-target event)
                 value (dom/get-value target)]
             (dom/select-text! target)
             (if (empty? value)
               (emit! (rt/nav :dashboard-search {:team-id team-id} {}))
               (emit! (rt/nav :dashboard-search {:team-id team-id} {:search-term value}))))))

        on-search-blur
        (mf/use-callback
         (fn [event]
           (reset! focused? false)))

        on-search-change
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value))]
             (emit! (rt/nav :dashboard-search {:team-id team-id} {:search-term value})))))

        on-clear-click
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [search-input (dom/get-element "search-input")]
             (dom/clean-value! search-input)
             (dom/focus! search-input)
             (emit! (rt/nav :dashboard-search {:team-id team-id} {})))))]

    [:form.sidebar-search
     [:input.input-text
      {:key :images-search-box
       :id "search-input"
       :type "text"
       :placeholder (tr "dashboard.search-placeholder")
       :default-value search-term
       :auto-complete "off"
       :on-focus on-search-focus
       :on-blur on-search-blur
       :on-change on-search-change
       :ref #(when % (set! (.-value %) search-term))}]

     (if (or @focused? (not (empty? search-term)))
       [:div.clear-search
        {:on-click on-clear-click}
        i/close]

       [:div.search
        {:on-click on-clear-click}
        i/search])]))

(mf/defc teams-selector-dropdown
  [{:keys [team profile] :as props}]
  (let [show-dropdown? (mf/use-state false)
        teams          (mf/deref refs/teams)

        on-create-clicked
        (mf/use-callback
         (st/emitf (modal/show :team-form {})))

        team-selected
        (mf/use-callback
          (fn [team-id]
            (du/set-current-team! team-id)
            (st/emit! (rt/nav :dashboard-projects {:team-id team-id}))))]

    [:ul.dropdown.teams-dropdown
     [:li.title (tr "dashboard.switch-team")]
     [:hr]
     [:li.team-name {:on-click (partial team-selected (:default-team-id profile))}
      [:span.team-icon i/logo-icon]
      [:span.team-text (tr "dashboard.your-penpot")]]

     (for [team (remove :is-default (vals teams))]
       [:* {:key (:id team)}
        [:li.team-name {:on-click (partial team-selected (:id team))}
         [:span.team-icon
          [:img {:src (cfg/resolve-team-photo-url team)}]]
         [:span.team-text {:title (:name team)} (:name team)]]])

     [:hr]
     [:li.action {:on-click on-create-clicked}
      (tr "dashboard.create-new-team")]]))

(s/def ::member-id ::us/uuid)
(s/def ::leave-modal-form
  (s/keys :req-un [::member-id]))

(mf/defc leave-and-reassign-modal
  {::mf/register modal/components
   ::mf/register-as ::leave-and-reassign}
  [{:keys [members profile team accept]}]
  (let [form    (fm/use-form :spec ::leave-modal-form :initial {})
        not-current-user? (fn [{:keys [id]}] (not= id (:id profile)))
        members (->> members (filterv not-current-user?))
        options (into [{:value "" :label (tr "modals.leave-and-reassign.select-memeber-to-promote")}]
                      (map #(hash-map :label (:name %) :value (str (:id %))) members))

        on-cancel
        (mf/use-callback (st/emitf (modal/hide)))

        on-accept
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (let [member-id (get-in @form [:clean-data :member-id])]
             (accept member-id))))]

    [:div.modal-overlay
     [:div.modal-container.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "modals.leave-and-reassign.title")]]
       [:div.modal-close-button
        {:on-click on-cancel} i/close]]

      [:div.modal-content.generic-form
       [:p (tr "modals.leave-and-reassign.hint1" (:name team))]

       (if (empty? members)
         [:p (tr "modals.leave-and-reassign.forbiden")]
         [:*
          [:p (tr "modals.leave-and-reassign.hint2")]
          [:& fm/form {:form form}
           [:& fm/select {:name :member-id
                          :options options}]]])]

      [:div.modal-footer
       [:div.action-buttons
        [:input.cancel-button
         {:type "button"
          :value (tr "labels.cancel")
          :on-click on-cancel}]

        [:input.accept-button
         {:type "button"
          :class (if (:valid @form) "primary" "btn-disabled")
          :disabled (not (:valid @form))
          :value (tr "modals.leave-and-reassign.promote-and-leave")
          :on-click on-accept}]]]]]))


(mf/defc team-options-dropdown
  [{:keys [team profile] :as props}]
  (let [members (mf/use-state [])

        go-members
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-team-members {:team-id (:id team)})))

        go-settings
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-team-settings {:team-id (:id team)})))

        on-create-clicked
        (mf/use-callback
         (st/emitf (modal/show :team-form {})))

        on-rename-clicked
        (mf/use-callback
         (mf/deps team)
         (st/emitf (modal/show :team-form {:team team})))

        on-leaved-success
        (mf/use-callback
         (mf/deps team profile)
         (fn []
           (let [team-id (:default-team-id profile)]
             (du/set-current-team! team-id)
             (st/emit! (modal/hide)
                       (du/fetch-teams)
                       (rt/nav :dashboard-projects {:team-id team-id})))))

        leave-fn
        (mf/use-callback
         (mf/deps team)
         (st/emitf (dd/leave-team (with-meta team {:on-success on-leaved-success}))))

        leave-and-reassign-fn
        (mf/use-callback
         (mf/deps team)
         (fn [member-id]
           (let [team (assoc team :reassign-to member-id)]
             (st/emit! (dd/leave-team (with-meta team {:on-success on-leaved-success}))))))

        on-leave-clicked
        (mf/use-callback
         (mf/deps team)
         (st/emitf (modal/show
                    {:type :confirm
                     :title (tr "modals.leave-confirm.title")
                     :message (tr "modals.leave-confirm.message")
                     :accept-label (tr "modals.leave-confirm.accept")
                     :on-accept leave-fn})))

        on-leave-as-owner-clicked
        (mf/use-callback
         (mf/deps team @members)
         (st/emitf (modal/show
                    {:type ::leave-and-reassign
                     :profile profile
                     :team team
                     :accept leave-and-reassign-fn
                     :members @members})))

        delete-fn
        (mf/use-callback
         (mf/deps team)
         (st/emitf (dd/delete-team (with-meta team {:on-success on-leaved-success}))))

        on-delete-clicked
        (mf/use-callback
         (mf/deps team)
         (st/emitf (modal/show
                    {:type :confirm
                     :title (tr "modals.delete-team-confirm.title")
                     :message (tr "modals.delete-team-confirm.message")
                     :accept-label (tr "modals.delete-team-confirm.accept")
                     :on-accept delete-fn})))]

    (mf/use-layout-effect
     (mf/deps (:id team))
     (fn []
       (->> (rp/query! :team-members {:team-id (:id team)})
            (rx/subs #(reset! members %)))))

    [:ul.dropdown.options-dropdown
     [:li {:on-click go-members} (tr "labels.members")]
     [:li {:on-click go-settings} (tr "labels.settings")]
     [:hr]
     [:li {:on-click on-rename-clicked} (tr "labels.rename")]

     (cond
       (:is-owner team)
       [:li {:on-click on-leave-as-owner-clicked} (tr "dashboard.leave-team")]

       (> (count @members) 1)
       [:li {:on-click on-leave-clicked}  (tr "dashboard.leave-team")])


     (when (:is-owner team)
       [:li {:on-click on-delete-clicked} (tr "dashboard.delete-team")])]))


(mf/defc sidebar-team-switch
  [{:keys [team profile] :as props}]
  (let [show-dropdown? (mf/use-state false)

        show-team-opts-ddwn? (mf/use-state false)
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
           [:img {:src (cfg/resolve-team-photo-url team)}]]
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
         (st/emitf (rt/nav :dashboard-projects {:team-id (:id team)})))

        go-fonts
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-fonts {:team-id (:id team)})))

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
         (st/emitf (rt/nav :dashboard-libraries {:team-id (:id team)})))

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
       [:li.recent-projects
        {:on-click go-fonts
         :class-name (when fonts? "current")}
        [:span.element-title (tr "labels.fonts")]]]]

     [:hr]
     [:div.sidebar-content-section
      (if (seq pinned-projects)
        [:ul.sidebar-nav
         (for [item pinned-projects]
           [:& sidebar-project
            {:item item
             :key (:id item)
             :id (:id item)
             :team-id (:id team)
             :selected? (= (:id item) (:id project))}])]
        [:div.sidebar-empty-placeholder
         [:span.icon i/pin]
         [:span.text (tr "dashboard.no-projects-placeholder")]])]]))


(mf/defc profile-section
  [{:keys [profile team] :as props}]
  (let [show  (mf/use-state false)
        photo (cfg/resolve-profile-photo-url profile)

        on-click
        (mf/use-callback
         (fn [section event]
           (dom/stop-propagation event)
           (if (keyword? section)
             (st/emit! (rt/nav section))
             (st/emit! section))))]

    [:div.profile-section
     [:div.profile {:on-click #(reset! show true)}
      [:img {:src photo}]
      [:span (:fullname profile)]

     [:& dropdown {:on-close #(reset! show false)
                   :show @show}
      [:ul.dropdown
       [:li {:on-click (partial on-click :settings-profile)}
        [:span.icon i/user]
        [:span.text (tr "labels.profile")]]
       [:li {:on-click (partial on-click :settings-password)}
        [:span.icon i/lock]
        [:span.text (tr "labels.password")]]
       [:li {:on-click (partial on-click (du/logout))}
        [:span.icon i/exit]
        [:span.text (tr "labels.logout")]]

       (when cfg/feedback-enabled
         [:li.feedback {:on-click (partial on-click :settings-feedback)}
          [:span.icon i/msg-info]
          [:span.text (tr "labels.give-feedback")]
          [:span.primary-badge "ALPHA"]])]]]

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
