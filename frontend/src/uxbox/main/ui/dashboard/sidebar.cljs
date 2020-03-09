;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.sidebar
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.projects :as udp]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.exports :as exports]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.dashboard.common :as common]
   [uxbox.main.ui.dashboard.header :refer [header]]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.util.data :refer [read-string parse-int uuid-str?]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Component: Sidebar

(mf/defc sidebar-project
  [{:keys [id name selected? team-id] :as props}]
  (let [local (mf/use-state {:name name})
        editable? (not (nil? id))
        on-click #(st/emit! (rt/nav :dashboard-project {:team-id team-id :project-id id}))
        on-dbl-click #(when editable? (swap! local assoc :edit true))
        on-input #(as-> % $
                    (dom/get-target $)
                    (dom/get-value $)
                    (swap! local assoc :name $))
        on-cancel #(swap! local assoc :edit false :name name)
        on-keyup #(cond
                    (kbd/esc? %)
                    (on-cancel)

                    (kbd/enter? %)
                    (let [name (-> % dom/get-target dom/get-value)]
                      (st/emit! (udp/rename-project id name))
                      (swap! local assoc :edit false)))]

    [:li {:on-click on-click
          :on-double-click on-dbl-click
          :class-name (when selected? "current")}
     (if (:edit @local)
       [:div
        [:input.element-title {:value (:name @local)
                               :on-change on-input
                               :on-key-down on-keyup}]
        [:span.close {:on-click on-cancel} i/close]]
       [:span.element-title name])]))

(def projects-iref
  (-> (l/key :projects)
      (l/derive st/state)))

(mf/defc sidebar-projects
  [{:keys [team-id selected-project-id] :as props}]
  (let [projects (->> (mf/deref projects-iref)
                      (vals)
                      (remove #(:is-default %))
                      (sort-by :created-at))]
    (for [item projects]
      [:& sidebar-project
       {:id (:id item)
        :key (:id item)
        :name (:name item)
        :selected? (= (:id item) selected-project-id)
        :team-id team-id
        }])))

(mf/defc sidear-team
  [{:keys [profile
           team-id
           selected-section
           selected-project-id
           selected-team-id] :as props}]
  (let [home?   (and (= selected-section :dashboard-team)
                     (= selected-team-id (:default-team-id profile)))
        drafts? (and (= selected-section :dashboard-project)
                     (= selected-team-id (:default-team-id profile))
                     (= selected-project-id (:default-project-id profile)))]
    [:ul.library-elements
     [:li.recent-projects
      {:on-click #(st/emit! (rt/nav :dashboard-team {:team-id team-id}))
       :class-name (when home? "current")}
      i/user
      [:span.element-title "Personal"]]

     [:li
      {:on-click #(st/emit! (rt/nav :dashboard-project {:team-id team-id
                                                        :project-id "drafts"}))
       :class-name (when drafts? "current")}
      i/file-html
      [:span.element-title "Drafts"]]


     [:li
      i/icon-set
      [:span.element-title "Libraries"]]

     [:div.projects-row
      [:span "PROJECTS"]
      [:a.add-project {:on-click #(st/emit! dsh/create-project)}
       i/close]]

     [:& sidebar-projects
      {:selected-team-id selected-team-id
       :selected-project-id selected-project-id
       :team-id team-id}]]))

(mf/defc sidebar
  [{:keys [section team-id project-id] :as props}]
  (let [locale (i18n/use-locale)
        profile (mf/deref refs/profile)]
    [:div.library-bar
     [:div.library-bar-inside
      [:form.dashboard-search
       [:input.input-text
        {:key :images-search-box
         :type "text"
         :auto-focus true
         :placeholder (t locale "ds.search.placeholder")}]
       [:div.clear-search i/close]]
      [:& sidear-team {:selected-team-id team-id
                       :selected-project-id project-id
                       :selected-section section
                       :profile profile
                       :team-id "self"}]]]))
