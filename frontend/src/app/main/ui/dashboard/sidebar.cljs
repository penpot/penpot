;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.sidebar
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.auth :as da]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.data.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
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
  [{:keys [item selected?] :as props}]
  (let [dstate    (mf/deref refs/dashboard-local)
        edit-id   (:project-for-edit dstate)

        edition?  (mf/use-state (= (:id item) edit-id))

        on-click
        (mf/use-callback
         (mf/deps item)
         (fn []
           (st/emit! (rt/nav :dashboard-files {:team-id (:team-id item)
                                               :project-id (:id item)}))))

        on-dbl-click
        (mf/use-callback #(reset! edition? true))

        on-edit
        (mf/use-callback
         (mf/deps item)
         (fn [name]
           (st/emit! (dd/rename-project (assoc item :name name)))
           (reset! edition? false)))]

    [:li {:on-click on-click
          :on-double-click on-dbl-click
          :class (when selected? "current")}
     (if @edition?
       [:& inline-edition {:content (:name item)
                           :on-end on-edit}]
       [:span.element-title (:name item)])]))

(mf/defc sidebar-search
  [{:keys [search-term team-id locale] :as props}]
  (let [search-term (or search-term "")
        emit!       (mf/use-memo #(f/debounce st/emit! 500))

        on-search-focus
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [target (dom/get-target event)
                 value (dom/get-value target)]
             (dom/select-text! target)
             (if (empty? value)
               (emit! (rt/nav :dashboard-search {:team-id team-id} {}))
               (emit! (rt/nav :dashboard-search {:team-id team-id} {:search-term value}))))))

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
       :placeholder (t locale "dashboard.search-placeholder")
       :default-value search-term
       :auto-complete "off"
       :on-focus on-search-focus
       :on-change on-search-change
       :ref #(when % (set! (.-value %) search-term))}]
     [:div.clear-search
      {:on-click on-clear-click}
      i/close]]))

(mf/defc teams-selector-dropdown
  [{:keys [team profile locale] :as props}]
  (let [show-dropdown? (mf/use-state false)
        teams          (mf/use-state [])

        on-create-clicked
        (mf/use-callback
         (st/emitf (modal/show :team-form {})))

        go-projects
        (mf/use-callback #(st/emit! (rt/nav :dashboard-projects {:team-id %})))]

    (mf/use-layout-effect
     (mf/deps (:id team))
     (fn []
       (->> (rp/query! :teams)
            (rx/map #(mapv dd/assoc-team-avatar %))
            (rx/subs #(reset! teams %)))))

    [:ul.dropdown.teams-dropdown
     [:li.title (t locale "dashboard.switch-team")]
     [:hr]
     [:li.team-name {:on-click (partial go-projects (:default-team-id profile))}
      [:span.team-icon i/logo-icon]
      [:span.team-text (t locale "dashboard.your-penpot")]]

     (for [team (remove :is-default @teams)]
       [:* {:key (:id team)}
        [:li.team-name {:on-click (partial go-projects (:id team))}
         [:span.team-icon
          [:img {:src (cfg/resolve-media-path (:photo team))}]]
         [:span.team-text {:title (:name team)} (:name team)]]])

     [:hr]
     [:li.action {:on-click on-create-clicked}
      (t locale "dashboard.create-new-team")]]))

(s/def ::member-id ::us/uuid)
(s/def ::leave-modal-form
  (s/keys :req-un [::member-id]))

(mf/defc leave-and-reassign-modal
  {::mf/register modal/components
   ::mf/register-as ::leave-and-reassign}
  [{:keys [members profile team accept]}]
  (let [form    (fm/use-form :spec ::leave-modal-form :initial {})
        options (into [{:value "" :label (tr "modals.leave-and-reassign.select-memeber-to-promote")}]
                      (map #(hash-map :name (:name %) :value (str (:id %))) members))

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
       [:p (tr "modals.leave-and-reassign.hint2")]

       [:& fm/form {:form form}
        [:& fm/select {:name :member-id
                       :options options}]]]

      [:div.modal-footer
       [:div.action-buttons
        [:input.cancel-button
         {:type "button"
          :value (tr "labels.cancel")
          :on-click on-cancel}]

        [:input.accept-button
         {:type "button"
          :class (when-not (:valid @form) "btn-disabled")
          :disabled (not (:valid @form))
          :value (tr "modals.leave-and-reassign.promote-and-leave")
          :on-click on-accept}]]]]]))


(mf/defc team-options-dropdown
  [{:keys [team locale profile] :as props}]
  (let [members (mf/use-state [])

        go-members
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-team-members {:team-id (:id team)})))

        go-settings
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-team-settings {:team-id (:id team)})))

        go-projects
        (mf/use-callback #(st/emit! (rt/nav :dashboard-projects {:team-id %})))

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
         (st/emitf (rt/nav :dashboard-projects {:team-id (:default-team-id profile)})))

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
                     :title (t locale "modals.leave-confirm.title")
                     :message (t locale "modals.leave-confirm.message")
                     :accept-label (t locale "modals.leave-confirm.accept")
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
                     :title (t locale "modals.delete-team-confirm.title")
                     :message (t locale "modals.delete-team-confirm.message")
                     :accept-label (t locale "modals.delete-team-confirm.accept")
                     :on-accept delete-fn})))]

    (mf/use-layout-effect
     (mf/deps (:id team))
     (fn []
       (->> (rp/query! :team-members {:team-id (:id team)})
            (rx/subs #(reset! members %)))))

    [:ul.dropdown.options-dropdown
     [:li {:on-click go-members} (t locale "labels.members")]
     [:li {:on-click go-settings} (t locale "labels.settings")]
     [:hr]
     [:li {:on-click on-rename-clicked} (t locale "labels.rename")]

     (cond
       (:is-owner team)
       [:li {:on-click on-leave-as-owner-clicked} (t locale "dashboard.leave-team")]

       (> (count @members) 1)
       [:li {:on-click on-leave-clicked}  (t locale "dashboard.leave-team")])


     (when (:is-owner team)
       [:li {:on-click on-delete-clicked} (t locale "dashboard.delete-team")])]))


(mf/defc sidebar-team-switch
  [{:keys [team profile locale] :as props}]
  (let [show-dropdown? (mf/use-state false)

        show-team-opts-ddwn? (mf/use-state false)
        show-teams-ddwn?     (mf/use-state false)]

    [:div.sidebar-team-switch
     [:div.switch-content
      [:div.current-team
       (if (:is-default team)
         [:div.team-name
          [:span.team-icon i/logo-icon]
          [:span.team-text (t locale "dashboard.default-team-name")]]
         [:div.team-name
          [:span.team-icon
           [:img {:src (cfg/resolve-media-path (:photo team))}]]
          [:span.team-text {:title (:name team)} (:name team)]])

       [:span.switch-icon {:on-click #(reset! show-teams-ddwn? true)}
        i/arrow-down]]

      (when-not (:is-default team)
        [:div.switch-options {:on-click #(reset! show-team-opts-ddwn? true)}
         i/actions])]

     ;; Teams Dropdown
     [:& dropdown {:show @show-teams-ddwn?
                   :on-close #(reset! show-teams-ddwn? false)}
      [:& teams-selector-dropdown {:team team
                                   :profile profile
                                   :locale locale}]]

     [:& dropdown {:show @show-team-opts-ddwn?
                   :on-close #(reset! show-team-opts-ddwn? false)}
      [:& team-options-dropdown {:team team
                                 :profile profile
                                 :locale locale}]]]))

(mf/defc sidebar-content
  [{:keys [locale projects profile section team project search-term] :as props}]
  (let [default-project-id
        (->> (vals projects)
             (d/seek :is-default)
             (:id))

        projects?   (= section :dashboard-projects)
        libs?       (= section :dashboard-libraries)
        drafts?     (and (= section :dashboard-files)
                         (= (:id project) default-project-id))

        go-projects
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-projects {:team-id (:id team)})))

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
     [:& sidebar-team-switch {:team team :profile profile :locale locale}]
     [:hr]
     [:& sidebar-search {:search-term search-term
                         :team-id (:id team)
                         :locale locale}]
     [:div.sidebar-content-section
      [:ul.sidebar-nav.no-overflow
       [:li.recent-projects
        {:on-click go-projects
         :class-name (when projects? "current")}
        i/recent
        [:span.element-title (t locale "labels.projects")]]

       [:li {:on-click go-drafts
             :class-name (when drafts? "current")}
        i/file-html
        [:span.element-title (t locale "labels.drafts")]]


       [:li {:on-click go-libs
             :class-name (when libs? "current")}
        i/library
        [:span.element-title (t locale "labels.shared-libraries")]]]]

     [:hr]

     [:div.sidebar-content-section
      (if (seq pinned-projects)
        [:ul.sidebar-nav
         (for [item pinned-projects]
           [:& sidebar-project
            {:item item
             :key (:id item)
             :id (:id item)
             :selected? (= (:id item) (:id project))}])]
        [:div.sidebar-empty-placeholder
         [:span.icon i/pin]
         [:span.text (t locale "dashboard.no-projects-placeholder")]])]]))


(mf/defc profile-section
  [{:keys [profile locale] :as props}]
  (let [show  (mf/use-state false)
        photo (:photo-uri profile "")
        photo (if (str/empty? photo)
                "/images/avatar.jpg"
                photo)

        on-click
        (mf/use-callback
         (fn [section event]
           (dom/stop-propagation event)
           (if (keyword? section)
             (st/emit! (rt/nav section))
             (st/emit! section))))]

    [:div.profile-section {:on-click #(reset! show true)}
     [:img {:src photo}]
     [:span (:fullname profile)]

     [:& dropdown {:on-close #(reset! show false)
                   :show @show}
      [:ul.dropdown
       [:li {:on-click (partial on-click :settings-profile)}
        [:span.icon i/user]
        [:span.text (t locale "labels.profile")]]
       [:li {:on-click (partial on-click :settings-password)}
        [:span.icon i/lock]
        [:span.text (t locale "labels.password")]]
       [:li {:on-click (partial on-click da/logout)}
        [:span.icon i/exit]
        [:span.text (t locale "labels.logout")]]]]]))

(mf/defc sidebar
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [locale  (mf/deref i18n/locale)
        profile (obj/get props "profile")
        props   (-> (obj/clone props)
                    (obj/set! "locale" locale))]
    [:div.dashboard-sidebar
     [:div.sidebar-inside
      [:> sidebar-content props]
      [:& profile-section {:profile profile :locale locale}]]]))
