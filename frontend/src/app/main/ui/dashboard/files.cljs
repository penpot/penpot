;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.files
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.router :as rt]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc header
  [{:keys [team project] :as props}]
  (let [local  (mf/use-state {:menu-open false
                             :edition false})
        locale (mf/deref i18n/locale)
        project-id (:id project)
        team-id    (:id team)

        on-menu-click
        (mf/use-callback #(swap! local assoc :menu-open true))

        on-menu-close
        (mf/use-callback #(swap! local assoc :menu-open false))

        on-edit
        (mf/use-callback #(swap! local assoc :edition true :menu-open false))

        on-blur
        (mf/use-callback
         (mf/deps project)
         (fn [event]
           (let [name (-> event dom/get-target dom/get-value)]
             #_(st/emit! (dd/rename-project (:id project) name))
             (swap! local assoc :edition false))))

        on-key-down
        (mf/use-callback
         (mf/deps project)
         (fn [event]
           (cond
             (kbd/enter? event) (on-blur event)
             (kbd/esc? event) (swap! local assoc :edition false))))

        delete-fn
        (mf/use-callback
         (mf/deps project)
         (fn [event]
           (st/emit! (dd/delete-project project)
                     (rt/nav :dashboard-projects {:team-id (:id team)}))))

        on-delete
        (mf/use-callback
         (mf/deps project)
         (fn [] (modal/show! :confirm-dialog {:on-accept delete-fn})))

        on-create-clicked
        (mf/use-callback
         (mf/deps project)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (dd/create-file (:id project)))))]


    [:header.dashboard-header
     (if (:is-default project)
       [:h1.dashboard-title (t locale "dashboard.header.draft")]
       [:*
        [:h1.dashboard-title (t locale "dashboard.header.project" (:name project))]
        [:div.icon {:on-click on-menu-click} i/actions]
        [:& context-menu {:on-close on-menu-close
                          :show (:menu-open @local)
                          :options [[(t locale "dashboard.grid.rename") on-edit]
                                    [(t locale "dashboard.grid.delete") on-delete]]}]
        (if (:edition @local)
          [:input.element-name {:type "text"
                                :auto-focus true
                                :on-key-down on-key-down
                                :on-blur on-blur
                                :default-value (:name project)}])])
     #_[:ul.main-nav
      [:li.current
       [:a "PROJECTS"]]
      [:li
       [:a "MEMBERS"]]]

     [:a.btn-secondary.btn-small {:on-click on-create-clicked}
      (t locale "dashboard.new-file")]]))

(defn files-ref
  [project-id]
  (l/derived (l/in [:files project-id]) st/state))

(mf/defc files-section
  [{:keys [project team] :as props}]
  (let [files-ref (mf/use-memo (mf/deps (:id project)) #(files-ref (:id project)))
        files-map (mf/deref files-ref)
        files     (->> (vals files-map)
                       (sort-by :modified-at)
                       (reverse))]

    (mf/use-effect
     (mf/deps (:id project))
     (fn []
       (st/emit! (dd/fetch-files {:project-id (:id project)}))))

    [:*
     [:& header {:team team :project project}]
     [:section.dashboard-grid-container
      [:& grid {:id (:id project)
                :files files}]]]))

