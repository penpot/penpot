;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.projects
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.geom.point :as gpt]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [line-grid]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.pin-button :refer [pin-button*]]
   [app.main.ui.dashboard.project-menu :refer [project-menu]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [app.util.storage :as storage]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private show-more-icon
  (i/icon-xref :arrow (stl/css :show-more-icon)))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(def ^:private add-icon
  (i/icon-xref :add (stl/css :add-icon)))

(def ^:private menu-icon
  (i/icon-xref :menu (stl/css :menu-icon)))

(mf/defc header
  {::mf/wrap [mf/memo]}
  []
  (let [on-click (mf/use-fn #(st/emit! (dd/create-project)))]
    [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
     [:div#dashboard-projects-title {:class (stl/css :dashboard-title)}
      [:h1 (tr "dashboard.projects-title")]]
     [:button {:class (stl/css :btn-secondary :btn-small)
               :on-click on-click
               :data-testid "new-project-button"}
      (tr "dashboard.new-project")]]))

(mf/defc team-hero*
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [team on-close]}]
  (let [on-nav-members-click (mf/use-fn #(st/emit! (dd/go-to-team-members)))

        on-invite
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit! (modal/show {:type :invite-members
                                  :team team
                                  :origin :hero}))))
        on-close'
        (mf/use-fn
         (mf/deps on-close)
         (fn [event]
           (dom/prevent-default event)
           (on-close event)))]

    [:div {:class (stl/css :team-hero)}
     [:div {:class (stl/css :img-wrapper)}
      [:img {:src "images/deco-team-banner.png"
             :border "0"
             :role "presentation"}]]
     [:div {:class (stl/css :text)}
      [:div {:class (stl/css :title)} (tr "dasboard.team-hero.title")]
      [:div {:class (stl/css :info)}
       [:span (tr "dasboard.team-hero.text")]
       [:a {:on-click on-nav-members-click} (tr "dasboard.team-hero.management")]]
      [:button
       {:class (stl/css :btn-primary :invite)
        :on-click on-invite}
       (tr "onboarding.choice.team-up.invite-members")]]

     [:button {:class (stl/css :close)
               :on-click on-close'
               :aria-label (tr "labels.close")}
      close-icon]]))

(def builtin-templates
  (l/derived :builtin-templates st/state))

(mf/defc project-item
  [{:keys [project first? team files] :as props}]
  (let [locale     (mf/deref i18n/locale)
        file-count (or (:count project) 0)
        project-id (:id project)
        team-id    (:id team)

        dstate     (mf/deref refs/dashboard-local)
        edit-id    (:project-for-edit dstate)

        local      (mf/use-state {:menu-open false
                                  :menu-pos nil
                                  :edition? (= (:id project) edit-id)})

        [rowref limit] (hooks/use-dynamic-grid-item-width)
        on-nav
        (mf/use-fn
         (mf/deps project-id team-id)
         (fn []
           (st/emit! (rt/nav :dashboard-files
                             {:team-id team-id
                              :project-id project-id}))))
        toggle-pin
        (mf/use-fn
         (mf/deps project)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dd/toggle-project-pin project))))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)

           (let [client-position (dom/get-client-position event)
                 position (if (and (nil? (:y client-position)) (nil? (:x client-position)))
                            (let [target-element (dom/get-target event)
                                  points         (dom/get-bounding-rect target-element)
                                  y              (:top points)
                                  x              (:left points)]
                              (gpt/point x y))
                            client-position)]
             (swap! local assoc
                    :menu-open true
                    :menu-pos position))))

        on-menu-close
        (mf/use-fn #(swap! local assoc :menu-open false))

        on-edit-open
        (mf/use-fn #(swap! local assoc :edition? true))

        on-edit
        (mf/use-fn
         (mf/deps project)
         (fn [name]
           (let [name (str/trim name)]
             (when-not (str/empty? name)
               (st/emit! (-> (dd/rename-project (assoc project :name name))
                             (with-meta {::ev/origin "dashboard"}))))
             (swap! local assoc :edition? false))))

        on-file-created
        (mf/use-fn
         (fn [data]
           (let [pparams {:project-id (:project-id data)
                          :file-id (:id data)}
                 qparams {:page-id (get-in data [:data :pages 0])}]
             (st/emit! (rt/nav :workspace pparams qparams)))))

        create-file
        (mf/use-fn
         (mf/deps project-id on-file-created)
         (fn [origin]
           (let [mdata  {:on-success on-file-created}
                 params {:project-id project-id}]
             (st/emit! (-> (dd/create-file (with-meta params mdata))
                           (with-meta {::ev/origin origin}))))))

        on-create-click
        (mf/use-fn
         (mf/deps create-file)
         (fn [_]
           (create-file "dashboard:grid-header-plus-button")))

        on-import
        (mf/use-fn
         (mf/deps project-id (:id team))
         (fn []
           (st/emit! (dd/fetch-files {:project-id project-id})
                     (dd/fetch-recent-files (:id team))
                     (dd/fetch-projects (:id team))
                     (dd/clear-selected-files))))

        handle-create-click
        (mf/use-callback
         (mf/deps on-create-click)
         (fn [event]
           (when (kbd/enter? event)
             (on-create-click event))))


        handle-menu-click
        (mf/use-callback
         (mf/deps on-menu-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-menu-click event))))
        title-width (/ 100 limit)]

    [:article {:class (stl/css-case :dashboard-project-row true :first first?)}
     [:header {:class (stl/css :project)}
      [:div {:class (stl/css :project-name-wrapper)}
       (if (:edition? @local)
         [:& inline-edition {:content (:name project)
                             :on-end on-edit}]
         [:h2 {:on-click on-nav
               :style {:max-width (str title-width "%")}
               :class (stl/css :project-name)
               :title (if (:is-default project)
                        (tr "labels.drafts")
                        (:name project))
               :on-context-menu on-menu-click}
          (if (:is-default project)
            (tr "labels.drafts")
            (:name project))])

       [:div {:class (stl/css :info-wrapper)}
        [:& project-menu
         {:project project
          :show? (:menu-open @local)
          :left (+ 24 (:x (:menu-pos @local)))
          :top (:y (:menu-pos @local))
          :on-edit on-edit-open
          :on-menu-close on-menu-close
          :on-import on-import}]

        ;; We group these two spans under a div to avoid having extra space between them.
        [:div
         [:span {:class (stl/css :info)} (str (tr "labels.num-of-files" (i18n/c file-count)))]

         (let [time (-> (:modified-at project)
                        (dt/timeago {:locale locale}))]
           [:span {:class (stl/css :recent-files-row-title-info)} (str ", " time)])]

        [:div {:class (stl/css-case :project-actions true
                                    :pinned-project (:is-pinned project))}
         (when-not (:is-default project)
           [:> pin-button* {:class (stl/css :pin-button) :is-pinned (:is-pinned project) :on-click toggle-pin :tab-index 0}])

         [:button {:class (stl/css :add-file-btn)
                   :on-click on-create-click
                   :title (tr "dashboard.new-file")
                   :aria-label (tr "dashboard.new-file")
                   :data-testid "project-new-file"
                   :on-key-down handle-create-click}
          add-icon]

         [:button {:class (stl/css :options-btn)
                   :on-click on-menu-click
                   :title (tr "dashboard.options")
                   :aria-label  (tr "dashboard.options")
                   :data-testid "project-options"
                   :on-key-down handle-menu-click}
          menu-icon]]]]]

     [:div {:class (stl/css :grid-container) :ref rowref}
      [:& line-grid
       {:project project
        :team team
        :files files
        :create-fn create-file
        :limit limit}]]

     (when (and (> limit 0)
                (> file-count limit))
       [:button {:class (stl/css :show-more)
                 :on-click on-nav
                 :tab-index "0"
                 :on-key-down (fn [event]
                                (when (kbd/enter? event)
                                  (on-nav)))}
        [:span {:class (stl/css :placeholder-label)} (tr "dashboard.show-all-files")]
        show-more-icon])]))


(def recent-files-ref
  (l/derived :dashboard-recent-files st/state))

(mf/defc projects-section
  [{:keys [team projects profile] :as props}]
  (let [projects            (->> (vals projects)
                                 (sort-by :modified-at)
                                 (reverse))
        recent-map          (mf/deref recent-files-ref)
        you-owner?          (get-in team [:permissions :is-owner])
        you-admin?          (get-in team [:permissions :is-admin])
        can-invite?         (or you-owner? you-admin?)

        show-team-hero*     (mf/use-state #(get storage/global ::show-team-hero true))
        show-team-hero?     (deref show-team-hero*)

        is-my-penpot        (= (:default-team-id profile) (:id team))
        is-defalt-team?     (:is-default team)

        team-id             (:id team)

        on-close
        (mf/use-fn
         (fn []
           (reset! show-team-hero* false)
           (st/emit! (ptk/data-event ::ev/event {::ev/name "dont-show-team-up-hero"
                                                 ::ev/origin "dashboard"}))))]

    (mf/with-effect [show-team-hero?]
      (swap! storage/global assoc ::show-team-hero show-team-hero?))

    (mf/with-effect [team]
      (let [tname (if (:is-default team)
                    (tr "dashboard.your-penpot")
                    (:name team))]
        (dom/set-html-title (tr "title.dashboard.projects" tname))))

    (mf/with-effect [team-id]
      (st/emit! (dd/fetch-recent-files team-id)
                (dd/clear-selected-files)))

    (when (seq projects)
      [:*
       [:& header]
       [:div {:class (stl/css :projects-container)}
        [:*
         (when (and show-team-hero?
                    can-invite?
                    (not is-defalt-team?))
           [:> team-hero* {:team team :on-close on-close}])

         [:div {:class (stl/css-case :dashboard-container true
                                     :no-bg true
                                     :dashboard-projects true
                                     :with-team-hero (and (not is-my-penpot)
                                                          (not is-defalt-team?)
                                                          show-team-hero?
                                                          can-invite?))}
          (for [{:keys [id] :as project} projects]
            (let [files (when recent-map
                          (->> (vals recent-map)
                               (filterv #(= id (:project-id %)))
                               (sort-by :modified-at #(compare %2 %1))))]
              [:& project-item {:project project
                                :team team
                                :files files
                                :first? (= project (first projects))
                                :key id}]))]]]])))
