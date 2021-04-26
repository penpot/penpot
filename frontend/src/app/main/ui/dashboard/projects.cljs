;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.projects
  (:require
   [app.common.exceptions :as ex]
   [app.main.constants :as c]
   [app.main.data.dashboard :as dd]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [line-grid]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  [{:keys [locale team] :as props}]
  (let [create #(st/emit! (dd/create-project {:team-id (:id team)}))]
    [:header.dashboard-header
     [:div.dashboard-title
      [:h1 (t locale "dashboard.projects-title")]]
     [:a.btn-secondary.btn-small {:on-click create}
      (t locale "dashboard.new-project")]]))

(defn files-ref
  [project-id]
  (l/derived (l/in [:files project-id]) st/state))

(defn recent-ref
  [project-id]
  (l/derived (l/in [:recent-files project-id]) st/state))

(mf/defc project-item
  [{:keys [project first? locale] :as props}]
  (let [files-ref  (mf/use-memo (mf/deps project) #(files-ref (:id project)))
        recent-ref (mf/use-memo (mf/deps project) #(recent-ref (:id project)))

        files-map  (mf/deref files-ref)
        recent-ids (mf/deref recent-ref)

        files      (some->> recent-ids
                            (map #(get files-map %))
                            (sort-by :modified-at)
                            (filter some?)
                            (reverse))

        project-id (:id project)
        team-id    (:team-id project)
        file-count (or (:count project) 0)

        dstate  (mf/deref refs/dashboard-local)
        edit-id (:project-for-edit dstate)
        local (mf/use-state {:menu-open false
                             :menu-pos nil
                             :edition? (= (:id project) edit-id)})

        on-nav
        (mf/use-callback
         (mf/deps project)
         (st/emitf (rt/nav :dashboard-files {:team-id (:team-id project)
                                             :project-id (:id project)})))

        toggle-pin
        (mf/use-callback
         (mf/deps project)
         (st/emitf (dd/toggle-project-pin project)))

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
           (st/emit! (dd/rename-project (assoc project :name name)))
           (swap! local assoc :edition? false)))

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
         (fn []
           (let [mdata  {:on-success on-file-created}
                 params {:project-id (:id project)}]
             (st/emit! (dd/create-file (with-meta params mdata))))))]

    [:div.dashboard-project-row {:class (when first? "first")}
     [:div.project
      (when-not (:is-default project)
        [:span.pin-icon
         {:class (when (:is-pinned project) "active")
          :on-click toggle-pin}
         (if (:is-pinned project)
           i/pin-fill
           i/pin)])
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
                        :on-menu-close on-menu-close}]
      [:span.info (str file-count " files")]
      (when (> file-count 0)
        (let [time (-> (:modified-at project)
                       (dt/timeago {:locale locale}))]
          [:span.recent-files-row-title-info (str ", " time)]))

      [:a.btn-secondary.btn-small
       {:on-click create-file}
       (t locale "dashboard.new-file")]]

     [:& line-grid
      {:project-id (:id project)
       :team-id team-id
       :on-load-more on-nav
       :files files}]]))

(mf/defc projects-section
  [{:keys [team projects] :as props}]
  (let [projects (->> (vals projects)
                      (sort-by :modified-at)
                      (reverse))
        locale   (mf/deref i18n/locale)]

    (mf/use-effect
     (mf/deps team)
     (fn []
       (dom/set-html-title (tr "title.dashboard.projects"
                              (if (:is-default team)
                                (tr "dashboard.your-penpot")
                                (:name team))))
       (st/emit! (dd/fetch-recent-files {:team-id (:id team)})
                 (dd/clear-selected-files))))

    (when (seq projects)
      [:*
       [:& header {:locale locale
                   :team team}]
       [:section.dashboard-container
        (for [project projects]
          [:& project-item {:project project
                            :locale locale
                            :first? (= project (first projects))
                            :key (:id project)}])]])))

