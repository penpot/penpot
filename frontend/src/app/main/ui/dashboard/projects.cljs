;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.projects
  (:require
   [app.common.math :as mth]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
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
   [rumext.alpha :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  []
  (let [create #(st/emit! (dd/create-project))]
    [:header.dashboard-header
     [:div.dashboard-title
      [:h1 (tr "dashboard.projects-title")]]

     [:a.btn-secondary.btn-small {:on-click create :data-test "new-project-button"}
      (tr "dashboard.new-project")]]))

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

       [:span.info (str file-count " files")]
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
  [{:keys [team projects] :as props}]
  (let [projects   (->> (vals projects)
                        (sort-by :modified-at)
                        (reverse))
        recent-map (mf/deref recent-files-ref)]

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

