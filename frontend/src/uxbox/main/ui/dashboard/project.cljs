;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.dashboard.project
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.dom :as dom]
   [uxbox.util.router :as rt]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]
   [uxbox.main.ui.dashboard.grid :refer [grid]]))

(def projects-ref
  (-> (l/key :projects)
      (l/derive st/state)))

(def files-ref
  (-> (comp (l/key :files)
            (l/lens vals))
      (l/derive st/state)))

(mf/defc project-header
  [{:keys [team-id project-id] :as props}]
  (let [local (mf/use-state {:menu-open false
                             :edition false})
        projects (mf/deref projects-ref)
        project (get projects project-id)
        locale (i18n/use-locale)
        on-menu-click #(swap! local assoc :menu-open true)
        on-menu-close #(swap! local assoc :menu-open false)
        on-edit #(swap! local assoc :edition true :menu-open false)
        on-blur #(let [name (-> % dom/get-target dom/get-value)]
                   (st/emit! (dsh/rename-project project-id name))
                   (swap! local assoc :edition false))
        on-key-down #(cond
                       (kbd/enter? %) (on-blur %)
                       (kbd/esc? %) (swap! local assoc :edition false))
        delete-fn #(do
                     (st/emit! (dsh/delete-project project-id))
                     (st/emit! (rt/nav :dashboard-team {:team-id team-id})))
        on-delete #(modal/show! confirm-dialog {:on-accept delete-fn})]
    [:header.main-bar
     (if (:is-default project)
       [:h1.dashboard-title (t locale "dashboard.header.draft")]
       [:*
        [:div.main-bar-icon {:on-click on-menu-click} i/actions]
        [:& context-menu {:on-close on-menu-close
                          :show (:menu-open @local)
                          :options [[(t locale "dashboard.grid.edit") on-edit]
                                    [(t locale "dashboard.grid.delete") on-delete]]}]
        (if (:edition @local)
          [:input.element-name {:type "text"
                                :auto-focus true
                                :on-key-down on-key-down
                                :on-blur on-blur
                                :default-value (:name project)}]
          [:h1.dashboard-title (t locale "dashboard.header.project" (:name project))])])
     [:a.btn-dashboard {:on-click #(do
                                     (dom/prevent-default %)
                                     (st/emit! (dsh/create-file project-id)))}
      (t locale "dashboard.header.new-file")]]))

(mf/defc project-page
  [{:keys [section team-id project-id] :as props}]
  (let [files (->> (mf/deref files-ref)
                   (sort-by :modified-at)
                   (reverse))]
    (mf/use-effect
     (mf/deps section team-id project-id)
     #(st/emit! (dsh/initialize-project team-id project-id)))

    [:*
      [:& project-header {:team-id team-id :project-id project-id}]
      [:section.projects-page
       [:& grid { :id project-id :files files :hide-new? true}]]]))

