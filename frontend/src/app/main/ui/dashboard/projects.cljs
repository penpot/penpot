;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.projects
  (:require
   [app.common.math :as mth]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
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
  (let [create #(st/emit! (dd/create-project))]
    [:header.dashboard-header
     [:div.dashboard-title
      [:h1 (tr "dashboard.projects-title")]]

     [:a.btn-secondary.btn-small {:on-click create :data-test "new-project-button"}
      (tr "dashboard.new-project")]]))

(mf/defc team-hero
  {::mf/wrap [mf/memo]}
  [{:keys [team close-banner] :as props}]
  (let [go-members    #(st/emit! (dd/go-to-team-members))
        invite-member #(st/emit! (modal/show {:type :invite-members :team team :origin :hero}))]
    [:div.team-hero
     [:img {:src "images/deco-team-banner.png" :border "0"}]
     [:div.text
      [:div.title (tr "dasboard.team-hero.title")]
      [:div.info
       [:span (tr "dasboard.team-hero.text")]
       [:a {:on-click  go-members} (tr "dasboard.team-hero.management")]]]
     [:button.btn-primary.invite {:on-click invite-member} (tr "onboarding.choice.team-up.invite-members")]
     [:button.close {:on-click close-banner}
      [:span i/close]]]))

(def builtin-templates
  (l/derived :builtin-templates st/state))

(mf/defc tutorial-project
  [{:keys [close-tutorial default-project-id] :as props}]
  (let [state (mf/use-state
               {:status :waiting
                :file nil})

        template (->>  (mf/deref builtin-templates)
                       (filter #(= (:id %) "tutorial-for-beginners"))
                       first)

        on-template-cloned-success
        (mf/use-callback
         (fn [response]
           (swap! state #(assoc % :status :success :file (:first response)))
           (st/emit! (dd/go-to-workspace {:id (first response) :project-id default-project-id :name "tutorial"})
                     (du/update-profile-props {:viewed-tutorial? true}))))

        on-template-cloned-error
        (fn []
          (swap! state #(assoc % :status :waiting))
          (st/emit!
           (dm/error (tr "dashboard.libraries-and-templates.import-error"))))

        download-tutorial
        (fn []
          (let [mdata  {:on-success on-template-cloned-success :on-error on-template-cloned-error}
                params {:project-id default-project-id :template-id (:id template)}]
            (swap! state #(assoc % :status :importing))
            (st/emit! (with-meta (dd/clone-template (with-meta params mdata))
                        {::ev/origin "get-started-hero-block"}))))]
    [:div.tutorial
     [:div.img]
     [:div.text
      [:div.title (tr "dasboard.tutorial-hero.title")]
      [:div.info (tr "dasboard.tutorial-hero.info")]
      [:button.btn-primary.action {:on-click download-tutorial} 
       (case (:status @state)
         :waiting (tr "dasboard.tutorial-hero.start")
         :importing [:span.loader i/loader-pencil]
         :success ""
         )
       ]]
     [:button.close
      {:on-click close-tutorial}
      [:span.icon i/close]]]))

(mf/defc interface-walkthrough
  {::mf/wrap [mf/memo]}
  [{:keys [close-walkthrough] :as props}]
  (let [handle-walkthrough-link
        (fn []
          (st/emit! (ptk/event ::ev/event {::ev/name "show-walkthrough"
                                           ::ev/origin "get-started-hero-block"
                                           :section "dashboard"})))]
    [:div.walkthrough
     [:div.img]
     [:div.text
      [:div.title (tr "dasboard.walkthrough-hero.title")]
      [:div.info (tr "dasboard.walkthrough-hero.info")]
      [:a.btn-primary.action {:href " https://design.penpot.app/walkthrough" :target "_blank" :on-click handle-walkthrough-link}
       (tr "dasboard.walkthrough-hero.start")]]
     [:button.close
      {:on-click close-walkthrough}
      [:span.icon i/close]]]))

(mf/defc project-item
  [{:keys [project first? team files] :as props}]
  (let [locale     (mf/deref i18n/locale)
        file-count (or (:count project) 0)

        dstate     (mf/deref refs/dashboard-local)
        edit-id    (:project-for-edit dstate)

        local
        (mf/use-state {:menu-open false
                       :menu-pos nil
                       :edition? (= (:id project) edit-id)})

        on-nav
        (mf/use-callback
         (mf/deps project)
         #(st/emit! (rt/nav :dashboard-files {:team-id (:team-id project)
                                              :project-id (:id project)})))

        width            (mf/use-state nil)
        rowref           (mf/use-ref)
        itemsize       (if (>= @width 1030)
                         280
                         230)

        ratio          (if (some? @width) (/ @width itemsize) 0)
        nitems         (mth/floor ratio)
        limit          (min 10 nitems)
        limit          (max 1 limit)

        toggle-pin
        (mf/use-callback
         (mf/deps project)
         #(st/emit! (dd/toggle-project-pin project)))

        on-menu-click
        (mf/use-callback (fn [event]
                           (let [position (dom/get-client-position event)]
                             (dom/prevent-default event)
                             (swap! local assoc :menu-open true
                                                :menu-pos position))))

        on-menu-close
        (mf/use-callback #(swap! local assoc :menu-open false))

        on-edit-open
        (mf/use-callback #(swap! local assoc :edition? true))

        on-edit
        (mf/use-callback
         (mf/deps project)
         (fn [name]
           (let [name (str/trim name)]
             (when-not (str/empty? name)
               (st/emit! (-> (dd/rename-project (assoc project :name name))
                             (with-meta {::ev/origin "dashboard"}))))
             (swap! local assoc :edition? false))))

        on-file-created
        (mf/use-callback
         (mf/deps project)
         (fn [data]
           (let [pparams {:project-id (:project-id data)
                          :file-id (:id data)}
                 qparams {:page-id (get-in data [:data :pages 0])}]
             (st/emit! (rt/nav :workspace pparams qparams)))))


        create-file
        (mf/use-callback
         (mf/deps project)
         (fn [origin]
           (let [mdata  {:on-success on-file-created}
                 params {:project-id (:id project)}]
             (st/emit! (with-meta (dd/create-file (with-meta params mdata))
                         {::ev/origin origin})))))

        on-import
        (mf/use-callback
         (mf/deps (:id project) (:id team))
         (fn []
           (st/emit! (dd/fetch-files {:project-id (:id project)})
                     (dd/fetch-recent-files (:id team))
                     (dd/clear-selected-files))))]

    (mf/use-effect
     (fn []
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
           (rx/dispose! sub)))))
    [:div.dashboard-project-row {:class (when first? "first")}
     [:div.project {:ref rowref}
      [:div.project-name-wrapper
       (if (:edition? @local)
         [:& inline-edition {:content (:name project)
                             :on-end on-edit}]
         [:h2 {:on-click on-nav
               :on-context-menu on-menu-click}
          (if (:is-default project)
            (tr "labels.drafts")
            (:name project))])

       [:& project-menu {:project project
                         :show? (:menu-open @local)
                         :left (:x (:menu-pos @local))
                         :top (:y (:menu-pos @local))
                         :on-edit on-edit-open
                         :on-menu-close on-menu-close
                         :on-import on-import}]

       [:span.info (str (tr "labels.num-of-files" (i18n/c file-count)))]
       (when (> file-count 0)
         (let [time (-> (:modified-at project)
                        (dt/timeago {:locale locale}))]
           [:span.recent-files-row-title-info (str ", " time)]))
       [:div.project-actions
        (when-not (:is-default project)
          [:span.pin-icon.tooltip.tooltip-bottom
           {:class (when (:is-pinned project) "active")
            :on-click toggle-pin :alt (tr "dashboard.pin-unpin")}
           (if (:is-pinned project)
             i/pin-fill
             i/pin)])

        [:a.btn-secondary.btn-small.tooltip.tooltip-bottom
         {:on-click create-file :alt (tr "dashboard.new-file") :data-test "project-new-file"}
         i/close]

        [:a.btn-secondary.btn-small.tooltip.tooltip-bottom
         {:on-click on-menu-click :alt (tr "dashboard.options") :data-test "project-options"}
         i/actions]]]
      (when (and (> limit 0)
                 (> file-count limit))
        [:div.show-more {:on-click on-nav}
         [:div.placeholder-label
          (tr "dashboard.show-all-files")]
         [:div.placeholder-icon i/arrow-down]])]

     [:& line-grid
      {:project project
       :team team
       :files files
       :on-create-clicked (partial create-file "dashboard:empty-folder-placeholder")
       :limit limit}]]))


(def recent-files-ref
  (l/derived :dashboard-recent-files st/state))

(mf/defc projects-section
  [{:keys [team projects profile default-project-id] :as props}]
  (let [projects            (->> (vals projects)
                                 (sort-by :modified-at)
                                 (reverse))
        recent-map          (mf/deref recent-files-ref)
        props               (some-> profile (get :props {}))
        team-hero?          (:team-hero? props true)
        tutorial-viewed?    (:viewed-tutorial? props true)
        walkthrough-viewed? (:viewed-walkthrough? props true)

        close-banner        (fn []
                              (st/emit!
                               (du/update-profile-props {:team-hero? false})
                               (ptk/event ::ev/event {::ev/name "dont-show-team-up-hero"
                                                      ::ev/origin "dashboard"})))

        close-tutorial      (fn []
                              (st/emit!
                               (du/update-profile-props {:viewed-tutorial? true})
                               (ptk/event ::ev/event {::ev/name "dont-show"
                                                      ::ev/origin "get-started-hero-block"
                                                      :type "tutorial"
                                                      :section "dashboard"})))

        close-walkthrough   (fn []
                              (st/emit!
                               (du/update-profile-props {:viewed-walkthrough? true})
                               (ptk/event ::ev/event {::ev/name "dont-show"
                                                      ::ev/origin "get-started-hero-block"
                                                      :type "walkthrough"
                                                      :section "dashboard"})))]

    (mf/use-effect
     (mf/deps team)
     (fn []
       (let [tname (if (:is-default team)
                     (tr "dashboard.your-penpot")
                     (:name team))]
         (dom/set-html-title (tr "title.dashboard.projects" tname)))))

    (mf/use-effect
     (mf/deps (:id team))
     (fn []
       (st/emit! (dd/fetch-recent-files (:id team))
                 (dd/clear-selected-files))))

    (when (seq projects)
      [:*
       [:& header]
       (when (and team-hero? (not (:is-default team)))
         [:& team-hero
          {:team team
           :close-banner close-banner}])
       (when (or (not tutorial-viewed?) (not walkthrough-viewed?))
         [:div.hero-projects
          (when (and (not tutorial-viewed?) (:is-default team))
            [:& tutorial-project
             {:close-tutorial close-tutorial
              :default-project-id default-project-id}])

          (when (and (not walkthrough-viewed?) (:is-default team))
            [:& interface-walkthrough
             {:close-walkthrough close-walkthrough}])])

       [:section.dashboard-container.no-bg
        (for [{:keys [id] :as project} projects]
          (let [files (when recent-map
                        (->> (vals recent-map)
                             (filterv #(= id (:project-id %)))
                             (sort-by :modified-at #(compare %2 %1))))]
            [:& project-item {:project project
                              :team team
                              :files files
                              :first? (= project (first projects))
                              :key (:id project)}]))]])))

