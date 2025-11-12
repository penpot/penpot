;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.libraries
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.dashboard.shortcuts :as sc]
   [app.main.data.team :as dtm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid*]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:selected-files
  (l/derived (fn [state]
               (let [selected (get state :selected-files)
                     files    (get state :shared-files)]
                 (refs/extract-selected-files files selected)))
             st/state))

(mf/defc libraries-page*
  {::mf/props :obj}
  [{:keys [team default-project]}]
  (let [files
        (mf/deref refs/shared-files)

        team-id
        (get team :id)

        can-edit
        (-> team :permissions :can-edit)

        files
        (mf/with-memo [files team-id]
          (->> (vals files)
               (filter #(= team-id (:team-id %)))
               (sort-by :modified-at)
               (reverse)))

        selected-files
        (mf/deref ref:selected-files)

        [rowref limit]
        (hooks/use-dynamic-grid-item-width 350)]

    (mf/with-effect [team]
      (let [tname (if (:is-default team)
                    (tr "dashboard.your-penpot")
                    (:name team))]
        (dom/set-html-title (tr "title.dashboard.shared-libraries" tname))))

    (mf/with-effect [team-id]
      (st/emit! (dtm/fetch-shared-files team-id)
                (dd/clear-selected-files)))

    (hooks/use-shortcuts ::dashboard sc/shortcuts-drafts-libraries)

    [:*
     [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
      [:div#dashboard-libraries-title {:class (stl/css :dashboard-title)}
       [:h1 (tr "dashboard.libraries-title")]]]

     [:section {:class (stl/css :dashboard-container :no-bg :dashboard-shared)
                :ref rowref}
      [:> grid* {:files files
                 :selected-files selected-files
                 :project default-project
                 :origin :libraries
                 :limit limit
                 :can-edit can-edit}]]]))

