;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.projects
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [line-grid]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  []
  (let [on-click (mf/use-fn #(st/emit! (dd/create-project)))]
    [:header.dashboard-header
     [:div.dashboard-title#dashboard-projects-title
      [:h1 (tr "dashboard.projects-title")]]
     [:button.btn-secondary.btn-small
      {:on-click on-click
       :data-test "new-project-button"}
      (tr "dashboard.new-project")]]))

(mf/defc team-hero
  {::mf/wrap [mf/memo]}
  [{:keys [team close-fn] :as props}]
  (let [on-nav-members-click (mf/use-fn #(st/emit! (dd/go-to-team-members)))

        on-invite-click
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit! (modal/show {:type :invite-members
                                  :team team
                                  :origin :hero}))))
        on-close-click
        (mf/use-fn
         (mf/deps close-fn)
         (fn [event]
           (dom/prevent-default event)
           (close-fn)))]

    [:div.team-hero
     [:img {:src "images/deco-team-banner.png" :border "0"
            :role "presentation"}]
     [:div.text
      [:div.title (tr "dasboard.team-hero.title")]
      [:div.info
       [:span (tr "dasboard.team-hero.text")]
       [:a {:on-click on-nav-members-click} (tr "dasboard.team-hero.management")]]]
     [:button.btn-primary.invite
      {:on-click on-invite-click}
      (tr "onboarding.choice.team-up.invite-members")]
     [:button.close
      {:on-click on-close-click
       :aria-label (tr "labels.close")}
      [:span i/close]]]))

(def builtin-templates
  (l/derived :builtin-templates st/state))

(mf/defc tutorial-project
  [{:keys [close-tutorial default-project-id] :as props}]
  (let [state     (mf/use-state {:status :waiting
                                 :file nil})

        templates (mf/deref builtin-templates)
        template  (d/seek #(= (:id %) "tutorial-for-beginners") templates)

        on-template-cloned-success
        (mf/use-fn
         (mf/deps default-project-id)
         (fn [response]
           (swap! state #(assoc % :status :success :file (:first response)))
           (st/emit! (dd/go-to-workspace {:id (first response) :project-id default-project-id :name "tutorial"})
                     (du/update-profile-props {:viewed-tutorial? true}))))

        on-template-cloned-error
        (mf/use-fn
         (fn []
           (swap! state #(assoc % :status :waiting))
           (st/emit!
            (msg/error (tr "dashboard.libraries-and-templates.import-error")))))

        download-tutorial
        (mf/use-fn
         (mf/deps template default-project-id)
         (fn []
           (let [mdata  {:on-success on-template-cloned-success :on-error on-template-cloned-error}
                 params {:project-id default-project-id :template-id (:id template)}]
             (swap! state #(assoc % :status :importing))
             (st/emit! (with-meta (dd/clone-template (with-meta params mdata))
                         {::ev/origin "get-started-hero-block"})))))]
    [:article.tutorial
     [:div.thumbnail]
     [:div.text
      [:h2.title (tr "dasboard.tutorial-hero.title")]
      [:p.info (tr "dasboard.tutorial-hero.info")]
      [:button.btn-primary.action {:on-click download-tutorial}
       (case (:status @state)
         :waiting (tr "dasboard.tutorial-hero.start")
         :importing [:span.loader i/loader-pencil]
         :success "")]]

     [:button.close
      {:on-click close-tutorial
       :aria-label (tr "labels.close")}
      [:span.icon i/close]]]))

(mf/defc interface-walkthrough
  {::mf/wrap [mf/memo]}
  [{:keys [close-walkthrough] :as props}]
  (let [handle-walkthrough-link
        (fn []
          (st/emit! (ptk/event ::ev/event {::ev/name "show-walkthrough"
                                           ::ev/origin "get-started-hero-block"
                                           :section "dashboard"})))]
    [:article.walkthrough
     [:div.thumbnail]
     [:div.text
      [:h2.title (tr "dasboard.walkthrough-hero.title")]
      [:p.info (tr "dasboard.walkthrough-hero.info")]
      [:a.btn-primary.action
       {:href " https://design.penpot.app/walkthrough"
        :target "_blank"
        :on-click handle-walkthrough-link}
       (tr "dasboard.walkthrough-hero.start")]]
     [:button.close
      {:on-click close-walkthrough
       :aria-label (tr "labels.close")}
      [:span.icon i/close]]]))

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

        width      (mf/use-state nil)
        rowref     (mf/use-ref)
        itemsize   (if (>= @width 1030)
                     280
                     230)

        ratio      (if (some? @width) (/ @width itemsize) 0)
        nitems     (mth/floor ratio)
        limit      (min 10 nitems)
        limit      (max 1 limit)

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
                     (dd/clear-selected-files))))]

    (mf/with-effect
      (let [node (mf/ref-val rowref)
            mnt? (volatile! true)
            sub  (->> (wapi/observe-resize node)
                      (rx/observe-on :af)
                      (rx/subs (fn [entries]
                                 (let [row (first entries)
                                       row-rect (.-contentRect ^js row)
                                       row-width (.-width ^js row-rect)]
                                   (when @mnt?
                                     (reset! width row-width))))))]
        (fn []
          (vreset! mnt? false)
          (rx/dispose! sub))))

    [:article.dashboard-project-row
     {:class (when first? "first")}
     [:header.project {:ref rowref}
      [:div.project-name-wrapper
       (if (:edition? @local)
         [:& inline-edition {:content (:name project)
                             :on-end on-edit}]
         [:h2 {:on-click on-nav
               :on-context-menu on-menu-click}
          (if (:is-default project)
            (tr "labels.drafts")
            (:name project))])

       [:& project-menu
        {:project project
         :show? (:menu-open @local)
         :left (+ 24 (:x (:menu-pos @local)))
         :top (:y (:menu-pos @local))
         :on-edit on-edit-open
         :on-menu-close on-menu-close
         :on-import on-import}]

       [:span.info (str (tr "labels.num-of-files" (i18n/c file-count)))]
       (let [time (-> (:modified-at project)
                      (dt/timeago {:locale locale}))]
         [:span.recent-files-row-title-info (str ", " time)])
       [:div.project-actions
        (when-not (:is-default project)
          [:button.pin-icon.tooltip.tooltip-bottom
           {:class (when (:is-pinned project) "active")
            :on-click toggle-pin
            :alt (tr "dashboard.pin-unpin")
            :aria-label (tr "dashboard.pin-unpin")
            :tab-index "0"}
           (if (:is-pinned project)
             i/pin-fill
             i/pin)])

        [:button.btn-secondary.btn-small.tooltip.tooltip-bottom
         {:on-click on-create-click
          :alt (tr "dashboard.new-file")
          :aria-label (tr "dashboard.new-file")
          :data-test "project-new-file"
          :tab-index "0"
          :on-key-down (fn [event]
                         (when (kbd/enter? event)
                           (on-create-click event)))}
         i/close]

        [:button.btn-secondary.btn-small.tooltip.tooltip-bottom
         {:on-click on-menu-click
          :alt (tr "dashboard.options")
          :aria-label  (tr "dashboard.options")
          :data-test "project-options"
          :tab-index "0"
          :on-key-down (fn [event]
                         (when (kbd/enter? event)
                           (dom/stop-propagation event)
                           (on-menu-click event)))}
         i/actions]]]]

     [:& line-grid
      {:project project
       :team team
       :files files
       :create-fn create-file
       :limit limit}]

     (when (and (> limit 0)
                (> file-count limit))
       [:button.show-more {:on-click on-nav
                           :tab-index "0"
                           :on-key-down (fn [event]
                                          (when (kbd/enter? event)
                                            (on-nav)))}
        [:div.placeholder-label
         (tr "dashboard.show-all-files")]
        [:div.placeholder-icon i/arrow-down]])]))


(def recent-files-ref
  (l/derived :dashboard-recent-files st/state))

(mf/defc projects-section
  [{:keys [team projects profile default-project-id] :as props}]
  (let [projects            (->> (vals projects)
                                 (sort-by :modified-at)
                                 (reverse))
        recent-map          (mf/deref recent-files-ref)
        props               (some-> profile (get :props {}))
        you-owner?          (get-in team [:permissions :is-owner])
        you-admin?          (get-in team [:permissions :is-admin])
        can-invite?         (or you-owner? you-admin?)
        team-hero?          (and can-invite?
                              (:team-hero? props true)
                              (not (:is-default team)))

        tutorial-viewed?    (:viewed-tutorial? props true)
        walkthrough-viewed? (:viewed-walkthrough? props true)

        team-id             (:id team)

        close-banner
        (mf/use-fn
         (fn []
           (st/emit! (du/update-profile-props {:team-hero? false})
                     (ptk/event ::ev/event {::ev/name "dont-show-team-up-hero"
                                            ::ev/origin "dashboard"}))))
        close-tutorial
        (mf/use-fn
         (fn []
           (st/emit! (du/update-profile-props {:viewed-tutorial? true})
                     (ptk/event ::ev/event {::ev/name "dont-show"
                                            ::ev/origin "get-started-hero-block"
                                            :type "tutorial"
                                            :section "dashboard"}))))
        close-walkthrough
        (mf/use-fn
         (fn []
           (st/emit! (du/update-profile-props {:viewed-walkthrough? true})
                     (ptk/event ::ev/event {::ev/name "dont-show"
                                            ::ev/origin "get-started-hero-block"
                                            :type "walkthrough"
                                            :section "dashboard"}))))]

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

       (when team-hero?
         [:& team-hero {:team team :close-fn close-banner}])

       (when (and (contains? cf/flags :dashboard-templates-section)
                  (or (not tutorial-viewed?)
                      (not walkthrough-viewed?)))
         [:div.hero-projects
          (when (and (not tutorial-viewed?) (:is-default team))
            [:& tutorial-project
             {:close-tutorial close-tutorial
              :default-project-id default-project-id}])

          (when (and (not walkthrough-viewed?) (:is-default team))
            [:& interface-walkthrough
             {:close-walkthrough close-walkthrough}])])

       [:div.dashboard-container.no-bg.dashboard-projects
        (for [{:keys [id] :as project} projects]
          (let [files (when recent-map
                        (->> (vals recent-map)
                             (filterv #(= id (:project-id %)))
                             (sort-by :modified-at #(compare %2 %1))))]
            [:& project-item {:project project
                              :team team
                              :files files
                              :first? (= project (first projects))
                              :key id}]))]])))

