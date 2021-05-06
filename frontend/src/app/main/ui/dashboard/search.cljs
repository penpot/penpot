;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.search
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]
   [okulary.core :as l]
   [rumext.alpha :as mf]))


(def result-ref
  (l/derived (l/in [:dashboard-local :search-result]) st/state))

(mf/defc search-page
  [{:keys [team search-term] :as props}]
  (let [result (mf/deref result-ref)
        locale (mf/deref i18n/locale)]

    (mf/use-effect
     (mf/deps team search-term)
     (fn []
       (dom/set-html-title (t locale "title.dashboard.search"
                              (if (:is-default team)
                                (t locale "dashboard.your-penpot")
                                (:name team))))
       (when search-term
         (st/emit! (dd/search-files {:team-id (:id team)
                                     :search-term search-term})
                   (dd/clear-selected-files)))))

    [:*
     [:header.dashboard-header
      [:div.dashboard-title
       [:h1 (t locale "dashboard.title-search")]]]

     [:section.dashboard-container.search
      (cond
        (empty? search-term)
        [:div.grid-empty-placeholder
         [:div.icon i/search]
         [:div.text (t locale "dashboard.type-something")]]

        (nil? result)
        [:div.grid-empty-placeholder
         [:div.icon i/search]
         [:div.text  (t locale "dashboard.searching-for" search-term)]]

        (empty? result)
        [:div.grid-empty-placeholder
         [:div.icon i/search]
         [:div.text  (t locale "dashboard.no-matches-for" search-term)]]

        :else
        [:& grid {:files result
                  :hide-new? true}])]]))
