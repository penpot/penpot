;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.files
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc header
  [{:keys [team project] :as props}]
  (let [local      (mf/use-state {:menu-open false
                                  :edition false})
        project-id (:id project)
        team-id    (:id team)

        on-menu-click
        (mf/use-callback
         (fn [event]
           (let [position (dom/get-client-position event)]
             (dom/prevent-default event)
             (swap! local assoc :menu-open true :menu-pos position))))

        on-menu-close
        (mf/use-callback #(swap! local assoc :menu-open false))

        on-edit
        (mf/use-callback #(swap! local assoc :edition true :menu-open false))

        toggle-pin
        (mf/use-callback
         (mf/deps project)
         (st/emitf (dd/toggle-project-pin project)))

        on-create-clicked
        (mf/use-callback
         (mf/deps project)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (dd/create-file {:project-id (:id project)}))))]


    [:header.dashboard-header
     (if (:is-default project)
       [:div.dashboard-title
        [:h1 (tr "labels.drafts")]]

       (if (:edition @local)
         [:& inline-edition {:content (:name project)
                             :on-end (fn [name]
                                       (st/emit! (dd/rename-project (assoc project :name name)))
                                       (swap! local assoc :edition false))}]
         [:div.dashboard-title
          [:h1 {:on-double-click on-edit}
           (:name project)]
          [:& project-menu {:project project
                            :show? (:menu-open @local)
                            :left (- (:x (:menu-pos @local)) 180)
                            :top (:y (:menu-pos @local))
                            :on-edit on-edit
                            :on-menu-close on-menu-close}]]))
     [:div.dashboard-header-actions
      [:a.btn-secondary.btn-small {:on-click on-create-clicked}
       (tr "dashboard.new-file")]

      [:div.icon.pin-icon.tooltip.tooltip-bottom
       {:class (when (:is-pinned project) "active")
       :on-click toggle-pin :alt (tr "dashboard.pin-unpin")}
       (if (:is-pinned project)
         i/pin-fill
         i/pin)]
      
      [:div.icon.tooltip.tooltip-bottom
       {:on-click on-menu-click :alt (tr "dashboard.options")}
       i/actions]]]))

(mf/defc files-section
  [{:keys [project team] :as props}]
  (let [files-map (mf/deref refs/dashboard-files)
        files     (->> (vals files-map)
                       (filter #(= (:id project) (:project-id %)))
                       (sort-by :modified-at)
                       (reverse))]

    (mf/use-effect
     (mf/deps (:id project))
     (fn []
       (dom/set-html-title (tr "title.dashboard.files"
                              (if (:is-default project)
                                (tr "labels.drafts")
                                (:name project))))
       (st/emit! (dd/fetch-files {:project-id (:id project)})
                 (dd/clear-selected-files))))

    [:*
     [:& header {:team team :project project}]
     [:section.dashboard-container
      [:& grid {:id (:id project)
                :files files}]]]))

