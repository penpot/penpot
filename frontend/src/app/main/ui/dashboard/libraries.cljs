;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.libraries
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.team :as dtm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc libraries-page*
  {::mf/props :obj}
  [{:keys [team default-project]}]
  (let [files
        (mf/deref refs/shared-files)

        files
        (mf/with-memo [files]
          (->> (vals files)
               (sort-by :modified-at)
               (reverse)))

        can-edit
        (-> team :permissions :can-edit)

        [rowref limit]
        (hooks/use-dynamic-grid-item-width 350)]

    (mf/with-effect [team]
      (let [tname (if (:is-default team)
                    (tr "dashboard.your-penpot")
                    (:name team))]
        (dom/set-html-title (tr "title.dashboard.shared-libraries" tname))))

    (mf/with-effect [team]
      (st/emit! (dtm/fetch-shared-files)
                (dd/clear-selected-files)))

    [:*
     [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
      [:div#dashboard-libraries-title {:class (stl/css :dashboard-title)}
       [:h1 (tr "dashboard.libraries-title")]]]
     [:section {:class (stl/css :dashboard-container :no-bg :dashboard-shared)  :ref rowref}
      [:& grid {:files files
                :project default-project
                :origin :libraries
                :limit limit
                :can-edit can-edit}]]]))

