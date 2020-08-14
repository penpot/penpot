;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.dashboard.search
  (:require
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.store :as st]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.main.ui.dashboard.grid :refer [grid]]))

;; --- Component: Search

(def search-result-ref
  (-> #(get-in % [:dashboard-local :search-result])
      (l/derived st/state)))

(mf/defc search-page
  [{:keys [team-id search-term] :as props}]
  (let [search-result (mf/deref search-result-ref)
        locale (i18n/use-locale)]
    (mf/use-effect
     (mf/deps search-term)
     #(st/emit! (dsh/initialize-search team-id search-term)))

    [:section.search-page
      [:section.dashboard-grid
        (cond
          (empty? search-term)
          [:div.grid-files-empty
           [:div.grid-files-desc (t locale "dashboard.search.type-something")]]

          (nil? search-result)
          [:div.grid-files-empty
           [:div.grid-files-desc (t locale "dashboard.search.searching-for" search-term)]]

          (empty? search-result)
          [:div.grid-files-empty
           [:div.grid-files-desc (t locale "dashboard.search.no-matches-for" search-term)]]

          :else
          [:& grid { :files search-result :hide-new? true}])]]))

