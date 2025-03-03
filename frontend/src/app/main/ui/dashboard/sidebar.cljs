;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
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
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu dropdown-menu-item*]]
   [app.main.ui.components.link :refer [link]]
   [app.main.ui.dashboard.comments :refer [comments-icon* comments-section]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu*]]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.icons :as i :refer [icon-xref]]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [goog.functions :as f]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private clear-search-icon
  (icon-xref :delete-text (stl/css :clear-search-icon)))

(def ^:private search-icon
  (icon-xref :search (stl/css :search-icon)))

(def ^:private tick-icon
  (icon-xref :tick (stl/css :tick-icon)))

(def ^:private logo-icon
  (icon-xref :logo (stl/css :logo-icon)))

(def ^:private add-icon
  (icon-xref :add (stl/css :add-icon)))

(def ^:private arrow-icon
  (icon-xref :arrow (stl/css :arrow-icon)))

(def ^:private menu-icon
  (icon-xref :menu (stl/css :menu-icon)))

(def ^:private pin-icon
  (icon-xref :pin (stl/css :pin-icon)))

(def ^:private exit-icon
  (icon-xref :exit (stl/css :exit-icon)))

(mf/defc sidebar-project
  [{:keys [item selected?] :as props}]
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
                                :current selected?
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
                        :on-menu-close on-menu-close}]]))

(mf/defc sidebar-search
  [{:keys [search-term team-id] :as props}]
  (let [search-term (or search-term "")
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

(mf/defc teams-selector-dropdown-items
  {::mf/wrap-props false}
  [{:keys [team profile teams] :as props}]
  (let [on-create-clicked
        (mf/use-fn
         #(st/emit! (modal/show :team-form {})))

        team-selected
        (mf/use-fn
         (fn [event]
           (let [team-id (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (uuid/parse))]

             (st/emit! (dcm/go-to-dashboard-recent :team-id team-id)))))

        handle-select-default
        (mf/use-fn
         (mf/deps profile team-selected)
         (fn [event]
           (when (kbd/enter? event)
             (team-selected (:default-team-id profile) event))))

        handle-select-team
        (mf/use-fn
         (mf/deps team-selected)
         (fn [event]
           (when (kbd/enter? event)
             (team-selected event))))

        handle-creation-key-down
        (mf/use-fn
         (mf/deps on-create-clicked)
         (fn [event]
           (when (kbd/enter? event)
             (on-create-clicked event))))]

    [:*
     [:> dropdown-menu-item* {:on-click    team-selected
                              :data-value  (:default-team-id profile)
                              :on-key-down handle-select-default
                              :id          "teams-selector-default-team"
                              :class       (stl/css :team-dropdown-item)}
      [:span {:class (stl/css :penpot-icon)} i/logo-icon]

      [:span {:class (stl/css :team-text)} (tr "dashboard.your-penpot")]
      (when (= (:default-team-id profile) (:id team))
        tick-icon)]

     (for [team-item (remove :is-default (vals teams))]
       [:> dropdown-menu-item* {:on-click    team-selected
                                :data-value  (:id team-item)
                                :on-key-down handle-select-team
                                :id          (str "teams-selector-" (:id team-item))
                                :class       (stl/css :team-dropdown-item)
                                :key         (str "teams-selector-" (:id team-item))}
        [:img {:src (cf/resolve-team-photo-url team-item)
               :class (stl/css :team-picture)
               :alt (:name team-item)}]
        [:span {:class (stl/css :team-text)
                :title (:name team-item)} (:name team-item)]
        (when (= (:id team-item) (:id team))
          tick-icon)])

     [:hr {:role "separator"
           :class (stl/css :team-separator)}]
     [:> dropdown-menu-item* {:on-click    on-create-clicked
                              :on-key-down handle-creation-key-down
                              :id          "teams-selector-create-team"
                              :class       (stl/css :team-dropdown-item :action)}
      [:span {:class (stl/css :icon-wrapper)} add-icon]
      [:span {:class (stl/css :team-text)} (tr "dashboard.create-new-team")]]]))

(s/def ::member-id ::us/uuid)
(s/def ::leave-modal-form
  (s/keys :req-un [::member-id]))

(mf/defc team-options-dropdown
  [{:keys [team profile] :as props}]
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
             :on-accept delete-fn})))

        handle-members
        (mf/use-fn
         (mf/deps go-members)
         (fn [event]
           (when (kbd/enter? event)
             (go-members))))

        handle-invitations
        (mf/use-fn
         (mf/deps go-invitations)
         (fn [event]
           (when (kbd/enter? event)
             (go-invitations))))

        handle-webhooks
        (mf/use-fn
         (mf/deps go-webhooks)
         (fn [event]
           (when (kbd/enter? event)
             (go-webhooks))))

        handle-settings
        (mf/use-fn
         (mf/deps go-settings)
         (fn [event]
           (when (kbd/enter? event)
             (go-settings))))


        handle-rename
        (mf/use-fn
         (mf/deps on-rename-clicked)
         (fn [event]
           (when (kbd/enter? event)
             (on-rename-clicked))))


        handle-leave-and-close
        (mf/use-fn
         (mf/deps leave-and-close)
         (fn [event]
           (when (kbd/enter? event)
             (leave-and-close))))

        handle-leave-as-owner-clicked
        (mf/use-fn
         (mf/deps on-leave-as-owner-clicked)
         (fn [event]
           (when (kbd/enter? event)
             (on-leave-as-owner-clicked))))


        handle-on-leave-clicked
        (mf/use-fn
         (mf/deps on-leave-clicked)
         (fn [event]
           (when (kbd/enter? event)
             (on-leave-clicked))))

        handle-on-delete-clicked
        (mf/use-fn
         (mf/deps on-delete-clicked)
         (fn [event]
           (when (kbd/enter? event)
             (on-delete-clicked))))]

    [:*
     [:> dropdown-menu-item* {:on-click    go-members
                              :on-key-down handle-members
                              :className   (stl/css :team-options-item)
                              :id          "teams-options-members"
                              :data-testid   "team-members"}
      (tr "labels.members")]
     [:> dropdown-menu-item* {:on-click    go-invitations
                              :on-key-down handle-invitations
                              :className   (stl/css :team-options-item)
                              :id          "teams-options-invitations"
                              :data-testid   "team-invitations"}
      (tr "labels.invitations")]

     (when (contains? cf/flags :webhooks)
       [:> dropdown-menu-item* {:on-click    go-webhooks
                                :on-key-down handle-webhooks
                                :className   (stl/css :team-options-item)
                                :id          "teams-options-webhooks"}
        (tr "labels.webhooks")])

     [:> dropdown-menu-item* {:on-click    go-settings
                              :on-key-down handle-settings
                              :className   (stl/css :team-options-item)
                              :id          "teams-options-settings"
                              :data-testid   "team-settings"}
      (tr "labels.settings")]

     [:hr {:class (stl/css :team-option-separator)}]
     (when can-rename?
       [:> dropdown-menu-item* {:on-click    on-rename-clicked
                                :on-key-down handle-rename
                                :id          "teams-options-rename"
                                :className   (stl/css :team-options-item)
                                :data-testid   "rename-team"}
        (tr "labels.rename")])

     (cond
       (= (count members) 1)
       [:> dropdown-menu-item* {:on-click    leave-and-close
                                :on-key-down handle-leave-and-close
                                :className   (stl/css :team-options-item)
                                :id          "teams-options-leave-team"}
        (tr "dashboard.leave-team")]


       (get-in team [:permissions :is-owner])
       [:> dropdown-menu-item* {:on-click    on-leave-as-owner-clicked
                                :on-key-down handle-leave-as-owner-clicked
                                :id          "teams-options-leave-team"
                                :className   (stl/css :team-options-item)
                                :data-testid   "leave-team"}
        (tr "dashboard.leave-team")]

       (> (count members) 1)
       [:> dropdown-menu-item* {:on-click    on-leave-clicked
                                :on-key-down handle-on-leave-clicked
                                :className   (stl/css :team-options-item)
                                :id          "teams-options-leave-team"}
        (tr "dashboard.leave-team")])

     (when (get-in team [:permissions :is-owner])
       [:> dropdown-menu-item* {:on-click    on-delete-clicked
                                :on-key-down handle-on-delete-clicked
                                :id          "teams-options-delete-team"
                                :className   (stl/css :team-options-item :warning)
                                :data-testid   "delete-team"}
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
                               (when (contains? cf/flags :webhooks)
                                 "teams-options-webhooks")
                               "teams-options-settings"
                               (when can-rename?
                                 "teams-options-rename")
                               "teams-options-leave-team"
                               (when (get-in team [:permissions :is-owner])
                                 "teams-options-delete-team")]


        ;; _ (prn "--------------- sidebar-team-switch")
        ;; _ (app.common.pprint/pprint teams)

        handle-show-team-click
        (fn [event]
          (dom/stop-propagation event)
          (swap! show-teams-ddwn? not)
          (reset! show-team-opts-ddwn? false))

        handle-show-team-keydown
        (fn [event]
          (when (or (kbd/space? event) (kbd/enter? event))
            (dom/prevent-default event)
            (reset! show-teams-ddwn? true)
            (reset! show-team-opts-ddwn? false)
            (ts/schedule-on-idle
             (fn []
               (let [first-element (dom/get-element (first ids))]
                 (when first-element
                   (dom/focus! first-element)))))))

        close-team-opts-ddwn
        (mf/use-fn
         #(reset! show-team-opts-ddwn? false))

        handle-show-opts-click
        (fn [event]
          (dom/stop-propagation event)
          (swap! show-team-opts-ddwn? not)
          (reset! show-teams-ddwn? false))

        handle-show-opts-keydown
        (fn [event]
          (when (or (kbd/space? event) (kbd/enter? event))
            (dom/prevent-default event)
            (reset! show-team-opts-ddwn? true)
            (reset! show-teams-ddwn? false)
            (ts/schedule-on-idle
             (fn []
               (let [first-element (dom/get-element (first options-ids))]
                 (when first-element
                   (dom/focus! first-element)))))))

        handle-close-team
        (fn []
          (reset! show-teams-ddwn? false))]

    [:div {:class (stl/css :sidebar-team-switch)}
     [:div {:class (stl/css :switch-content)}
      [:button {:class (stl/css :current-team)
                :on-click handle-show-team-click
                :on-key-down handle-show-team-keydown}

       (if (:is-default team)
         [:div {:class (stl/css :team-name)}
          [:span {:class (stl/css :penpot-icon)} i/logo-icon]
          [:span {:class (stl/css :team-text)} (tr "dashboard.default-team-name")]]

         [:div {:class (stl/css :team-name)}
          [:img {:src (cf/resolve-team-photo-url team)
                 :class (stl/css :team-picture)
                 :alt (:name team)}]
          [:span {:class (stl/css :team-text) :title (:name team)} (:name team)]])

       arrow-icon]

      (when-not (:is-default team)
        [:button {:class (stl/css :switch-options)
                  :on-click handle-show-opts-click
                  :aria-label "team-management"
                  :tab-index "0"
                  :on-key-down handle-show-opts-keydown}
         menu-icon])]

     ;; Teams Dropdown

     [:& dropdown-menu {:show @show-teams-ddwn?
                        :on-close handle-close-team
                        :ids ids
                        :list-class (stl/css :dropdown :teams-dropdown)}
      [:& teams-selector-dropdown-items {:ids ids
                                         :team team
                                         :profile profile
                                         :teams teams}]]

     [:& dropdown-menu {:show @show-team-opts-ddwn?
                        :on-close close-team-opts-ddwn
                        :ids options-ids
                        :list-class (stl/css :dropdown :options-dropdown)}
      [:& team-options-dropdown {:team team
                                 :profile profile}]]]))

(mf/defc sidebar-content*
  {::mf/private true
   ::mf/props :obj}
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
           (st/emit! (dcm/go-to-dashboard-recent :team-id team-id)
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
         #(st/emit! (dcm/go-to-dashboard-fonts :team-id team-id)
                    (ts/schedule-on-idle
                     (fn []
                       (let [font-title (dom/get-element "dashboard-fonts-title")]
                         (when font-title
                           (dom/set-attribute! font-title "tabindex" "0")
                           (dom/focus! font-title)
                           (dom/set-attribute! font-title "tabindex" "-1")))))))
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
         #(st/emit! (dcm/go-to-dashboard-libraries :team-id team-id)))

        go-libs-with-key
        (mf/use-fn
         (mf/deps team-id)
         #(st/emit! (dcm/go-to-dashboard-libraries :team-id team-id)
                    (ts/schedule-on-idle
                     (fn []
                       (let [libs-title (dom/get-element "dashboard-libraries-title")]
                         (when libs-title
                           (dom/set-attribute! libs-title "tabindex" "0")
                           (dom/focus! libs-title)
                           (dom/set-attribute! libs-title "tabindex" "-1")))))))
        pinned-projects
        (->> projects
             (remove :is-default)
             (filter :is-pinned))]

    (mf/use-layout-effect
     (mf/deps pinned-projects)
     (fn []
       (let [dom   (mf/ref-val container)
             client-height (obj/get dom "clientHeight")
             scroll-height (obj/get dom "scrollHeight")]
         (reset! overflow* (> scroll-height client-height)))))

    [:*
     [:div {:class (stl/css-case :sidebar-content true)
            :ref container}
      [:& sidebar-team-switch {:team team :profile profile}]

      [:& sidebar-search {:search-term search-term
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
          [:span {:class (stl/css :element-title)} (tr "labels.drafts")]]]


        [:li {:class (stl/css-case :current libs?
                                   :sidebar-nav-item true)}
         [:& link {:action go-libs
                   :data-testid "libs-link-sidebar"
                   :class (stl/css :sidebar-link)
                   :keyboard-action go-libs-with-key}
          [:span {:class (stl/css :element-title)} (tr "labels.shared-libraries")]]]]]


      [:div {:class (stl/css :sidebar-content-section)}
       [:ul {:class (stl/css :sidebar-nav)}
        [:li {:class (stl/css-case :sidebar-nav-item true
                                   :current fonts?)}
         [:& link {:action go-fonts
                   :class (stl/css :sidebar-link)
                   :keyboard-action go-fonts-with-key
                   :data-testid "fonts"}
          [:span {:class (stl/css :element-title)} (tr "labels.fonts")]]]]]


      [:div {:class (stl/css :sidebar-content-section)
             :data-testid "pinned-projects"}
       (if (seq pinned-projects)
         [:ul {:class (stl/css :sidebar-nav :pinned-projects)}
          (for [item pinned-projects]
            [:& sidebar-project
             {:item item
              :key (dm/str (:id item))
              :id (:id item)
              :team-id (:id team)
              :selected? (= (:id item) (:id project))}])]
         [:div {:class (stl/css :sidebar-empty-placeholder)}
          pin-icon
          [:span {:class (stl/css :empty-text)} (tr "dashboard.no-projects-placeholder")]])]]
     [:div {:class (stl/css-case :separator true :overflow-separator overflow?)}]]))

(mf/defc profile-section*
  {::mf/props :obj}
  [{:keys [profile team]}]
  (let [show*  (mf/use-state false)
        show   (deref show*)
        photo  (cf/resolve-profile-photo-url profile)

        on-click
        (mf/use-fn
         (fn [section event]
           (dom/stop-propagation event)
           (reset! show* false)
           (if (keyword? section)
             (st/emit! (rt/nav section))
             (st/emit! section))))

        show-release-notes
        (mf/use-fn
         (fn [event]
           (let [version (:main cf/version)]
             (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version version}))
             (if (and (kbd/alt? event) (kbd/mod? event))
               (st/emit! (modal/show {:type :onboarding}))
               (st/emit! (modal/show {:type :release-notes :version version}))))))

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
           (swap! show* not)))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (when (kbd/enter? event)
             (reset! show* true))))

        on-close
        (fn [event]
          (dom/stop-propagation event)
          (reset! show* false))

        handle-key-down-profile
        (mf/use-fn
         (fn [event]
           (when (kbd/enter? event)
             (on-click :settings-profile event))))

        handle-click-url
        (mf/use-fn
         (fn [event]
           (let [url (-> (dom/get-current-target event)
                         (dom/get-data "url"))]
             (dom/open-new-window url))))

        handle-keydown-url
        (mf/use-fn
         (fn [event]
           (let [url (-> (dom/get-current-target event)
                         (dom/get-data "url"))]
             (when (kbd/enter? event)
               (dom/open-new-window url)))))

        handle-show-release-notes
        (mf/use-fn
         (mf/deps show-release-notes)
         (fn [event]
           (when (kbd/enter? event)
             (show-release-notes))))

        handle-feedback-click
        (mf/use-fn #(on-click :settings-feedback %))

        handle-feedback-keydown
        (mf/use-fn
         (fn [event]
           (when (kbd/enter? event)
             (on-click :settings-feedback event))))

        handle-logout-click
        (mf/use-fn
         #(on-click (da/logout) %))

        handle-logout-keydown
        (mf/use-fn
         (fn [event]
           (when (kbd/enter? event)
             (on-click (da/logout) event))))

        handle-set-profile
        (mf/use-fn
         #(on-click :settings-profile %))

        on-power-up-click
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-pricing-click" ::ev/origin "dashboard" :section "sidebar"}))
           (dom/open-new-window "https://penpot.app/pricing")))]

    [:*
     [:button {:class (stl/css :upgrade-plan-section)
               :on-click on-power-up-click}
      [:div {:class (stl/css :penpot-free)}
       [:span (tr "dashboard.upgrade-plan.penpot-free")]
       [:span {:class (stl/css :no-limits)} (tr "dashboard.upgrade-plan.no-limits")]]
      [:div {:class (stl/css :power-up)}
       (tr "dashboard.upgrade-plan.power-up")]]
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

      [:& dropdown-menu {:on-close on-close
                         :show show
                         :list-class (stl/css :profile-dropdown)}
       [:li {:tab-index (if show "0" "-1")
             :class (stl/css :profile-dropdown-item)
             :on-click handle-set-profile
             :on-key-down handle-key-down-profile
             :data-testid "profile-profile-opt"}
        (tr "labels.your-account")]

       [:li {:class (stl/css :profile-separator)}]

       [:li {:class (stl/css :profile-dropdown-item)
             :tab-index (if show "0" "-1")
             :data-url "https://help.penpot.app"
             :on-click handle-click-url
             :on-key-down handle-keydown-url
             :data-testid "help-center-profile-opt"}
        (tr "labels.help-center")]

       [:li {:tab-index (if show "0" "-1")
             :class (stl/css :profile-dropdown-item)
             :data-url "https://community.penpot.app"
             :on-click handle-click-url
             :on-key-down handle-keydown-url}
        (tr "labels.community")]

       [:li {:tab-index (if show "0" "-1")
             :class (stl/css :profile-dropdown-item)
             :data-url "https://www.youtube.com/c/Penpot"
             :on-click handle-click-url
             :on-key-down handle-keydown-url}
        (tr "labels.tutorials")]

       [:li {:tab-index (if show "0" "-1")
             :class (stl/css :profile-dropdown-item)
             :on-click show-release-notes
             :on-key-down handle-show-release-notes}
        (tr "labels.release-notes")]

       [:li {:class (stl/css :profile-separator)}]

       [:li {:class     (stl/css :profile-dropdown-item)
             :tab-index (if show "0" "-1")
             :data-url "https://penpot.app/libraries-templates"
             :on-click handle-click-url
             :on-key-down handle-keydown-url
             :data-testid "libraries-templates-profile-opt"}
        (tr "labels.libraries-and-templates")]

       [:li {:tab-index (if show "0" "-1")
             :class (stl/css :profile-dropdown-item)
             :data-url "https://github.com/penpot/penpot"
             :on-click handle-click-url
             :on-key-down handle-keydown-url}
        (tr "labels.github-repo")]

       [:li {:tab-index (if show "0" "-1")
             :class (stl/css :profile-dropdown-item)
             :data-url "https://penpot.app/terms"
             :on-click handle-click-url
             :on-key-down handle-keydown-url}
        (tr "auth.terms-of-service")]

       [:li {:class (stl/css :profile-separator)}]

       (when (contains? cf/flags :user-feedback)
         [:li {:class (stl/css :profile-dropdown-item)
               :tab-index (if show "0" "-1")
               :on-click handle-feedback-click
               :on-key-down handle-feedback-keydown
               :data-testid "feedback-profile-opt"}
          (tr "labels.give-feedback")])

       [:li {:class (stl/css :profile-dropdown-item :item-with-icon)
             :tab-index (if show "0" "-1")
             :on-click handle-logout-click
             :on-key-down handle-logout-keydown
             :data-testid "logout-profile-opt"}
        exit-icon
        (tr "labels.logout")]]

      (when (and team profile)
        [:> comments-icon*
         {:profile profile
          :on-show-comments handle-show-comments}])]]))

(mf/defc sidebar*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [team profile] :as props}]
  [:nav {:class (stl/css :dashboard-sidebar) :data-testid "dashboard-sidebar"}
   [:> sidebar-content* props]
   [:> profile-section*
    {:profile profile
     :team team}]])

