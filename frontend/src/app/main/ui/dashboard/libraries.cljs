;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.libraries
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(defn files-ref
  [team-id]
  (l/derived (l/in [:shared-files team-id]) st/state))

(mf/defc libraries-page
  [{:keys [team] :as props}]
  (let [files-ref (mf/use-memo (mf/deps (:id team)) #(files-ref (:id team)))
        files-map (mf/deref files-ref)
        files     (->> (vals files-map)
                       (sort-by :modified-at)
                       (reverse))]
    (mf/use-effect
     (mf/deps team)
     #(st/emit! (dd/fetch-shared-files {:team-id (:id team)})))

    [:*
     [:header.dashboard-header
      [:div.dashboard-title
       [:h1 (tr "dashboard.header.libraries")]]]
     [:section.dashboard-container
      [:& grid {:files files}]]]))

