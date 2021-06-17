;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.search
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc search-page
  [{:keys [team search-term] :as props}]
  (let [result (mf/deref refs/dashboard-search-result)]
    (mf/use-effect
     (mf/deps team)
     (fn []
       (dom/set-html-title (tr "title.dashboard.search"
                              (if (:is-default team)
                                (tr "dashboard.your-penpot")
                                (:name team))))))
    (mf/use-effect
     (mf/deps search-term)
     (fn []
       (st/emit! (dd/search {:search-term search-term})
                 (dd/clear-selected-files))))

    [:*
     [:header.dashboard-header
      [:div.dashboard-title
       [:h1 (tr "dashboard.title-search")]]]

     [:section.dashboard-container.search
      (cond
        (empty? search-term)
        [:div.grid-empty-placeholder
         [:div.icon i/search]
         [:div.text (tr "dashboard.type-something")]]

        (nil? result)
        [:div.grid-empty-placeholder
         [:div.icon i/search]
         [:div.text (tr "dashboard.searching-for" search-term)]]

        (empty? result)
        [:div.grid-empty-placeholder
         [:div.icon i/search]
         [:div.text (tr "dashboard.no-matches-for" search-term)]]

        :else
        [:& grid {:files result
                  :hide-new? true}])]]))
