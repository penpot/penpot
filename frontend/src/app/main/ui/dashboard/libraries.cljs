;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.libraries
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc libraries-page
  [{:keys [team] :as props}]
  (let [files-map (mf/deref refs/dashboard-shared-files)
        files     (->> (vals files-map)
                       (sort-by :modified-at)
                       (reverse))]
    (mf/use-effect
     (mf/deps team)
     (fn []
       (dom/set-html-title (tr "title.dashboard.shared-libraries"
                               (if (:is-default team)
                                 (tr "dashboard.your-penpot")
                                 (:name team))))))

    (mf/use-effect
     (st/emitf (dd/fetch-shared-files)
               (dd/clear-selected-files)))

    [:*
     [:header.dashboard-header
      [:div.dashboard-title
       [:h1 (tr "dashboard.libraries-title")]]]
     [:section.dashboard-container
      [:& grid {:files files}]]]))

