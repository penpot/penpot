;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.search
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t]]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Component: Search

(def result-ref
  (l/derived (l/in [:dashboard-local :search-result]) st/state))

(mf/defc search-page
  [{:keys [team search-term] :as props}]
  (let [result (mf/deref result-ref)
        locale (mf/deref i18n/locale)]

    (mf/use-effect
     (mf/deps team search-term)
     (st/emitf (dd/search-files {:team-id (:id team)
                                 :search-term search-term})))

    [:section.dashboard-grid-container.search
     (cond
       (empty? search-term)
       [:div.grid-empty-placeholder
        [:div.icon i/search]
        [:div.text (t locale "dashboard.search.type-something")]]

       (nil? result)
       [:div.grid-empty-placeholder
        [:div.icon i/search]
        [:div.text  (t locale "dashboard.search.searching-for" search-term)]]

       (empty? result)
       [:div.grid-empty-placeholder
        [:div.icon i/search]
        [:div.text  (t locale "dashboard.search.no-matches-for" search-term)]]

       :else
       [:& grid {:files result
                 :hide-new? true}])]))
