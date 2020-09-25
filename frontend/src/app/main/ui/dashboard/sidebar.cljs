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
   [app.main.constants :as c]
   [app.main.data.auth :as da]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.forms :refer [input submit-button form]]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.modal :as modal]
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

(mf/defc sidebar-project-edition
  [{:keys [item on-end] :as props}]
  (let [name      (mf/use-state (:name item))
        input-ref (mf/use-ref)

        on-input
        (mf/use-callback
         (fn [event]
           (->> event
                (dom/get-target)
                (dom/get-value)
                (reset! name))))

        on-cancel
        (mf/use-callback
         (fn []
           (st/emit! dd/clear-project-for-edit)
           (on-end)))

        on-keyup
        (mf/use-callback
         (fn [event]
           (cond
             (kbd/esc? event)
             (on-cancel)

             (kbd/enter? event)
             (let [name (-> event
                            dom/get-target
                            dom/get-value)]
               (st/emit! dd/clear-project-for-edit
                         (dd/rename-project (assoc item :name name)))
               (on-end)))))]

    (mf/use-effect
     (fn []
       (let [node (mf/ref-val input-ref)]
         (dom/focus! node)
         (dom/select-text! node))))

    [:div.edit-wrapper
     [:input.element-title {:value @name
                            :ref input-ref
                            :on-change on-input
                            :on-key-down on-keyup}]
     [:span.close {:on-click on-cancel} i/close]]))



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
        (mf/use-callback #(reset! edition? true))]

    [:li {:on-click on-click
          :on-double-click on-dbl-click
          :class (when selected? "current")}
     (if @edition?
       [:& sidebar-project-edition {:item item
                                    :on-end #(reset! edition? false)}]
       [:span.element-title (:name item)])]))


(mf/defc sidebar-search
  [{:keys [search-term team-id locale] :as props}]
  (let [search-term (or search-term "")

        emit! (mf/use-memo #(f/debounce st/emit! 500))

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
       :placeholder (t locale "ds.search.placeholder")
       :default-value search-term
       :auto-complete "off"
       :on-focus on-search-focus
       :on-change on-search-change
       :ref #(when % (set! (.-value %) search-term))}]
     [:div.clear-search
      {:on-click on-clear-click}
      i/close]]))

(mf/defc sidebar-team-switch
  [{:keys [team profile] :as props}]
  (let [show-dropdown? (mf/use-state false)

        show-team-opts-ddwn? (mf/use-state false)
        show-teams-ddwn?     (mf/use-state false)
        teams                (mf/use-state [])

        on-nav
        (mf/use-callback #(st/emit! (rt/nav :dashboard-projects {:team-id %})))

        on-create-clicked
        (mf/use-callback #(modal/show! :team-form {}))]

    (mf/use-effect
     (mf/deps (:id teams))
     (fn []
       (->> (rp/query! :teams)
            (rx/subs #(reset! teams %)))))

    [:div.sidebar-team-switch
     [:div.switch-content
      [:div.current-team
       [:div.team-name
        [:span.team-icon i/logo-icon]
        (if (:is-default team)
          [:span.team-text "Your penpot"]
          [:span.team-text (:name team)])]
       [:span.switch-icon {:on-click #(reset! show-teams-ddwn? true)}
        i/arrow-down]]
      (when-not (:is-default team)
        [:div.switch-options {:on-click #(reset! show-team-opts-ddwn? true)}
         i/actions])]

     ;; Teams Dropdown
     [:& dropdown {:show @show-teams-ddwn?
                   :on-close #(reset! show-teams-ddwn? false)}
      [:ul.dropdown.teams-dropdown
       [:li.title "Switch Team"]
       [:hr]
       [:li.team-item {:on-click (partial on-nav (:default-team-id profile))}
        [:span.icon i/logo-icon]
        [:span.text "Your penpot"]]

       (for [team (remove :is-default @teams)]
         [:* {:key (:id team)}
          [:hr]
          [:li.team-item {:on-click (partial on-nav (:id team))}
           [:span.icon i/logo-icon]
           [:span.text (:name team)]]])

       [:hr]
       [:li.action {:on-click on-create-clicked}
        "+ Create new team"]]]

     [:& dropdown {:show @show-team-opts-ddwn?
                   :on-close #(reset! show-team-opts-ddwn? false)}
      [:ul.dropdown.options-dropdown
       [:li "Members"]
       [:li "Settings"]
       [:hr]
       [:li "Rename"]
       [:li "Leave team"]
       [:li "Delete team"]]]
     ]))

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(mf/defc team-form-modal
  {::mf/register modal/components
   ::mf/register-as :team-form}
  [props]
  (let [locale (mf/deref i18n/locale)

        on-success
        (mf/use-callback
         (fn [form response]
           (modal/hide!)
           (let [msg "Team created successfuly"]
             (st/emit!
              (dm/success msg)
              (rt/nav :dashboard-projects {:team-id (:id response)})))))

        on-error
        (mf/use-callback
         (fn [form response]
           (let [msg "Error on creating team."]
             (st/emit! (dm/error msg)))))

        on-submit
        (mf/use-callback
         (fn [form]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:name (get-in form [:clean-data :name])}]
             (st/emit! (dd/create-team (with-meta params mdata))))))]

    [:div.modal-overlay
     [:div.generic-modal.team-form-modal
      [:span.close {:on-click #(modal/hide!)} i/close]
      [:section.modal-content.generic-form
       [:h2 "CREATE NEW TEAM"]

       [:& form {:on-submit on-submit
                 :spec ::team-form
                 :initial {}}

        [:& input {:type "text"
                   :name :name
                   :label "Enter new team name:"}]

        [:div.buttons-row
         [:& submit-button
          {:label "Create team"}]]]]]]))


(mf/defc sidebar-content
  [{:keys [locale projects profile section team project search-term] :as props}]
  (let [default-project-id
        (->> (vals projects)
             (d/seek :is-default)
             (:id))

        team-id     (:id team)
        projects?   (= section :dashboard-projects)
        libs?       (= section :dashboard-libraries)
        drafts?     (and (= section :dashboard-files)
                         (= (:id project) default-project-id))

        go-projects #(st/emit! (rt/nav :dashboard-projects {:team-id (:id team)}))
        go-default  #(st/emit! (rt/nav :dashboard-files {:team-id (:id team) :project-id default-project-id}))
        go-libs     #(st/emit! (rt/nav :dashboard-libraries {:team-id (:id team)}))

        pinned-projects
        (->> (vals projects)
             (remove :is-default)
             (filter :is-pinned))]

    [:div.sidebar-content
     [:& sidebar-team-switch {:team team :profile profile}]

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
        [:span.element-title (t locale "dashboard.sidebar.projects")]]

       [:li {:on-click go-default
             :class-name (when drafts? "current")}
        i/file-html
        [:span.element-title (t locale "dashboard.sidebar.drafts")]]


       [:li {:on-click go-libs
             :class-name (when libs? "current")}
        i/library
        [:span.element-title (t locale "dashboard.sidebar.libraries")]]]]

     [:hr]

     [:div.sidebar-content-section
      (if (seq pinned-projects)
        [:ul.sidebar-nav
         (for [item pinned-projects]
           [:& sidebar-project
            {:item item
             :id (:id item)
             :selected? (= (:id item) (:id project))}])]
        [:div.sidebar-empty-placeholder
         [:span.icon i/pin]
         [:span.text "Pinned projects will appear here"]])]]))


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
        [:span.text (t locale "dashboard.header.profile-menu.profile")]]
       [:hr]
       [:li {:on-click (partial on-click :settings-password)}
        [:span.icon i/lock]
        [:span.text (t locale "dashboard.header.profile-menu.password")]]
       [:hr]
       [:li {:on-click (partial on-click da/logout)}
        [:span.icon i/exit]
        [:span.text (t locale "dashboard.header.profile-menu.logout")]]]]]))

(mf/defc sidebar
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [locale  (mf/deref i18n/locale)
        profile (mf/deref refs/profile)
        props   (-> (obj/clone props)
                    (obj/set! "locale" locale)
                    (obj/set! "profile" profile))]

    [:div.dashboard-sidebar
     [:div.sidebar-inside
      [:> sidebar-content props]
      [:& profile-section {:profile profile
                           :locale locale}]]]))


