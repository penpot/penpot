;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.deleted
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.common :as dcm]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [line-grid]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.product.empty-placeholder :refer [empty-placeholder*]]
   [app.main.ui.hooks :as hooks]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc header*
  {::mf/props :obj
   ::mf/private true}
  []
  [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
   [:div#dashboard-deleted-title {:class (stl/css :dashboard-title)}
    [:h1 (tr "dashboard.projects-title")]]])

(mf/defc deleted-project-item*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [project team files]}]
  (let [file-count (or (:count project) 0)
        empty?     (= 0 file-count)
        [rowref limit]
        (hooks/use-dynamic-grid-item-width)]
    ;; TODO: get real deleted items
    [:article {:class (stl/css-case :dashboard-project-row true)}
     [:header {:class (stl/css :project)}
      [:div {:class (stl/css :project-name-wrapper)}
       [:h2  {:class (stl/css :project-name)
              :title (:name project)}
        (:name project)]]]

     [:div {:class (stl/css :grid-container) :ref rowref}
      (if ^boolean empty?
        [:> empty-placeholder* {:title (tr "dashboard.empty-placeholder-files-title")
                                :class (stl/css :placeholder-placement)
                                :type 1
                                :subtitle (tr "dashboard.empty-placeholder-files-subtitle")}]

        [:& line-grid
         {:project project
          :team team
          :files files
          :can-edit false
          :limit limit}])]]))

(def ^:private ref:recent-files
  (l/derived :recent-files st/state))

(mf/defc deleted-section*
  {::mf/props :obj}
  [{:keys [team projects profile]}]
  (let [projects
        (mf/with-memo [projects]
          (->> projects
               (sort-by :modified-at)
               (reverse)))
        team-id             (get team :id)
        recent-map          (mf/deref ref:recent-files)
        deletion-days 30 ;; Get this from current subscription

        on-clear
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (println recent-map)

           (println "Clear all deleted projects")))

        on-restore-all
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (println "Restore all deleted projects")))

        on-recent-click
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit! (dcm/go-to-dashboard-recent :team-id team-id))))]
    [:*
     [:> header* {:team team}]
     [:section {:class (stl/css :dashboard-container :no-bg)}
      [:*
       [:div {:class (stl/css :no-bg)}

        [:div {:class (stl/css :nav-options)}
         [:> button* {:variant "ghost"
                      :type "button"
                      :on-click on-recent-click}
          (tr "dashboard.labels.recent")]
         [:div {:class (stl/css :selected)}
          (tr "dashboard.labels.deleted")]]

        [:div {:class (stl/css :deleted-content)}
         [:div {:class (stl/css :deleted-info)}
          [:div
           (tr "dashboard.deleted.info-text")
           [:span {:class (stl/css :info-text-highlight)}
            (tr "dashboard.deleted.info-days" deletion-days)]
           (tr "dashboard.deleted.info-text2")]
          [:div
           (tr "dashboard.deleted.restore-text")]]
         [:div {:class (stl/css :deleted-options)}
          [:> button* {:variant "ghost"
                       :type "button"
                       :on-click on-restore-all}
           (tr "dashboard.labels.restore-all")]
          [:> button* {:variant "destructive"
                       :type "button"
                       :icon "delete"
                       :on-click on-clear}
           (tr "dashboard.labels.clear")]]]]

       (for [{:keys [id] :as project} projects]
         (let [files (when recent-map
                       (->> (vals recent-map)
                            (filterv #(= id (:project-id %)))
                            (sort-by :modified-at #(compare %2 %1))))]
           [:> deleted-project-item* {:project project
                                      :team team
                                      :files files
                                      :key id}]))]]]))
