;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.projects
  (:require
   [app.common.exceptions :as ex]
   [app.main.constants :as c]
   [app.main.data.dashboard :as dd]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [line-grid]]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
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
      [:h1 "Projects"]]
     [:a.btn-secondary.btn-small {:on-click create}
      (t locale "dashboard.header.new-project")]]))

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

        files      (->> recent-ids
                        (map #(get files-map %))
                        (sort-by :modified-at)
                        (reverse))

        project-id (:id project)
        team-id    (:team-id project)
        file-count (or (:count project) 0)

        on-nav
        (mf/use-callback
         (mf/deps project)
         (st/emitf (rt/nav :dashboard-files {:team-id (:team-id project)
                                             :project-id (:id project)})))
        toggle-pin
        (mf/use-callback
         (mf/deps project)
         (st/emitf (dd/toggle-project-pin project)))

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
         i/pin])
      [:h2 {:on-click on-nav} (:name project)]
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
       (st/emit! (dd/fetch-recent-files {:team-id (:id team)}))))

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

