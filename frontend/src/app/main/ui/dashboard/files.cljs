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
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.router :as rt]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc header
  [{:keys [team project] :as props}]
  (let [local      (mf/use-state {:menu-open false
                                  :edition false})
        locale     (mf/deref i18n/locale)
        project-id (:id project)
        team-id    (:id team)

        on-menu-click
        (mf/use-callback #(swap! local assoc :menu-open true))

        on-menu-close
        (mf/use-callback #(swap! local assoc :menu-open false))

        on-edit
        (mf/use-callback #(swap! local assoc :edition true :menu-open false))


        delete-fn
        (mf/use-callback
         (mf/deps project)
         (fn [event]
           (st/emit! (dd/delete-project project)
                     (rt/nav :dashboard-projects {:team-id (:id team)}))))

        on-delete
        (mf/use-callback
         (mf/deps project)
         (st/emitf (modal/show
                    {:type :confirm
                     :title (t locale "modals.delete-project-confirm.title")
                     :message (t locale "modals.delete-project-confirm.message")
                     :accept-label (t locale "modals.delete-project-confirm.accept")
                     :on-accept delete-fn})))

        on-create-clicked
        (mf/use-callback
         (mf/deps project)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (dd/create-file {:project-id (:id project)}))))]


    [:header.dashboard-header
     (if (:is-default project)
       [:div.dashboard-title
        [:h1 (t locale "dashboard.draft-title")]]

       (if (:edition @local)
         [:& inline-edition {:content (:name project)
                             :on-end (fn [name]
                                       (st/emit! (dd/rename-project (assoc project :name name)))
                                       (swap! local assoc :edition false))}]
         [:div.dashboard-title
          [:h1 (:name project)]
          [:div.icon {:on-click on-menu-click} i/actions]
          [:& context-menu {:on-close on-menu-close
                            :show (:menu-open @local)
                            :options [[(t locale "labels.rename") on-edit]
                                      [(t locale "labels.delete") on-delete]]}]]))
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
     [:section.dashboard-container
      [:& grid {:id (:id project)
                :files files}]]]))

