;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.libraries
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.dashboard :as dd]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc libraries-page
  [{:keys [team] :as props}]
  (let [files-map       (mf/deref refs/dashboard-shared-files)
        projects        (mf/deref refs/dashboard-projects)

        default-project (->> projects vals (d/seek :is-default))

        files           (mf/with-memo [files-map]
                          (if (nil? files-map)
                            nil
                            (->> (vals files-map)
                                 (sort-by :modified-at)
                                 (reverse))))

        components-v2   (features/use-feature "components/v2")

        [rowref limit] (hooks/use-dynamic-grid-item-width 350)]

    (mf/with-effect [team]
      (when team
        (let [tname (if (:is-default team)
                      (tr "dashboard.your-penpot")
                      (:name team))]
          (dom/set-html-title (tr "title.dashboard.shared-libraries" tname)))))

    (mf/with-effect []
      (st/emit! (dd/fetch-shared-files (:id team))
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
                :library-view? components-v2}]]]))

