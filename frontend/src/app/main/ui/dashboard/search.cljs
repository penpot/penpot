;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.search
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc search-page
  [{:keys [team search-term] :as props}]
  (let [result (mf/deref refs/dashboard-search-result)
        [rowref limit] (hooks/use-dynamic-grid-item-width)]

    (mf/use-effect
     (mf/deps team)
     (fn []
       (when team
         (let [tname (if (:is-default team)
                       (tr "dashboard.your-penpot")
                       (:name team))]
           (dom/set-html-title (tr "title.dashboard.search" tname))))))

    (mf/use-effect
     (mf/deps search-term)
     (fn []
       (st/emit! (dd/search {:search-term search-term})
                 (dd/clear-selected-files))))
    [:*
     [:header {:class (stl/css :dashboard-header)}
      [:div#dashboard-search-title {:class (stl/css :dashboard-title)}
       [:h1 (tr "dashboard.title-search")]]]

     [:section {:class (stl/css :dashboard-container :search :no-bg)
                :ref rowref}
      (cond
        (empty? search-term)
        [:div {:class (stl/css :grid-empty-placeholder :search)}
         [:div {:class (stl/css :icon)} i/search]
         [:div {:class (stl/css :text)} (tr "dashboard.type-something")]]

        (nil? result)
        [:div {:class (stl/css :grid-empty-placeholder :search)}
         [:div {:class (stl/css :icon)} i/search]
         [:div {:class (stl/css :text)} (tr "dashboard.searching-for" search-term)]]

        (empty? result)
        [:div {:class (stl/css :grid-empty-placeholder :search)}
         [:div {:class (stl/css :icon)} i/search]
         [:div {:class (stl/css :text)} (tr "dashboard.no-matches-for" search-term)]]

        :else
        [:& grid {:files result
                  :hide-new? true
                  :origin :search
                  :limit limit}])]]))
